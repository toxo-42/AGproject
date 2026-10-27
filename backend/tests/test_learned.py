"""학습 결과 반영 테스트 — 채택 게이트, 깨진 파일 fallback, 변화율 반영, 습관 학습 공식 교체."""

from __future__ import annotations

import json

import pytest

from peob.calibration import DEFAULT_ACCEL_RATE_HIGH, default_thresholds
from peob.habit import BLEND_K, GATE_EVENTS, HABIT_CAP, HabitLearner, personal_accel_high
from peob.learned import LearnedParams, apply_learned, load_learned, parse_learned


def _doc(adopt: bool = True, **overrides) -> str:
    doc = {
        "trained_at": "2026-10-25T10:00:00",
        "accel_high": {"slope": 0.8, "intercept": 0.2},
        "accel_rate_high": 0.25,
        "data": {"sessions": 12, "synthetic_sessions": 10},
        "adopt": adopt,
    }
    doc.update(overrides)
    return json.dumps(doc)


LEARNED = LearnedParams(slope=0.8, intercept=0.2, accel_rate_high=0.25,
                        trained_at="2026-10-25T10:00:00", sessions=12, synthetic_sessions=10)


# ── 파일 파싱(채택 게이트) ──


def test_adopted_result_is_parsed():
    assert parse_learned(_doc()) == LEARNED


def test_not_adopted_result_is_ignored():
    assert parse_learned(_doc(adopt=False)) is None


def test_broken_result_falls_back_to_none():
    assert parse_learned("{not json") is None
    assert parse_learned(_doc(accel_high={"slope": 0.8})) is None   # intercept 없음
    assert parse_learned("[]") is None


def test_bundled_file_loads_without_error():
    # 번들된 파일이 채택 안 됨이면 None, 채택이면 값 — 어느 쪽이든 예외 없이 읽혀야 앱이 안 죽는다
    learned = load_learned()
    assert learned is None or isinstance(learned, LearnedParams)


# ── 판정 임계값 반영 ──


def test_apply_learned_replaces_only_rate():
    base = default_thresholds()
    applied = apply_learned(base, LEARNED)
    assert applied["accel_rate_high"] == 0.25
    assert {k: v for k, v in applied.items() if k != "accel_rate_high"} == \
        {k: v for k, v in base.items() if k != "accel_rate_high"}


def test_apply_learned_without_result_keeps_current_rule():
    assert apply_learned(default_thresholds(), None)["accel_rate_high"] == DEFAULT_ACCEL_RATE_HIGH


# ── 습관 학습 공식 ──


def test_personal_formula_uses_learned_slope_and_cap():
    assert personal_accel_high(0.5, LEARNED.slope, LEARNED.intercept) == pytest.approx(0.6)
    assert personal_accel_high(0.95, LEARNED.slope, LEARNED.intercept) == HABIT_CAP


def test_habit_learner_moves_toward_learned_formula():
    learner = HabitLearner(learned=LEARNED)
    for _ in range(GATE_EVENTS + 5):
        for lv in (0.5, 0.5, 0.5, 0.0):
            learner.add_window(lv, fired=False)
    for _ in range(3):
        learner.add_window(0.0, fired=False)
    personal = personal_accel_high(0.5, LEARNED.slope, LEARNED.intercept)
    weight = learner.events / (learner.events + BLEND_K)
    assert learner.update_applied(0.75) == round(0.75 + weight * (personal - 0.75), 4)
