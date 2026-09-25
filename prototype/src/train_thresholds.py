"""라벨 데이터로 오조작 판정 임계값을 학습한다 — "임계값을 AI(로지스틱 회귀)가 정했다"의 근거.

판정 규칙(peob.judge: 급조작 트리거로 무장 → 깊게 밟은 상태가 유지되면 경고)의 구조는 그대로 두고,
규칙 안에서 사람이 정했던 두 숫자를 각각 로지스틱 회귀의 결정경계로 학습한다
(§진행상황_및_로드맵.md "학습 데이터 수집 재설계", 2026-09-25 사용자 합의).

  1. accel_rate_high (지금 0.30 고정)
     입력: 윈도우의 50ms 엑셀 변화율 최댓값(accel_rate) 하나
     정답: 오조작 재현 회차의 순간(회차별 최대 변화율) = 1 / 정상 주행·천천히 깊게 밟기 = 0
     → p=0.5 가 되는 변화율이 학습된 임계값

  2. accel_high (지금 "개인 p90 + 0.20" 고정 공식)
     입력: 윈도우 엑셀 레벨(accel_median, 옛 CSV 는 마지막 샘플로 근사) + 그 사람의 평소 p90
     정답: 오조작 유지 구간 = 1 / 정상 주행 중 페달을 밟고 있는 구간 = 0
     → 결정경계를 풀면 accel_high = slope × p90 + intercept — 개인별 임계값 공식 자체가 학습된다

"세게 정상 가속"(hard_normal)은 두 학습 모두에서 뺀다 — 이건 변화율도 레벨도 높아서 규칙의
시간 조건(유지 여부)이 걸러내는 대상이다. 대신 검증에선 오탐 여부를 센다.

검증: 세션(CSV 파일) 하나씩 빼고 나머지로 학습 → 뺀 세션에 같은 상태기계(peob.judge.MisopDetector)를
다시 돌려 "학습값"과 "지금 값"을 같은 조건에서 비교한다(회차별 감지율, 정상 실험 오탐, 주행 중 오경보).
CSV 에는 원시 200Hz 샘플이 없어서 윈도우 판정을 행 값(accel_median/accel_rate)으로 복원하는 근사다.

실행:  uv run python src/train_thresholds.py [csv_또는_디렉터리 ...] [--out 경로]
       인자 없으면 저장소 루트 "수집 데이터" 를 읽고 out/learned_thresholds.json 에 쓴다.
"""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from dataset import (
    ROWS_PER_SEC,
    TRIAL_HARD_NORMAL,
    TRIAL_SLOW_DEEP,
    Row,
    Session,
    default_data_dir,
    load_sessions,
)
from peob.calibration import ACCEL_HIGH_OFFSET, DEFAULT_ACCEL_RATE_HIGH, DEFAULT_BRAKE_LOW, DEFAULT_HIGH_RATIO
from peob.features import ACCEL_ACTIVE_THRESHOLD, extract_features
from peob.judge import MisopDetector
from train_logreg import standardize, train_logreg

# 오조작 이벤트 앞쪽 이만큼(행)은 밟기 시작 구간으로 보고 변화율 최댓값을 찾을 때 포함한다(0.5초)
ONSET_LEAD_ROWS = 2
# 이벤트 끝 뒤 이만큼(행)까지 발동하면 감지로 인정 — 판정 지속시간 때문에 경고가 늦게 뜰 수 있다(2초).
# analyze_field_data.MATCH_TOLERANCE_ROWS 와 같은 근거.
DETECT_TAIL_ROWS = 8

# 개인별 기울기(slope)를 학습하려면 오조작 예시가 최소 이만큼의 서로 다른 개인(p90)에서 나와야 한다.
# 한 사람 것뿐이면 모델이 "그 사람의 p90 = 오조작"이라는 그룹 차이를 배워 기울기가 엉뚱해진다
# (2026-09-26 실제로 slope −2.4 가 나옴 — 07-18 수동 라벨 세션만 오조작 예시였음).
MIN_PERSONAS_FOR_SLOPE = 2

