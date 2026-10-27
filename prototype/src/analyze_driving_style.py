"""운전 성향 진단 1단계 — 기존 CSV로 페달 지표가 사람(·연기한 성향)별로 갈리는지 본다 (Phase F-1, 앱 수정 없음).

질문: "주행 구간 하나만 보고 누구(어떤 성향)인지 구별할 수 있나?"
  1. 정상 주행 행만 모아 사람/페르소나별로 N분 구간으로 자른다(진단도 결국 주행 한 토막으로 판단하므로 같은 조건).
  2. 구간마다 페달 지표를 계산한다(아래 StyleMetrics).
  3. 두 사람 A·B 의 구간 지표로 구별도 = P(A 구간 값 > B 구간 값)를 잰다.
     0.5 = 구별 불가, 0 또는 1 에 가까울수록 구간 하나로도 확실히 갈린다(AUC 와 같은 뜻).

입력은 4Hz CSV(판정 윈도우당 1행)라 시간 해상도가 0.25초다. 밟는 속도는 변화율(accel_rate)이 없는 옛 파일과
비교하려고 행 사이 level 증가량(0.25초당)으로 잰다. 차량 속도(GPS)는 2026-10-25 이후 CSV 에만 있어 여기선 안 쓴다.

실행:  uv run python src/analyze_driving_style.py [csv_또는_디렉터리 ...] [--chunk-min 5]
"""

from __future__ import annotations

import statistics
import sys
from dataclasses import dataclass, fields

from dataset import ROWS_PER_SEC, Row, default_data_dir, load_sessions
from peob.features import ACCEL_ACTIVE_THRESHOLD
from peob.style import PressEvent, WindowStream
from train_style_reference import estimate_brake_on, to_windows

# 구간에 엑셀 이벤트가 이보다 적으면 지표가 의미 없어 버린다
MIN_EVENTS_PER_CHUNK = 5

REAL_PEOPLE = ("self", "dad", "seungmin")
ACTED = ("beginner", "normal", "aggressive")


@dataclass(frozen=True)
class StyleMetrics:
    """분석용 지표(절대값 포함) — 앱 진단 지표(peob.style.StyleMetrics, 상대·시간 지표)와 별개.
    이벤트·전환 추출은 앱과 같은 peob.style.WindowStream 을 쓴다(2026-10-25 이관 — 이 파일에 따로 있던 구현 제거)."""

    depth_p50: float        # 밟고 있을 때 깊이 중앙값
    depth_p90: float        # 밟고 있을 때 깊이 상위 10%
    peak_p50: float         # 이벤트별 최고 깊이 중앙값
    rise_p50: float         # 이벤트별 밟는 속도 중앙값
    rise_p90: float         # 이벤트별 밟는 속도 상위 10%(급가속 성향)
    start_rise_p50: float   # 정차 후 출발 이벤트의 밟는 속도 중앙값(급출발 성향). 없으면 nan
    events_per_min: float
    duration_mean_sec: float
    switch_p50_sec: float   # 엑셀 떼고 브레이크 밟기까지 시간 중앙값. 없으면 nan


