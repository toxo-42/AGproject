"""실주행 CSV로 개인화 파이프라인 1차 검증 — Phase B 오프라인 분석.

BleService.kt 가 기록한 CSV(4Hz, 판정 윈도우 하나당 1행)를 읽어서:
  1. style(강/보통/약)별 feature 비교 — extract_features() 로 정말 세기가 갈리는지.
  2. 세션별로 calibrate_thresholds() 를 다시 돌려 accel_high 가 세션마다 얼마나
     흔들리는지(안정성) 확인 — normal/strong 경계가 불안정했던 문제(§진행상황_및_로드맵.md
     Phase B, 2026-07-13 실험)가 이번에 모은 데이터에서도 재현되는지 보는 용도.
  3. 오조작 재현 세션의 수동 label(정답) vs 폰이 실시간으로 찍은 pedal_err(예측)를
     매칭해 민감도(recall)와 오탐 후보를 확인.

실행:  uv run python src/analyze_field_data.py [csv_또는_디렉터리 ...]
       인자 없이 실행하면 저장소 루트의 "수집 데이터" 디렉터리를 기본으로 스캔한다.

⚠️ CSV 행은 200Hz 원시 샘플이 아니라 판정 윈도우(0.25초)당 대표값 1개(윈도우 마지막
   샘플)라서, extract_features() 결과가 raw 200Hz 스트림으로 직접 돌렸을 때와 완전히
   같지는 않다. 다만 모든 세션에 같은 축소 방식이 동일하게 적용되니, 세션 간 상대
   비교(strong vs weak, 세션 A vs 세션 B)에는 문제없다 — 이 스크립트의 목적이
   "절대값 확정"이 아니라 "지금 데이터로 파이프라인이 굴러가는지, 방향이 맞는지" 확인이라
   이 정도 근사로 충분하다.
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

from calibration import calibrate_thresholds
from features import extract_features

STYLE_KEYS = ("weak", "normal", "strong")

# CSV 행은 200Hz 원시 스트림이 아니라 판정 윈도우(0.25초)당 1행 = 4Hz. extract_features() 의
# press_rate_per_min/press_duration_mean_sec 는 sample_rate_hz 로 "시간"을 역산하므로, 기본값
# 200Hz 그대로 넘기면 50배(200/4) 어긋난다 — 반드시 이 값을 넘겨야 한다.
CSV_ROW_RATE_HZ = 4.0

# label 구간 경계에서 앞뒤로 이만큼(행 수, 4Hz 기준 4행=1초)까지는 판정 지연으로 보고
# pedal_err 매칭을 허용한다. BleService.judgeWindow() 의 지속시간 판정(MISOP_SUSTAIN_WINDOWS=2
# + MISOP_GAP_TOLERANCE_WINDOWS=1)때문에 경고가 원시 압력 스파이크보다 최대 0.5~1초 정도
# 늦게 뜰 수 있어서, 라벨과 pedal_err를 같은 행 단위로 비교하면 안 된다(2026-07-18 실데이터에서
# 실제로 관찰된 현상 — §대화 기록 참고). 넉넉하게 2초(8행) 잡는다.
MATCH_TOLERANCE_ROWS = 8


def load_rows(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _parse_float(s: str | None) -> float | None:
    if s is None or s in ("none", ""):
        return None
    return float(s)


def samples_by_style(rows: list[dict]) -> dict[str, list[list[float]]]:
    """style 컬럼 기준으로 [accel, brake] 샘플을 묶는다. style 컬럼이 없는 구 스키마는 자연히 빈 채로 남는다."""
    buckets: dict[str, list[list[float]]] = {k: [] for k in STYLE_KEYS}
    for row in rows:
        style = row.get("style")
        if style not in STYLE_KEYS:
            continue
        accel = _parse_float(row.get("accel"))
        brake = _parse_float(row.get("brake"))
        if accel is None or brake is None:
            continue
        buckets[style].append([accel, brake])
    return buckets


def feature_report(name: str, rows: list[dict]) -> None:
    buckets = samples_by_style(rows)
    if not any(buckets.values()):
        return
    print(f"\n=== {name}: style별 feature ===")
    cols = ["n", "accel_p90", "accel_active_p90", "accel_active_mean",
            "press_rate_per_min", "press_duration_mean_sec", "brake_mean"]
    print(f"  {'style':<8}" + "".join(f"{c:>22}" for c in cols))
    for style in STYLE_KEYS:
        samples = buckets[style]
        if not samples:
            continue
        f = extract_features(samples, sample_rate_hz=CSV_ROW_RATE_HZ)
        values = [len(samples), f["accel_p90"], f["accel_active_p90"], f["accel_active_mean"],
                  f["press_rate_per_min"], f["press_duration_mean_sec"], f["brake_mean"]]
        print(f"  {style:<8}" + "".join(f"{v:>22}" for v in values))


def calibration_stability_report(sessions: list[tuple[str, list[dict]]]) -> None:
    print("\n=== 세션별 calibrate_thresholds() 안정성 (accel_high 재계산) ===")
    print("  (참고: 캘리브레이션 세션이 아니라 style 라벨링 세션이라도, 그 세션 데이터를")
    print("   캘리브레이션 입력이라 가정하면 accel_high가 얼마로 잡히는지를 보는 것 — 세션 간")
    print("   변동폭이 크면 normal/strong 경계 불안정 문제가 이번 데이터에도 있다는 뜻)")
    for name, rows in sessions:
        samples = [
            [a, b]
            for a, b in (
                (_parse_float(r.get("accel")), _parse_float(r.get("brake"))) for r in rows
            )
            if a is not None and b is not None
        ]
        if not samples:
            continue
        th = calibrate_thresholds(samples)
        print(f"  {name}: accel_high={th['accel_high']}  (n={len(samples)})")


def misop_detection_report(name: str, rows: list[dict]) -> None:
    n = len(rows)
    labels = [1 if r.get("label") == "1" else 0 for r in rows]
    pedal_errs = [r.get("pedal_err") == "pedal_err" for r in rows]

    episodes: list[tuple[int, int]] = []
    start: int | None = None
    for i, v in enumerate(labels):
        if v == 1 and start is None:
            start = i
        elif v == 0 and start is not None:
            episodes.append((start, i - 1))
            start = None
    if start is not None:
        episodes.append((start, n - 1))

    if not episodes:
        return

    covered: set[int] = set()
    matched = 0
    for s, e in episodes:
        lo, hi = max(0, s - MATCH_TOLERANCE_ROWS), min(n - 1, e + MATCH_TOLERANCE_ROWS)
        covered.update(range(lo, hi + 1))
        if any(pedal_errs[lo:hi + 1]):
            matched += 1

    total_pedal_err = sum(pedal_errs)
    unmatched_pedal_err = sum(1 for i, v in enumerate(pedal_errs) if v and i not in covered)

    print(f"\n=== {name}: 오조작 라벨(정답) vs pedal_err(실시간 판정) 매칭 ===")
    print(f"  라벨 구간(재현 이벤트) 수: {len(episodes)}")
    print(f"  판정이 잡아낸 구간 수: {matched} / {len(episodes)}  (recall={matched / len(episodes):.0%})")
    print(f"  pedal_err 총 발생: {total_pedal_err}건, 라벨 구간과 안 겹치는(오탐 후보): {unmatched_pedal_err}건")


def armed_state_report(name: str, rows: list[dict]) -> None:
    """긴 홀드형 오조작이 왜 놓쳐지는지 진단 — 놓친 라벨 구간마다 armed/consecutive/
    accel_score/accel_rate를 행 단위로 그대로 출력한다. armed가 구간 중간에 False로
    떨어지는 시점이 보이면 "무장이 도중에 풀렸다" 가설이 확정된다(2026-07-18).
    accel_score/armed 컬럼이 없는(구 스키마) 파일은 스킵.
    """
    if not rows or "armed" not in rows[0] or "accel_score" not in rows[0]:
        return

    n = len(rows)
    labels = [1 if r.get("label") == "1" else 0 for r in rows]
    pedal_errs = [r.get("pedal_err") == "pedal_err" for r in rows]

    episodes: list[tuple[int, int]] = []
    start: int | None = None
    for i, v in enumerate(labels):
        if v == 1 and start is None:
            start = i
        elif v == 0 and start is not None:
            episodes.append((start, i - 1))
            start = None
    if start is not None:
        episodes.append((start, n - 1))

    missed = []
    for s, e in episodes:
        lo, hi = max(0, s - MATCH_TOLERANCE_ROWS), min(n - 1, e + MATCH_TOLERANCE_ROWS)
        if not any(pedal_errs[lo:hi + 1]):
            missed.append((s, e))

    if not missed:
        print(f"\n=== {name}: 놓친 라벨 구간 없음 — armed 상태 진단 불필요 ===")
        return

    print(f"\n=== {name}: 놓친 구간 {len(missed)}개 — armed/score 상태 추적 ===")
    for s, e in missed:
        lo, hi = max(0, s - 2), min(n - 1, e + 2)
        print(f"\n  --- 구간 {s}~{e} ({(e - s + 1) * 0.25:.2f}초) ---")
        print(f"  {'행':>5} {'time':>13} {'accel':>7} {'rate':>7} {'score':>7} {'armed':>6} {'cons':>4}")
        for i in range(lo, hi + 1):
            r = rows[i]
            marker = " *" if s <= i <= e else ""
            print(f"  {i:>5} {r.get('time', ''):>13} {r.get('accel', ''):>7} "
                  f"{r.get('accel_rate', ''):>7} {r.get('accel_score', ''):>7} "
                  f"{r.get('armed', ''):>6} {r.get('consecutive', ''):>4}{marker}")


def _percentile(xs: list[float], p: float) -> float:
    if not xs:
        return 0.0
    s = sorted(xs)
    if len(s) == 1:
        return s[0]
    rank = (p / 100) * (len(s) - 1)
    lo, hi = int(rank), min(int(rank) + 1, len(s) - 1)
    frac = rank - lo
    return s[lo] + (s[hi] - s[lo]) * frac


def _find_runs(flags: list[bool]) -> list[tuple[int, int]]:
    """True인 연속 구간의 (시작, 끝) 인덱스 목록."""
    runs: list[tuple[int, int]] = []
    start: int | None = None
    for i, v in enumerate(flags):
        if v and start is None:
            start = i
        elif not v and start is not None:
            runs.append((start, i - 1))
            start = None
    if start is not None:
        runs.append((start, len(flags) - 1))
    return runs


def accel_rate_report(name: str, rows: list[dict]) -> None:
    """accel_rate_high 실측 튜닝용 — label=1(오조작 재현) 이벤트 각각의 **최댓값**과,
    label=0인 정상 조작(accel>=0.1) 이벤트 각각의 최댓값을 나란히 비교한다.

    ⚠️ 처음엔 이벤트 안의 모든 행을 그냥 다 풀어서 백분위를 봤는데, misop처럼 "꾹 눌러
    유지"하는 이벤트는 대부분의 행이 이미 레벨이 유지되는 정체 구간이라 rate(변화율)가
    0에 가깝다 — trigger는 원래 순간적 사건이라 이벤트를 통째로 풀면 신호가 희석된다
    (실측 데이터로 확인, 2026-07-18). judge.py의 trigger 판정 자체가 "윈도우 내 최댓값"
    기준이므로, 여기서도 이벤트당 최댓값으로 비교해야 같은 걸 재는 것이다.
    """
    if not rows or "accel_rate" not in rows[0]:
        return

    rates = [_parse_float(r.get("accel_rate")) for r in rows]
    accels = [_parse_float(r.get("accel")) for r in rows]
    is_misop = [r.get("label") == "1" for r in rows]
    is_normal_active = [
        (not m) and (a is not None and a >= 0.1) for m, a in zip(is_misop, accels)
    ]

    def _event_peaks(flags: list[bool]) -> list[float]:
        peaks = []
        for s, e in _find_runs(flags):
            seg = [rt for rt in rates[s:e + 1] if rt is not None]
            if seg:
                peaks.append(max(seg))
        return peaks

    misop_peaks = _event_peaks(is_misop)
    normal_peaks = _event_peaks(is_normal_active)

    if not misop_peaks and not normal_peaks:
        return

    print(f"\n=== {name}: 이벤트별 accel_rate 최댓값 (accel_rate_high 튜닝용) ===")

    def _line(label: str, xs: list[float]) -> None:
        if not xs:
            print(f"  {label}: 이벤트 없음")
            return
        print(f"  {label}: 이벤트 수={len(xs)}  min={min(xs):.3f}  p50={_percentile(xs, 50):.3f}"
              f"  p90={_percentile(xs, 90):.3f}  max={max(xs):.3f}")

    _line("label=1 (오조작 재현) 이벤트", misop_peaks)
    _line("label=0 정상 조작(accel>=0.1) 이벤트", normal_peaks)

    if misop_peaks and normal_peaks:
        misop_min = min(misop_peaks)
        normal_max = max(normal_peaks)
        candidate = (misop_min + normal_max) / 2
        print(f"  → 후보 accel_rate_high ≈ {candidate:.3f} "
              f"(오조작 이벤트 최솟값={misop_min:.3f} 과 정상 이벤트 최댓값={normal_max:.3f} 의 중간)")
        if misop_min <= normal_max:
            print("  ⚠️ 두 분포가 겹칩니다 — 이 세션만으론 깔끔한 컷오프가 안 나옵니다. "
                  "재현 방식(스냅 vs 서서히)을 더 뚜렷하게 나눠서 재수집 검토.")
        else:
            print(f"  ✅ 완전히 갈립니다 (정상 이벤트 최댓값 {normal_max:.3f} < 오조작 이벤트 최솟값 {misop_min:.3f})")


def find_csv_files(paths: list[str]) -> list[Path]:
    files: list[Path] = []
    for p in paths:
        path = Path(p)
        if path.is_dir():
            files.extend(sorted(path.glob("*.csv")))
        elif path.suffix == ".csv":
            files.append(path)
    return files


def main() -> None:
    args = sys.argv[1:]
    if not args:
        default_dir = Path(__file__).resolve().parents[2] / "수집 데이터"
        args = [str(default_dir)]

    files = find_csv_files(args)
    if not files:
        print("CSV 파일을 못 찾았습니다.")
        return

    style_sessions: list[tuple[str, list[dict]]] = []
    for path in files:
        rows = load_rows(path)
        if not rows:
            continue

        feature_report(path.name, rows)
        misop_detection_report(path.name, rows)
        accel_rate_report(path.name, rows)
        armed_state_report(path.name, rows)

        if any(r.get("style") in STYLE_KEYS for r in rows):
            style_sessions.append((path.name, rows))

    if style_sessions:
        calibration_stability_report(style_sessions)


if __name__ == "__main__":
    main()