LOGREG_EPOCHS = 800
LOGREG_LR = 0.5
# 입력이 1~2개뿐이라 강한 정규화가 필요 없다. train_logreg 기본값(0.01)은 결정경계를 눈에 띄게 기울였다
# (합성 데이터에서 정답 기울기 0.5 → 0.32, 0.001 이면 0.41 — 2026-09-26 확인)
LOGREG_L2 = 0.001


# ── 학습 결과 ─────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class LearnedThresholds:
    """학습된 판정 파라미터. accel_high 는 사람마다 p90 에서 계산한다."""

    level_slope: float
    level_intercept: float
    accel_rate_high: float
    # "slope": 기울기·절편 모두 학습 / "offset_only": 기울기 1 고정, 오프셋(절편)만 학습(데이터 부족 시 안전장치)
    level_mode: str = "slope"

    def accel_high(self, p90: float) -> float:
        return min(max(self.level_slope * p90 + self.level_intercept, 0.0), 1.0)


@dataclass(frozen=True)
class CurrentRule:
    """비교 기준 — 지금 앱이 쓰는 사람이 정한 값(p90 + 0.20, 변화율 0.30)"""

    accel_rate_high: float = DEFAULT_ACCEL_RATE_HIGH

    def accel_high(self, p90: float) -> float:
        return min(p90 + ACCEL_HIGH_OFFSET, 1.0)


# ── 데이터 준비 ───────────────────────────────────────────────────────────


def misop_events(rows: list[Row]) -> list[tuple[int, int]]:
    """label=1 이 연속된 구간 [start, end) 목록. 재현 실험이면 회차 하나, 옛 수동 라벨이면 토글 구간 하나."""
    events: list[tuple[int, int]] = []
    start: int | None = None
    for i, row in enumerate(rows):
        if row.label == 1 and start is None:
            start = i
        elif row.label == 0 and start is not None:
            events.append((start, i))
            start = None
    if start is not None:
        events.append((start, len(rows)))
    return events


def near_event_mask(n: int, events: list[tuple[int, int]]) -> list[bool]:
    """이벤트와 그 앞뒤 여유 구간에 속하는 행 — 정상 예시/오경보 집계에서 뺀다"""
    mask = [False] * n
    for start, end in events:
        for i in range(max(0, start - ONSET_LEAD_ROWS), min(n, end + DETECT_TAIL_ROWS)):
            mask[i] = True
    return mask


def personal_baselines(sessions: list[Session]) -> dict[str, float]:
    """페르소나별 평소 엑셀 p90 — 앱 캘리브레이션(calibrate_thresholds)과 같은 accel_active_p90.

    정상 주행 행만 쓴다. 페르소나 정보가 없는 옛 파일은 "none" 한 사람으로 묶인다.
    ⚠️ 검증 때도 뺀 세션을 포함한 전체로 계산한다 — 실제 앱에서도 그 사람의 캘리브레이션(평소 값)은
    판정 전에 이미 알고 있으므로 정보 누설로 보지 않는다.
    """
    by_persona: dict[str, list[list[float]]] = {}
    for session in sessions:
        for row in session.rows:
            if row.is_normal_driving:
                by_persona.setdefault(row.persona, []).append([row.level, row.brake])
    return {p: extract_features(samples)["accel_active_p90"] for p, samples in by_persona.items()}


def baseline_of(baselines: dict[str, float], persona: str) -> float:
    if persona in baselines:
        return baselines[persona]
    return sum(baselines.values()) / len(baselines) if baselines else 0.5


def level_dataset(sessions: list[Session], baselines: dict[str, float]) -> tuple[list[list[float]], list[int]]:
    """accel_high 학습용: [레벨, 개인 p90] → 오조작 유지(1) / 정상 주행 중 밟는 중(0)

    양쪽 다 "페달을 밟고 있는 행"(레벨 >= ACCEL_ACTIVE_THRESHOLD)만 쓴다. 옛 수동 토글 라벨은 토글을 켜 둔 채
    밟았다 뗐다를 반복해 label=1 행의 절반가량이 실제론 안 밟은 상태였다(2026-09-26 확인) — 이런 행을
    오조작 예시로 쓰면 "레벨이 낮아도 오조작"을 배우게 된다. 재현 실험에서도 "밟으세요" 직후 반응 지연 행을 거른다.
    """
    X: list[list[float]] = []
    y: list[int] = []
    for session in sessions:
        near = near_event_mask(len(session.rows), misop_events(session.rows))
        for i, row in enumerate(session.rows):
            if row.level < ACCEL_ACTIVE_THRESHOLD:
                continue
            p90 = baseline_of(baselines, row.persona)
            if row.label == 1:
                X.append([row.level, p90])
                y.append(1)
            elif row.is_normal_driving and not near[i]:
                X.append([row.level, p90])
                y.append(0)
    return X, y


