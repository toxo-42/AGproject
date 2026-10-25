"""운전 성향 지표 테스트 — 이벤트 구분, 정차 후 출발 판정(4Hz 경계), 페달 전환 시간."""

from __future__ import annotations

from analyze_driving_style import STOP_HOLD_SEC, press_events, pedal_switch_times
from dataset import ROWS_PER_SEC, Row


def _rows(levels: list[float], brakes: list[float]) -> list[Row]:
    return [Row(accel=a, level=a, brake=b, rate=None, label=0, persona="self",
                trial_id=None, trial_type=None, trial_phase=None) for a, b in zip(levels, brakes)]


HOLD = int(STOP_HOLD_SEC * ROWS_PER_SEC)


def test_start_after_long_brake_is_marked_even_if_brake_released_first():
    # 브레이크 2초 → 뗌(0.25초) → 엑셀. 엑셀 첫 행엔 브레이크가 이미 0 — 그래도 정차 후 출발이어야 한다
    rows = _rows([0.0] * (HOLD + 1) + [0.3, 0.6, 0.6, 0.0], [0.5] * HOLD + [0.0] * 5)
    events = press_events(rows, brake_on=0.1)
    assert len(events) == 1
    assert events[0].after_stop
    assert events[0].peak == 0.6
    assert abs(events[0].rise - 0.3) < 1e-9


def test_short_brake_is_not_a_stop():
    rows = _rows([0.0] * 3 + [0.5, 0.5, 0.0], [0.5, 0.5, 0.0, 0.0, 0.0, 0.0])
    assert not press_events(rows, brake_on=0.1)[0].after_stop


def test_pedal_switch_time_from_accel_release_to_brake():
    # 엑셀 뗀 행(index 2) → 브레이크(index 4) = 0.5초
    rows = _rows([0.5, 0.5, 0.0, 0.0, 0.0], [0.0, 0.0, 0.0, 0.0, 0.5])
    assert pedal_switch_times(rows, brake_on=0.1) == [0.5]
