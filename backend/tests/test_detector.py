"""MisopDetector(오조작 경고 상태기계) 테스트.

윈도우 = 50샘플(0.25초, BleService.WINDOW_SIZE). 샘플 = [accel, brake].
판정 결과가 분명하게 갈리도록 윈도우 모양을 몇 가지로 고정해 두고 순서만 바꿔 넣는다.
"""

from __future__ import annotations

from peob.judge import MisopDetector

THRESHOLDS = {"accel_high": 0.8, "brake_low": 0.1, "high_ratio": 0.5, "accel_rate_high": 0.3}

IDLE = [[0.0, 0.0]] * 50                          # misop X, trigger X
HOLD = [[0.95, 0.0]] * 50                         # misop O, trigger X (이미 깊게 밟고 유지 중)
SNAP = [[0.0, 0.0]] * 10 + [[0.95, 0.0]] * 40     # misop O(0.8), trigger O — 확 밟고 유지
SNAP_LATE = [[0.0, 0.0]] * 35 + [[0.95, 0.0]] * 15  # misop X(0.3), trigger O — 윈도우 끝에서 확 밟음


def run(windows: list[list[list[float]]]) -> list[bool]:
    det = MisopDetector(THRESHOLDS)
    return [det.step(w)["fired"] for w in windows]


def test_window_shapes():
    # 아래 테스트들의 전제 — 윈도우 모양이 의도한 판정을 내는지 먼저 고정.
    det = MisopDetector(THRESHOLDS)
    assert (det.step(IDLE)["misop"], det.step(IDLE)["trigger"]) == (False, False)
    assert (det.step(HOLD)["misop"], det.step(HOLD)["trigger"]) == (True, False)
    assert (det.step(SNAP)["misop"], det.step(SNAP)["trigger"]) == (True, True)
    assert (det.step(SNAP_LATE)["misop"], det.step(SNAP_LATE)["trigger"]) == (False, True)


def test_slow_press_without_trigger_never_fires():
    # 서서히 깊게 밟아 레벨만 유지 = 정상 주행(고속도로 등) — 트리거가 없으면 경고 안 함.
    assert run([HOLD] * 10) == [False] * 10


def test_snap_and_hold_fires_once():
    assert run([SNAP, HOLD, HOLD, HOLD]) == [True, False, False, False]


def test_single_gap_is_tolerated():
    # 무장 후 misop=false 한 번은 봐준다 — 무장 유지, 재경고 없음.
    det = MisopDetector(THRESHOLDS)
    det.step(SNAP)
    det.step(IDLE)
    assert det.armed is True


def test_two_gaps_disarm_and_allow_next_warning():
    assert run([SNAP, IDLE, IDLE, SNAP]) == [True, False, False, True]


def test_rearm_after_disarm_resets_gap():
    # 2026-07-18 버그 회귀 테스트: 무장 해제 후 gap 이 남아 있으면, 다음 트리거 윈도우의
    # misop=false(SNAP_LATE) 하나에 즉시 재해제돼 이어지는 HOLD 에서 경고가 안 떴다.
    assert run([SNAP, IDLE, IDLE, SNAP_LATE, HOLD]) == [True, False, False, False, True]