def rate_dataset(sessions: list[Session]) -> tuple[list[list[float]], list[int]]:
    """accel_rate_high 학습용: [변화율] → 오조작 회차의 최대 변화율(1) / 정상 주행·천천히 깊게(0)"""
    X: list[list[float]] = []
    y: list[int] = []
    for session in sessions:
        if not session.has_rate:
            continue
        rows = session.rows
        events = misop_events(rows)
        for start, end in events:
            rates = [r.rate for r in rows[max(0, start - ONSET_LEAD_ROWS):end] if r.rate is not None]
            if rates:
                X.append([max(rates)])
                y.append(1)
        near = near_event_mask(len(rows), events)
        for i, row in enumerate(rows):
            if row.rate is None or near[i] or row.label != 0:
                continue
            if row.is_normal_driving or row.trial_type == TRIAL_SLOW_DEEP:
                X.append([row.rate])
                y.append(0)
    return X, y


# ── 학습 ──────────────────────────────────────────────────────────────────


def fit_boundary(X: list[list[float]], y: list[int]) -> tuple[list[float], float]:
    """로지스틱 회귀 후 결정경계(p=0.5)를 원래 단위로 되돌린 가중치·절편: w·x + b = 0"""
    Xs, means, stds = standardize(X)
    w_s, b_s = train_logreg(Xs, y, lr=LOGREG_LR, epochs=LOGREG_EPOCHS, l2=LOGREG_L2)
    # 표준화 공간 w_s·(x−m)/s + b_s = 0  →  원 단위 w = w_s/s, b = b_s − Σ w_s·m/s
    w = [w_s[j] / stds[j] for j in range(len(w_s))]
    b = b_s - sum(w_s[j] * means[j] / stds[j] for j in range(len(w_s)))
    return w, b


def learn(sessions: list[Session], baselines: dict[str, float]) -> LearnedThresholds:
    """두 경계를 학습해 판정 파라미터로 변환. 데이터가 모자라거나 방향이 거꾸로면 ValueError."""
    X_rate, y_rate = rate_dataset(sessions)
    X_level, y_level = level_dataset(sessions, baselines)
    for name, y in (("변화율", y_rate), ("레벨", y_level)):
        if sum(y) == 0 or sum(y) == len(y):
            raise ValueError(f"{name} 학습에 오조작/정상 예시가 둘 다 있어야 합니다 (1: {sum(y)}, 0: {len(y) - sum(y)})")

    (w_rate,), b_rate = fit_boundary(X_rate, y_rate)
    if w_rate <= 0:
        raise ValueError("변화율이 클수록 오조작이라는 방향이 학습되지 않았습니다 — 데이터 확인 필요")

    rate_high = -b_rate / w_rate
    positive_baselines = {round(x[1], 6) for x, t in zip(X_level, y_level) if t == 1}
    if len(positive_baselines) >= MIN_PERSONAS_FOR_SLOPE:
        (w_level, w_p90), b_level = fit_boundary(X_level, y_level)
        # w_level·level + w_p90·p90 + b = 0  →  level = −(w_p90/w_level)·p90 − b/w_level
        if w_level > 0 and -w_p90 / w_level > 0:
            return LearnedThresholds(-w_p90 / w_level, -b_level / w_level, rate_high, "slope")

    # 안전장치: 기울기 1 고정(평소 값이 높은 사람일수록 임계값도 같은 만큼 높게) — (레벨 − p90) 하나로 오프셋만 학습
    (w_rel,), b_rel = fit_boundary([[x[0] - x[1]] for x in X_level], y_level)
    if w_rel <= 0:
        raise ValueError("레벨이 높을수록 오조작이라는 방향이 학습되지 않았습니다 — 데이터 확인 필요")
    return LearnedThresholds(1.0, -b_rel / w_rel, rate_high, "offset_only")


# ── 검증 (상태기계 재생) ──────────────────────────────────────────────────


