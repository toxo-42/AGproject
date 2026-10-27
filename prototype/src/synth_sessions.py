"""실주행 CSV를 재조합해 그럴듯한 합성 세션 CSV를 만든다 — "파이프라인 먼저, 실데이터로 교체" 방향의 데모 데이터.

방법 (2026-10-28):
  1. 조각 자르기: 페르소나 라벨 세션을 엑셀을 뗀 순간마다 잘라 "공백/브레이크 + 엑셀 밟기 한 번" 조각으로 만든다.
  2. 재조합: 같은 페르소나 조각을 무작위로 이어 붙여 성향 블록(기본 7분)을 만들고, 성향 순서를 섞어 두 바퀴 돈다.
     GPS 속도는 조각 경계에서 이어지도록 앞 조각 끝 속도에 맞춰 평행 이동한다(0 아래로는 안 내려감).
  3. 부착 상태 흉내: 세션마다 엑셀 배율(GAIN_RANGE)·브레이크 예압 이동(PRELOAD_SHIFT_RANGE)을 하나 뽑아 전체에 적용.
     self 실측(세션 간 깊이 약 2배, 예압 0~0.2)에 맞춘 범위. 상대 지표가 이 변화에 버티는지 보는 용도.
  4. 변형: 조각마다 길이 ±20% 시간 늘이기/줄이기, 페달 값에 작은 잡음.

⚠️ 할 수 있는 것: 파이프라인·데모용 양, 부착 변화 견고성 시험. 못 하는 것: 날짜별 운전 흔들림·다른 사람의 습관 —
   원본에 없는 정보는 만들 수 없다. 합성으로 잰 정확도는 부풀려지므로 검증은 실데이터로만 한다.
출력 CSV 는 앱 CSV 와 같은 컬럼 + source=synthetic(dataset.Session.synthetic 으로 집계됨).

실행:  uv run python src/synth_sessions.py <원본_csv ...> [--n 6] [--seed 0] [--block-min 7] [--out 디렉터리]
       --out 기본값은 저장소 루트 "합성 데이터".
"""

from __future__ import annotations

import csv
import random
import sys
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from pathlib import Path

from dataset import ROWS_PER_SEC, SOURCE_SYNTHETIC, Row, load_sessions
from peob.features import ACCEL_ACTIVE_THRESHOLD

PERSONAS = ("beginner", "normal", "aggressive")
GAIN_RANGE = (0.75, 1.25)
PRELOAD_SHIFT_RANGE = (-0.05, 0.10)
WARP_RANGE = (0.8, 1.2)
NOISE_STD = 0.005
ROUNDS = 2

HEADER = ["date", "time", "brake", "accel", "module_err", "pedal_err", "accel_high", "accel_rate", "accel_score",
          "armed", "consecutive", "label", "persona", "trial_id", "trial_type", "trial_phase", "accel_median",
          "speed_kmh", "speed_acc_kmh", "source"]


@dataclass(frozen=True)
class SessionKnobs:
    """세션 하나에 고정 적용되는 부착 상태 흉내 값."""

    gain: float
    preload_shift: float


def cut_episodes(rows: list[Row]) -> list[list[Row]]:
    """엑셀을 뗀 순간마다 자른다. 조각 = 직전 뗀 순간 다음 ~ 이번 뗀 순간(공백·브레이크 + 밟기 한 번)."""
    episodes: list[list[Row]] = []
    start = 0
    for i in range(1, len(rows)):
        if rows[i - 1].level >= ACCEL_ACTIVE_THRESHOLD > rows[i].level:
            episodes.append(rows[start:i + 1])
            start = i + 1
    if start < len(rows):
        episodes.append(rows[start:])
    return [e for e in episodes if e]


def episodes_by_persona(paths: list[str]) -> dict[str, list[list[Row]]]:
    """라벨 세션의 성향별 조각. 재현 실험·오조작 라벨 행은 빼고 정상 주행만, 성향이 이어진 블록 안에서만 자른다."""
    out: dict[str, list[list[Row]]] = {p: [] for p in PERSONAS}
    for session in load_sessions(paths):
        if session.synthetic:
            continue   # 합성으로 합성을 만들지 않는다
        block: list[Row] = []
        for row in session.rows + [None]:
            if row is not None and block and row.persona == block[-1].persona and row.is_normal_driving:
                block.append(row)
                continue
            if block and block[0].persona in out:
                out[block[0].persona].extend(cut_episodes(block))
            block = [row] if row is not None and row.is_normal_driving else []
    return out


def warp(episode: list[Row], factor: float) -> list[Row]:
    """길이를 factor 배로 늘이거나 줄인다(가장 가까운 행 선택)."""
    n = max(1, round(len(episode) * factor))
    return [episode[min(int(i * len(episode) / n), len(episode) - 1)] for i in range(n)]


