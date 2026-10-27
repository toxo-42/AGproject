"""성향 라벨 CSV로 운전 성향 기준점(style_reference.json)을 만든다 — 진단 파이프라인의 "학습 → 산출물" 단계.

모델: 최근접 중심(nearest centroid). 성향별로 N분 구간 지표(peob.style.StyleMetrics)를 모아
  중심 = 지표별 중앙값, 척도 = 전체 구간의 표준편차(z-score 표준화)
를 저장하고, 앱(peob.style.diagnose)은 내 지표와 표준화 거리가 가장 가까운 성향을 고른다.
데이터가 적을 때도 과적합이 거의 없고, 결과가 "평균과 얼마나 다른가"라 근거를 화면에 그대로 보여줄 수 있다.

라벨: CSV persona 컬럼(beginner/normal/aggressive — 화면에선 초보/능숙/난폭). 지금은 개발자 1인이 연기한 데이터뿐이라
  "사람 간 차이"가 아니라 "연기 차이"를 배운다 → 실데이터(여러 사람 + 정답 라벨)가 쌓이면 이 스크립트를 다시 돌려 교체.
지표 계산은 앱과 같은 peob.style 경로(metrics_from_windows)를 쓴다.

실행:  uv run python src/train_style_reference.py [csv_또는_디렉터리 ...] [--chunk-min 2] [--out 경로]
       결과 기본 위치는 backend/src/peob/style_reference.json(앱 번들). 실험만 할 땐 --out.
"""

from __future__ import annotations

import json
import statistics
import sys
from dataclasses import asdict
from datetime import datetime
from pathlib import Path

import peob.style
from dataset import ROWS_PER_SEC, Row, default_data_dir, load_sessions
from peob.style import Window, metrics_from_windows

LABELS = ("beginner", "normal", "aggressive")
# 앱은 영점 조절의 brake_low 를 쓰지만 CSV 엔 그 값이 없는 파일이 많다 → 구간 브레이크 하위 20% + 여유로 추정
# (케이스 예압으로 뗀 상태가 ~0.2 인 세션 대응, calibration.BRAKE_LOW_MARGIN 과 같은 생각)
BRAKE_ON_MARGIN = 0.10
MIN_CHUNKS_PER_FEATURE = 1   # 성향마다 이만큼의 구간에 값이 있어야 그 지표를 중심에 넣는다
# 기준점에서 빼는 지표(앱 계산은 그대로 함). GPS 급가감속은 2분 구간에 0~1회뿐이라 중앙값이 0 아니면 30/h 로 튀고
# (2026-10-27 실측: 평범 30, 난폭 0 — 순서가 뒤집힘), 앱은 수십 분 누적으로 재서 척도도 안 맞는다.
# 성향마다 수십 분 이상 쌓이면 긴 구간으로 다시 넣는다.
EXCLUDED_FEATURES = frozenset({"harsh_accel_per_h", "harsh_decel_per_h"})


def estimate_brake_on(rows: list[Row]) -> float:
    brakes = sorted(r.brake for r in rows)
    return brakes[int(0.2 * (len(brakes) - 1))] + BRAKE_ON_MARGIN


def to_windows(rows: list[Row]) -> list[Window]:
    return [Window(r.level, r.brake, r.speed) for r in rows]


def labeled_chunks(paths: list[str], chunk_rows: int) -> dict[str, list[dict[str, float | None]]]:
    """성향별 구간 지표 목록. 정상 주행 행만, 세션 경계를 넘어 잇지 않는다."""
    out: dict[str, list[dict[str, float | None]]] = {label: [] for label in LABELS}
    for session in load_sessions(paths):
        by_label: dict[str, list[Row]] = {}
        for row in session.rows:
            if row.is_normal_driving and row.persona in out:
                by_label.setdefault(row.persona, []).append(row)
        for label, rows in by_label.items():
            for start in range(0, len(rows) - chunk_rows + 1, chunk_rows):
                chunk = rows[start:start + chunk_rows]
                m = metrics_from_windows(to_windows(chunk), estimate_brake_on(chunk))
                if m is not None:
                    out[label].append(asdict(m))
    return out