@dataclass(frozen=True)
class Evaluation:
    events: int = 0                 # 오조작 이벤트(재현 회차) 수
    detected: int = 0
    normal_trial_reps: int = 0      # 정상 조작 실험 회차 수(세게 정상 가속·천천히 깊게)
    normal_trial_false: int = 0     # 그중 경고가 뜬 회차
    driving_hours: float = 0.0
    driving_false: int = 0          # 정상 주행 중 경고 수

    def __add__(self, other: "Evaluation") -> "Evaluation":
        return Evaluation(*(a + b for a, b in zip(self.__dict__.values(), other.__dict__.values())))

    def summary(self) -> dict:
        return {
            "recall": round(self.detected / self.events, 3) if self.events else None,
            "detected": f"{self.detected}/{self.events}",
            "normal_trial_false": f"{self.normal_trial_false}/{self.normal_trial_reps}",
            "driving_false_per_hour": round(self.driving_false / self.driving_hours, 2) if self.driving_hours else None,
            "driving_hours": round(self.driving_hours, 3),
        }


def replay(session: Session, rule: LearnedThresholds | CurrentRule, baselines: dict[str, float]) -> list[bool]:
    """세션을 판정 상태기계에 다시 흘려 행마다 경고 발동 여부를 돌려준다(변화율 없는 행은 트리거 없음)."""
    detector = MisopDetector({})
    fired: list[bool] = []
    for row in session.rows:
        accel_high = rule.accel_high(baseline_of(baselines, row.persona))
        misop = row.level >= accel_high and row.brake <= DEFAULT_BRAKE_LOW
        trigger = row.rate is not None and row.rate >= rule.accel_rate_high
        fired.append(detector.step_result({"misop": misop, "trigger": trigger})["fired"])
    return fired


def evaluate(session: Session, rule: LearnedThresholds | CurrentRule, baselines: dict[str, float]) -> Evaluation:
    rows = session.rows
    fired = replay(session, rule, baselines)
    events = misop_events(rows)
    near = near_event_mask(len(rows), events)

    detected = sum(1 for start, end in events if any(fired[max(0, start - ONSET_LEAD_ROWS):end + DETECT_TAIL_ROWS]))

    normal_trials: dict[str, bool] = {}
    for i, row in enumerate(rows):
        if row.trial_type in (TRIAL_HARD_NORMAL, TRIAL_SLOW_DEEP) and row.trial_id is not None:
            normal_trials[row.trial_id] = normal_trials.get(row.trial_id, False) or fired[i]

    driving_rows = [i for i, row in enumerate(rows) if row.is_normal_driving and row.trial_type is None and not near[i]]
    return Evaluation(
        events=len(events),
        detected=detected,
        normal_trial_reps=len(normal_trials),
        normal_trial_false=sum(normal_trials.values()),
        driving_hours=len(driving_rows) / ROWS_PER_SEC / 3600,
        driving_false=sum(1 for i in driving_rows if fired[i]),
    )


def should_adopt(learned: Evaluation, current: Evaluation) -> bool:
    """학습값을 앱에 채택할지 — 감지율은 같거나 높고, 오탐·오경보는 같거나 적어야 한다(안전 앱이라 둘 다 필수).
    검증할 오조작 이벤트가 없으면 판단 근거가 없으므로 채택하지 않는다."""
    if learned.events == 0:
        return False
    return (
        learned.detected >= current.detected
        and learned.normal_trial_false <= current.normal_trial_false
        and learned.driving_false <= current.driving_false
    )


def cross_validate(sessions: list[Session], baselines: dict[str, float]) -> list[dict]:
    """세션 하나씩 빼고 학습 → 뺀 세션에서 학습값 vs 지금 값. 변화율이 없는 세션은 트리거를 복원할 수 없어 검증 제외."""
    results = []
    for held in sessions:
        if not held.has_rate:
            continue
        train = [s for s in sessions if s is not held]
        try:
            learned = learn(train, baselines)
        except ValueError as e:
            results.append({"session": held.name, "error": str(e)})
            continue
        results.append({
            "session": held.name,
            "learned": evaluate(held, learned, baselines),
            "current": evaluate(held, CurrentRule(), baselines),
        })
    return results


