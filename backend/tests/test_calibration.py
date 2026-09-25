"""calibration 프리셋 임계값 테스트 — 개발자 수집 페르소나가 캘리브레이션 없이 쓰는 값."""

from __future__ import annotations

import json

from peob.calibration import calibrate_thresholds, preset_thresholds, preset_thresholds_json


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