def fit(chunks: dict[str, list[dict[str, float | None]]]) -> tuple[dict[str, dict[str, float]], dict[str, float]]:
    """(성향별 중심, 지표별 척도). 모든 성향에 충분한 값이 있는 지표만 쓴다(한쪽만 있으면 비교가 안 된다)."""
    labels = [label for label, cs in chunks.items() if cs]
    features = [f for f in (next(iter(chunks[labels[0]])) if labels else {}) if f not in EXCLUDED_FEATURES
                if all(sum(c[f] is not None for c in chunks[label]) >= MIN_CHUNKS_PER_FEATURE for label in labels)]
    centroids = {label: {f: statistics.median(c[f] for c in chunks[label] if c[f] is not None) for f in features}
                 for label in labels}
    # 척도는 성향 안 흩어짐이 아니라 전체 흩어짐 — 성향당 구간이 2~3개뿐이면 성향 안 표준편차가 0 에 가까워져
    # (2026-10-25 full_press_ratio ±0.007) 그 지표 하나가 거리를 좌우했다.
    scale: dict[str, float] = {}
    for f in features:
        std = statistics.pstdev([c[f] for label in labels for c in chunks[label] if c[f] is not None])
        if std > 0:
            scale[f] = std
    return centroids, scale


def _nearest(chunk: dict[str, float | None], centroids: dict[str, dict[str, float]], scale: dict[str, float]) -> str | None:
    ref = peob.style.Reference(centroids=centroids, scale=scale, trained_at="", chunks={})
    result = peob.style.diagnose(peob.style.StyleMetrics(**chunk), ref)
    return result.label if result else None


def leave_one_out(chunks: dict[str, list[dict[str, float | None]]]) -> dict[str, dict[str, int]]:
    """구간 하나씩 빼고 중심을 다시 만들어 맞히는지 — 성향별 예측 분포(혼동 행렬)."""
    confusion: dict[str, dict[str, int]] = {label: {} for label in chunks}
    for label, cs in chunks.items():
        for i, chunk in enumerate(cs):
            rest = {k: (v[:i] + v[i + 1:] if k == label else v) for k, v in chunks.items()}
            if not rest[label]:
                continue
            centroids, scale = fit(rest)
            predicted = _nearest(chunk, centroids, scale) or "none"
            confusion[label][predicted] = confusion[label].get(predicted, 0) + 1
    return confusion


def main() -> None:
    args = sys.argv[1:]
    chunk_min, out_path = 2.0, Path(peob.style.__file__).with_name(peob.style.REFERENCE_FILE)
    if "--chunk-min" in args:
        i = args.index("--chunk-min")
        chunk_min = float(args[i + 1])
        args = args[:i] + args[i + 2:]
    if "--out" in args:
        i = args.index("--out")
        out_path = Path(args[i + 1])
        args = args[:i] + args[i + 2:]
    paths = args or [str(default_data_dir())]
    sessions = load_sessions(paths)
    synthetic = sum(1 for s in sessions if s.synthetic)
    chunks = labeled_chunks(paths, int(chunk_min * 60 * ROWS_PER_SEC))
    print(f"== 입력: 세션 {len(sessions)}개 (합성 {synthetic}) · 구간 {chunk_min:g}분 ==")
    print("  구간 수: " + ", ".join(f"{k} {len(v)}" for k, v in chunks.items()))
    if sum(1 for v in chunks.values() if v) < 2:
        print("  성향 라벨이 2종류 이상 필요합니다.")
        return

    centroids, scale = fit(chunks)
    print("\n== 성향별 중심 (척도) ==")
    for f, s in scale.items():
        print(f"  {f:<18}" + "".join(f"{label[:6]:>8} {centroids[label][f]:.3f}" for label in centroids) + f"   (±{s:.3f})")
    print(f"  기준점에서 일부러 뺀 지표: {', '.join(sorted(EXCLUDED_FEATURES))} (EXCLUDED_FEATURES 주석 참고)")
    unused = [f for f in next(iter(next(v for v in chunks.values() if v))) if f not in scale and f not in EXCLUDED_FEATURES]
    if unused:
        print(f"  ⚠️ 데이터가 없어 빠진 지표: {', '.join(unused)} — 브레이크·GPS 가 기록된 라벨 데이터가 생기면 자동으로 들어간다.")

    confusion = leave_one_out(chunks)
    correct = sum(confusion[label].get(label, 0) for label in confusion)
    total = sum(sum(v.values()) for v in confusion.values())
    print("\n== 구간 하나씩 빼고 검증 ==")
    for label, row in confusion.items():
        print(f"  {label:<11} → {row}")
    print(f"  정확도 {correct}/{total}" + ("  ⚠️ 연기 데이터·구간 수가 적어 참고용" if total < 30 else ""))

    result = {
        "version": 1,
        "trained_at": datetime.now().isoformat(timespec="seconds"),
        "model": "nearest_centroid",
        "chunk_min": chunk_min,
        "centroids": centroids,
        "scale": {f: round(s, 4) for f, s in scale.items()},
        "chunks": {k: len(v) for k, v in chunks.items()},
        "data": {"sessions": len(sessions), "synthetic_sessions": synthetic},
        "validation": {"leave_one_out_correct": correct, "total": total},
    }
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n저장: {out_path}")


if __name__ == "__main__":
    main()