def _quantile(values: list[float], q: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    return s[min(int(q * len(s)), len(s) - 1)]


def stream_rows(rows: list[Row], brake_on: float) -> tuple[list[PressEvent], list[float]]:
    """(엑셀 이벤트, 페달 전환 시간) — 앱과 같은 추출기에 행을 차례로 흘린다."""
    stream = WindowStream(brake_on)
    events: list[PressEvent] = []
    switches: list[float] = []
    for w in to_windows(rows):
        event, switch, *_ = stream.step(w)
        if event is not None:
            events.append(event)
        if switch is not None:
            switches.append(switch)
    return events, switches


def style_metrics(rows: list[Row]) -> StyleMetrics | None:
    events, switches = stream_rows(rows, estimate_brake_on(rows))
    if len(events) < MIN_EVENTS_PER_CHUNK:
        return None
    pressed = [r.level for r in rows if r.level >= ACCEL_ACTIVE_THRESHOLD]
    rises = [e.rise for e in events]
    return StyleMetrics(
        depth_p50=_quantile(pressed, 0.5),
        depth_p90=_quantile(pressed, 0.9),
        peak_p50=_quantile([e.peak for e in events], 0.5),
        rise_p50=_quantile(rises, 0.5),
        rise_p90=_quantile(rises, 0.9),
        start_rise_p50=_quantile([e.rise for e in events if e.after_stop], 0.5),
        events_per_min=len(events) / (len(rows) / ROWS_PER_SEC / 60),
        duration_mean_sec=statistics.fmean(e.duration_sec for e in events),
        switch_p50_sec=_quantile(switches, 0.5),
    )


def driving_rows_by_persona(paths: list[str]) -> dict[str, list[list[Row]]]:
    """페르소나별 정상 주행 행 묶음(세션 단위, 세션 경계를 넘어 잇지 않는다)."""
    out: dict[str, list[list[Row]]] = {}
    for session in load_sessions(paths):
        by_persona: dict[str, list[Row]] = {}
        for row in session.rows:
            if row.is_normal_driving:
                by_persona.setdefault(row.persona, []).append(row)
        for persona, rows in by_persona.items():
            out.setdefault(persona, []).append(rows)
    return out


def chunk_metrics(sessions: list[list[Row]], chunk_rows: int) -> list[StyleMetrics]:
    result: list[StyleMetrics] = []
    for rows in sessions:
        for start in range(0, len(rows) - chunk_rows + 1, chunk_rows):
            m = style_metrics(rows[start:start + chunk_rows])
            if m is not None:
                result.append(m)
    return result


def separation(a: list[float], b: list[float]) -> float:
    """P(a > b) + 0.5·P(a == b). 0.5 면 구별 불가."""
    a = [x for x in a if x == x]   # nan 제외
    b = [x for x in b if x == x]
    if not a or not b:
        return float("nan")
    wins = sum((x > y) + 0.5 * (x == y) for x in a for y in b)
    return wins / (len(a) * len(b))


def _report(title: str, groups: dict[str, list[StyleMetrics]]) -> None:
    names = [n for n, ms in groups.items() if ms]
    if len(names) < 2:
        print(f"\n== {title}: 비교할 그룹이 2개 미만 — 건너뜀 ==")
        return
    print(f"\n== {title} ==")
    print("  구간 수: " + ", ".join(f"{n} {len(groups[n])}" for n in names))
    header = f"  {'지표':<18}" + "".join(f"{n:>16}" for n in names)
    print(header + "    구별도(쌍별, 0.5=불가)")
    for f in fields(StyleMetrics):
        cells = []
        for n in names:
            vals = [v for v in (getattr(m, f.name) for m in groups[n]) if v == v]
            cells.append(f"{_quantile(vals, 0.5):7.3f} ±{statistics.pstdev(vals) if len(vals) > 1 else 0:5.3f}" if vals else f"{'—':>14}")
        pairs = []
        for i, a in enumerate(names):
            for b in names[i + 1:]:
                s = separation([getattr(m, f.name) for m in groups[a]], [getattr(m, f.name) for m in groups[b]])
                mark = "★" if s == s and abs(s - 0.5) >= 0.35 else " "
                pairs.append(f"{a[:4]}/{b[:4]} {s:.2f}{mark}")
        print(f"  {f.name:<18}" + "".join(f"{c:>16}" for c in cells) + "    " + "  ".join(pairs))
    print("  (값: 구간 중앙값 ±표준편차 / ★ = 구별도 0.15 이하 또는 0.85 이상 — 구간 하나로도 대부분 갈림)")


def main() -> None:
    args = sys.argv[1:]
    chunk_min = 5.0
    if "--chunk-min" in args:
        i = args.index("--chunk-min")
        chunk_min = float(args[i + 1])
        args = args[:i] + args[i + 2:]
    chunk_rows = int(chunk_min * 60 * ROWS_PER_SEC)
    by_persona = driving_rows_by_persona(args or [str(default_data_dir())])
    print(f"구간 길이 {chunk_min:g}분 ({chunk_rows}행)")
    _report("실제 사람", {p: chunk_metrics(by_persona.get(p, []), chunk_rows) for p in REAL_PEOPLE})
    _report("혼자 연기한 성향", {p: chunk_metrics(by_persona.get(p, []), chunk_rows) for p in ACTED})


if __name__ == "__main__":
    main()
