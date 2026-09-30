"""habit 누적 학습 테스트 — 게이트·p90·경고 주변 제외·가중 이동·dead band·저장 왕복."""

from __future__ import annotations

import json

from peob.habit import (
    BLEND_K,
    DEAD_BAND,
    GATE_EVENTS,
    HABIT_CAP,
    HABIT_OFFSET,
    POST_FIRE_WINDOWS,
    PRE_FIRE_WINDOWS,
    HabitLearner,
    load_learner,
)


def press(learner: HabitLearner, level: float, times: int = 1) -> bool:
    """밟았다 떼는 이벤트를 times 번 넣는다(밟은 윈도우 3개 + 뗀 윈도우 1개). 게이트 도달 여부 반환."""
    reached = False
    for _ in range(times):
        for lv in (level, level, level, 0.0):
            reached = learner.add_window(lv, fired=False) or reached
    return reached


def flush(learner: HabitLearner) -> None:
    """보류 큐(경고 직전 구간 대비)에 남은 윈도우를 확정시킨다."""
    for _ in range(PRE_FIRE_WINDOWS):
        learner.add_window(0.0, fired=False)


def test_gate_crossing_is_reported_once():
    learner = HabitLearner()
    assert not press(learner, 0.5, GATE_EVENTS - 1)
    assert not learner.ready
    assert press(learner, 0.5, 2)       # 보류 큐 때문에 한 이벤트 늦게 확정될 수 있어 2회
    assert learner.ready
    assert not press(learner, 0.5, 5)   # 이미 넘은 뒤엔 다시 알리지 않는다


def test_idle_windows_are_not_counted():
    learner = HabitLearner()
    for _ in range(1000):
        learner.add_window(0.05, fired=False)
    assert learner.events == 0
    assert learner.p90() is None


def test_p90_of_accumulated_levels():
    learner = HabitLearner()
    press(learner, 0.4, 90)
    press(learner, 0.8, 10)
    flush(learner)
    assert learner.p90() == 0.4
    press(learner, 0.8, 20)
    flush(learner)
    assert learner.p90() == 0.8


def test_windows_around_warning_are_excluded():
    learner = HabitLearner()
    press(learner, 0.3, 10)
    before = sum(learner.hist)
    # 급밟기 직전 PRE_FIRE_WINDOWS 개는 보류 중이었다가 경고와 함께 버려진다
    for _ in range(PRE_FIRE_WINDOWS):
        learner.add_window(0.95, fired=False)
    learner.add_window(0.95, fired=True)
    for _ in range(POST_FIRE_WINDOWS):
        learner.add_window(0.95, fired=False)
    flush(learner)
    assert sum(learner.hist) - before <= 1   # press() 마지막 뗀 윈도우 확정분 외엔 늘지 않음
    assert learner.hist[95] == 0


def test_update_applied_is_none_before_gate():
    learner = HabitLearner()
    press(learner, 0.5, 10)
    assert learner.update_applied(0.75) is None


def test_update_applied_blends_from_base_toward_personal():
    learner = HabitLearner()
    press(learner, 0.5, GATE_EVENTS + 5)
    flush(learner)
    personal = 0.5 + HABIT_OFFSET
    weight = learner.events / (learner.events + BLEND_K)
    assert learner.update_applied(0.75) == round(0.75 + weight * (personal - 0.75), 4)


def test_personal_value_is_capped():
    learner = HabitLearner()
    press(learner, 0.95, 5000)
    flush(learner)
    assert learner.update_applied(HABIT_CAP) == HABIT_CAP


def test_dead_band_keeps_previous_value():
    learner = HabitLearner()
    press(learner, 0.5, GATE_EVENTS + 5)
    flush(learner)
    first = learner.update_applied(0.75)
    press(learner, 0.5, 5)   # 가중치가 조금 늘어 값이 DEAD_BAND 미만으로 움직임
    flush(learner)
    assert learner.update_applied(0.75) == first
    press(learner, 0.5, 2000)   # 크게 움직이면 바뀐다
    flush(learner)
    assert abs(learner.update_applied(0.75) - first) >= DEAD_BAND


def test_apply_json_replaces_only_accel_high():
    learner = HabitLearner()
    base = {"accel_high": 0.75, "brake_low": 0.3, "high_ratio": 0.5, "accel_rate_high": 0.3}
    assert json.loads(learner.apply_json(json.dumps(base))) == base   # 게이트 전엔 그대로
    press(learner, 0.5, GATE_EVENTS + 5)
    flush(learner)
    applied = json.loads(learner.apply_json(json.dumps(base)))
    assert applied["accel_high"] != 0.75
    assert {k: v for k, v in applied.items() if k != "accel_high"} == {k: v for k, v in base.items() if k != "accel_high"}


def test_state_roundtrip_keeps_accumulation_and_applied():
    learner = HabitLearner()
    press(learner, 0.5, GATE_EVENTS + 5)
    flush(learner)
    learner.update_applied(0.75)
    restored = load_learner(learner.to_json())
    assert restored.hist == learner.hist
    assert restored.events == learner.events
    assert restored.applied == learner.applied
    state = json.loads(learner.to_json())
    assert state["ready"] is True and state["gate"] == GATE_EVENTS


def test_load_learner_from_empty_state():
    assert load_learner("").events == 0