def _clip(x: float) -> float:
    return max(0.0, min(1.0, x))


def transform(row: Row, knobs: SessionKnobs, speed_shift: float, rng: random.Random) -> Row:
    """부착 흉내(배율·예압) + 잡음 + 속도 평행 이동을 적용한 새 행."""
    noise = lambda: rng.gauss(0.0, NOISE_STD)
    speed = None if row.speed is None else max(0.0, row.speed + speed_shift)
    return replace(
        row,
        accel=_clip(row.accel * knobs.gain + noise()),
        level=_clip(row.level * knobs.gain + noise()),
        brake=_clip(row.brake + knobs.preload_shift + noise()),
        rate=None if row.rate is None else row.rate * knobs.gain,
        speed=speed,
    )


def build_block(episodes: list[list[Row]], target_rows: int, knobs: SessionKnobs,
                last_speed: float | None, rng: random.Random) -> tuple[list[Row], float | None]:
    """같은 성향 조각을 무작위로 이어 target_rows 행 이상의 블록을 만든다. (블록, 마지막 속도)"""
    out: list[Row] = []
    while len(out) < target_rows:
        episode = warp(rng.choice(episodes), rng.uniform(*WARP_RANGE))
        first = next((r.speed for r in episode if r.speed is not None), None)
        shift = 0.0 if first is None or last_speed is None else last_speed - first
        for row in episode:
            new = transform(row, knobs, shift, rng)
            out.append(new)
            if new.speed is not None:
                last_speed = new.speed
    return out, last_speed


def synth_session(pool: dict[str, list[list[Row]]], block_rows: int, rng: random.Random) -> tuple[list[Row], SessionKnobs]:
    knobs = SessionKnobs(gain=rng.uniform(*GAIN_RANGE), preload_shift=rng.uniform(*PRELOAD_SHIFT_RANGE))
    rows: list[Row] = []
    last_speed: float | None = 0.0
    for _ in range(ROUNDS):
        order = [p for p in PERSONAS if pool[p]]
        rng.shuffle(order)
        for persona in order:
            block, last_speed = build_block(pool[persona], block_rows, knobs, last_speed, rng)
            rows.extend(block)
    return rows, knobs


def write_csv(path: Path, rows: list[Row], start: datetime) -> None:
    def fmt(x: float | None, digits: int = 4) -> str:
        return "none" if x is None else f"{x:.{digits}f}"

    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(HEADER)
        for i, r in enumerate(rows):
            t = start + timedelta(seconds=i / ROWS_PER_SEC)
            w.writerow([
                t.strftime("%Y-%m-%d"), t.strftime("%H:%M:%S.") + f"{t.microsecond // 1000:03d}",
                fmt(r.brake), fmt(r.accel), "none", "none", "none", fmt(r.rate), "none", "none", "none",
                r.label, r.persona, "none", "none", "none", fmt(r.level),
                fmt(r.speed, 1), "none" if r.speed is None else "1.0", SOURCE_SYNTHETIC,
            ])


def main() -> None:
    args = sys.argv[1:]
    opts = {"--n": "6", "--seed": "0", "--block-min": "7", "--out": str(Path(__file__).resolve().parents[2] / "합성 데이터")}
    for key in list(opts):
        if key in args:
            i = args.index(key)
            opts[key] = args[i + 1]
            args = args[:i] + args[i + 2:]
    if not args:
        print("원본 CSV(페르소나 라벨 세션)를 지정하세요.")
        return
    pool = episodes_by_persona(args)
    print("성향별 조각 수: " + ", ".join(f"{p} {len(v)}" for p, v in pool.items()))
    if sum(1 for v in pool.values() if v) < 2:
        print("성향 라벨 조각이 2종류 이상 필요합니다.")
        return
    rng = random.Random(int(opts["--seed"]))
    out_dir = Path(opts["--out"])
    out_dir.mkdir(parents=True, exist_ok=True)
    block_rows = int(float(opts["--block-min"]) * 60 * ROWS_PER_SEC)
    base = datetime(2026, 1, 1, 9, 0, 0)
    for k in range(int(opts["--n"])):
        rows, knobs = synth_session(pool, block_rows, rng)
        path = out_dir / f"synth_{opts['--seed']}_{k:02d}.csv"
        write_csv(path, rows, base + timedelta(days=k))
        print(f"  {path.name}: {len(rows) / ROWS_PER_SEC / 60:.1f}분, 엑셀 배율 {knobs.gain:.2f}, 예압 이동 {knobs.preload_shift:+.2f}")


if __name__ == "__main__":
    main()
