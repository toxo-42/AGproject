"""calibration 프리셋 임계값 테스트 — 개발자 수집 페르소나가 캘리브레이션 없이 쓰는 값."""

from __future__ import annotations

import json

from peob.calibration import calibrate_thresholds, preset_thresholds, preset_thresholds_json
from peob.judge import judge_with_thresholds


def test_preset_uses_given_accel_high_and_calibration_defaults():
    preset = preset_thresholds(0.7)
    calibrated = calibrate_thresholds([[0.5, 0.0]] * 200)
    assert preset["accel_high"] == 0.7
    # accel_high 외 값은 캘리브레이션 결과와 같은 기본값이어야 한다(두 경로가 어긋나지 않게)
    for key in ("brake_low", "high_ratio", "accel_rate_high"):
        assert preset[key] == calibrated[key]


def test_preset_accel_high_is_capped_at_one():
    assert preset_thresholds(1.3)["accel_high"] == 1.0


def test_preset_json_roundtrip():
    assert json.loads(preset_thresholds_json(0.8)) == preset_thresholds(0.8)


def test_brake_low_stays_default_without_brake_offset():
    assert calibrate_thresholds([[0.5, 0.0]] * 200)["brake_low"] == 0.10


def test_brake_low_follows_mounting_offset():
    # 실차 부착 시 발을 뗀 상태에서도 브레이크가 ~0.2 로 떠 있던 케이스(2026-09-30)
    th = calibrate_thresholds([[0.5, 0.2]] * 200)
    assert th["brake_low"] == 0.3


def test_offset_brake_does_not_block_misop_after_calibration():
    # offset 0.2 가 걸린 채로 엑셀을 깊게 밟으면 misop 이 떠야 한다(고정 0.10 이면 안 떴음)
    th = calibrate_thresholds([[0.5, 0.2]] * 200)
    window = [[1.0, 0.2]] * 50
    assert judge_with_thresholds(window, th, "t")["misop"] is True

