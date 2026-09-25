"""임계값 학습 파이프라인 테스트 — CSV 로딩, 이벤트 구분, 경계 학습, 채택 판정."""

from __future__ import annotations

from dataset import Row, Session, parse_rows
from train_thresholds import (
    CurrentRule,
    Evaluation,
    learn,
    misop_events,
    replay,
    should_adopt,
)


def _row(level: float, label: int = 0, persona: str = "normal", rate: float | None = 0.0) -> Row:
    return Row(accel=level, level=level, brake=0.0, rate=rate, label=label, persona=persona,
               trial_id=None, trial_type=None, trial_phase=None)


# ── CSV 로딩 ──


def test_old_schema_maps_style_to_persona_and_uses_last_sample_as_level():
    rows = parse_rows([{"accel": "0.8", "brake": "0.0", "style": "strong", "label": "1"}])
    assert rows[0].persona == "aggressive"
    assert rows[0].level == 0.8
    assert rows[0].rate is None
    assert rows[0].label == 1


def test_new_schema_reads_persona_median_and_trial_columns():
    rows = parse_rows([{
        "accel": "0.9", "brake": "0.0", "accel_rate": "0.4", "label": "1", "persona": "beginner",
        "trial_id": "101010-03", "trial_type": "panic_slam", "trial_phase": "action", "accel_median": "0.7",
    }])
    row = rows[0]
    assert (row.persona, row.level, row.rate) == ("beginner", 0.7, 0.4)
    assert (row.trial_id, row.trial_type, row.trial_phase) == ("101010-03", "panic_slam", "action")


def test_rows_without_pedal_values_are_skipped():
    assert parse_rows([{"accel": "none", "brake": "0.0"}]) == []


def test_free_act_trial_counts_as_normal_driving_but_other_trials_do_not():
    free = Row(0.5, 0.5, 0.0, 0.1, 0, "normal", "1-01", "free_act", "action")
    hard = Row(0.9, 0.9, 0.0, 0.5, 0, "normal", "1-01", "hard_normal", "action")
    assert free.is_normal_driving
    assert not hard.is_normal_driving


def test_misop_events_are_contiguous_label_runs():
    rows = [_row(0.1, label=v) for v in (0, 1, 1, 0, 0, 1, 1, 1)]
    assert misop_events(rows) == [(1, 3), (5, 8)]


# ── 학습 ──

PERSONA_P90 = {"beginner": 0.4, "normal": 0.6, "aggressive": 0.8}


def true_accel_high(p90: float) -> float:
    """합성 데이터의 정답 경계 — 학습이 이걸 되찾아야 한다"""
    return 0.5 * p90 + 0.45


def synthetic_session(persona: str, with_misop: bool = True) -> Session:
    """정상 주행(경계 아래·완만) 사이사이에 오조작(경계 위·급조작 유지)이 섞인 세션"""
    boundary = true_accel_high(PERSONA_P90[persona])
    rows: list[Row] = []
    for event in range(12):
        for k in range(20):
            level = 0.1 + (boundary - 0.05 - 0.1) * ((event * 20 + k) % 37) / 36
            rows.append(_row(level, persona=persona, rate=0.02 + 0.1 * (k % 5) / 4))
        if with_misop:
            for k in range(8):
                level = boundary + 0.05 + (1.0 - boundary - 0.05) * k / 7
                rows.append(_row(level, label=1, persona=persona, rate=0.6 if k == 0 else 0.05))
    return Session(name=persona, rows=rows, has_label_column=True)


def test_learns_personal_slope_when_misop_comes_from_several_people():
    sessions = [synthetic_session(p) for p in PERSONA_P90]
    learned = learn(sessions, PERSONA_P90)
    assert learned.level_mode == "slope"
    # 정규화(L2)된 로지스틱 회귀는 빈 구간(정답 ±0.05) 정중앙이 아니라 근처에 경계를 둔다 —
    # 여기선 "평소 값이 높을수록 임계값도 높아지는 개인별 공식"을 대략 되찾는지만 본다
    assert 0.2 < learned.level_slope < 0.8
    for p90 in PERSONA_P90.values():
        assert abs(learned.accel_high(p90) - true_accel_high(p90)) < 0.07
    assert 0.12 < learned.accel_rate_high < 0.6


def test_falls_back_to_offset_only_when_misop_comes_from_one_person():
    sessions = [synthetic_session("normal"), synthetic_session("beginner", with_misop=False)]
    learned = learn(sessions, PERSONA_P90)
    assert learned.level_mode == "offset_only"
    assert learned.level_slope == 1.0


# ── 검증·채택 ──


def test_replay_fires_once_on_trigger_then_hold():
    rows = [_row(0.1)] * 4 + [_row(0.95, rate=0.5)] + [_row(0.95)] * 6 + [_row(0.1)] * 4
    fired = replay(Session("s", rows, True), CurrentRule(), {"normal": 0.6})
    assert sum(fired) == 1


def test_replay_ignores_slow_deep_press_without_trigger():
    rows = [_row(0.1 + 0.1 * i, rate=0.05) for i in range(10)] + [_row(1.0, rate=0.0)] * 8
    fired = replay(Session("s", rows, True), CurrentRule(), {"normal": 0.6})
    assert sum(fired) == 0


def test_adopt_only_when_better_or_equal_on_every_metric():
    current = Evaluation(events=10, detected=7, normal_trial_reps=5, normal_trial_false=1, driving_hours=1.0, driving_false=2)
    better = Evaluation(events=10, detected=9, normal_trial_reps=5, normal_trial_false=1, driving_hours=1.0, driving_false=2)
    noisier = Evaluation(events=10, detected=10, normal_trial_reps=5, normal_trial_false=1, driving_hours=1.0, driving_false=5)
    assert should_adopt(better, current)
    assert not should_adopt(noisier, current)
    assert not should_adopt(Evaluation(), Evaluation())