# ── 실행 ─────────────────────────────────────────────────────────────────


def main() -> None:
    args = sys.argv[1:]
    out_path = Path(__file__).resolve().parents[1] / "out" / "learned_thresholds.json"
    if "--out" in args:
        i = args.index("--out")
        out_path = Path(args[i + 1])
        args = args[:i] + args[i + 2:]
    sessions = load_sessions(args or [str(default_data_dir())])
    if not sessions:
        print("CSV를 찾지 못했습니다.")
        return

    baselines = personal_baselines(sessions)
    print("== 개인(페르소나)별 평소 엑셀 p90 ==")
    for persona, p90 in sorted(baselines.items()):
        print(f"  {persona:<11} {p90:.3f}")
    if len(baselines) < 2:
        print("  ⚠️ 평소 값이 한 종류뿐이라 개인별 기울기(slope)는 의미가 약합니다 — 페르소나 데이터를 더 모으세요.")

    print("\n== 세션별 교차검증 (학습값 vs 지금 값) ==")
    total_learned, total_current = Evaluation(), Evaluation()
    folds = cross_validate(sessions, baselines)
    for fold in folds:
        if "error" in fold:
            print(f"  {fold['session']}: 학습 실패 — {fold['error']}")
            continue
        total_learned += fold["learned"]
        total_current += fold["current"]
        print(f"  {fold['session']}")
        print(f"    학습값: {fold['learned'].summary()}")
        print(f"    지금값: {fold['current'].summary()}")
    print(f"\n  합계 학습값: {total_learned.summary()}")
    print(f"  합계 지금값: {total_current.summary()}")
    adopt = should_adopt(total_learned, total_current)
    print(f"  → 앱 채택: {'예' if adopt else '아니오 (지금 값보다 확실히 낫지 않음 — 앱은 지금 값 유지)'}")

    learned = learn(sessions, baselines)
    X_level, y_level = level_dataset(sessions, baselines)
    X_rate, y_rate = rate_dataset(sessions)
    print("\n== 전체 데이터로 학습한 최종 파라미터 ==")
    print(f"  accel_high = {learned.level_slope:.3f} × p90 + {learned.level_intercept:.3f}   (지금: 1.000 × p90 + {ACCEL_HIGH_OFFSET:.3f})")
    if learned.level_mode == "offset_only":
        print(f"  ⚠️ 오조작 예시가 개인 {MIN_PERSONAS_FOR_SLOPE}명 이상에서 나오지 않아 기울기는 1로 고정하고 오프셋만 학습했습니다.")
        print("     페르소나별로 재현 실험(패닉 급밟기)을 하면 개인별 기울기까지 학습됩니다.")
    print(f"  accel_rate_high = {learned.accel_rate_high:.3f}   (지금: {DEFAULT_ACCEL_RATE_HIGH:.3f})")
    for persona, p90 in sorted(baselines.items()):
        print(f"    {persona:<11} accel_high 학습 {learned.accel_high(p90):.3f} / 지금 {CurrentRule().accel_high(p90):.3f}")

    result = {
        "version": 1,
        "trained_at": datetime.now().isoformat(timespec="seconds"),
        "model": "logistic_regression",
        "accel_high": {"slope": round(learned.level_slope, 4), "intercept": round(learned.level_intercept, 4),
                       "mode": learned.level_mode,
                       "formula": "accel_high = slope * accel_active_p90 + intercept"},
        "accel_rate_high": round(learned.accel_rate_high, 4),
        "fixed": {"brake_low": DEFAULT_BRAKE_LOW, "high_ratio": DEFAULT_HIGH_RATIO},
        "data": {
            "sessions": len(sessions),
            "rows": sum(len(s.rows) for s in sessions),
            "level_examples": {"misop": sum(y_level), "normal": len(y_level) - sum(y_level)},
            "rate_examples": {"misop": sum(y_rate), "normal": len(y_rate) - sum(y_rate)},
            "persona_p90": {p: round(v, 4) for p, v in sorted(baselines.items())},
        },
        "validation": {"learned": total_learned.summary(), "current": total_current.summary()},
        # 앱(4단계)은 이 값이 true 일 때만 학습값을 쓴다
        "adopt": adopt,
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n저장: {out_path}")


if __name__ == "__main__":
    main()
