"""운전 성향 테스트 — 이벤트 추출(4Hz 경계), 상대 지표, 급가감속, 저장 왕복, 진단 게이트·최근접 중심."""

from __future__ import annotations

import json

import pytest

from peob.style import (
    DEMO_GATE_EVENTS,
    STOP_HOLD_SEC,
    WINDOWS_PER_SEC,
    Reference,
    StyleMetrics,
    StyleTracker,
    Window,
    WindowStream,
    diagnose,
    load_tracker,
    metrics_from_windows,
    parse_reference,
)

HOLD = int(STOP_HOLD_SEC * WINDOWS_PER_SEC)


def _w(levels: list[float], brakes: list[float] | None = None, speeds: list[float | None] | None = None) -> list[Window]:
    brakes = brakes or [0.0] * len(levels)
    speeds = speeds or [None] * len(levels)
    return [Window(a, b, s) for a, b, s in zip(levels, brakes, speeds)]


def _events(windows: list[Window]):
    stream = WindowStream(brake_on=0.1)
    return [e for e in (stream.step(w)[0] for w in windows) if e is not None]


# ── 이벤트 추출 ──


def test_start_after_long_brake_is_marked_even_if_brake_released_first():
    # 브레이크 2초 → 뗌 → 엑셀. 엑셀 첫 윈도우엔 브레이크가 이미 0 — 그래도 정차 후 출발
    events = _events(_w([0.0] * (HOLD + 1) + [0.3, 0.6, 0.6, 0.0], [0.5] * HOLD + [0.0] * 5))
    assert len(events) == 1
    assert events[0].after_stop
    assert events[0].peak == 0.6
    assert events[0].rise == pytest.approx(0.3)
    assert events[0].duration_sec == 0.75


def test_short_brake_is_not_a_stop():
    events = _events(_w([0.0] * 3 + [0.5, 0.5, 0.0], [0.5, 0.5, 0.0, 0.0, 0.0, 0.0]))
    assert not events[0].after_stop


def test_pedal_switch_time_from_accel_release_to_brake():
    stream = WindowStream(brake_on=0.1)
    switches = [s for s in (stream.step(w)[1] for w in _w([0.5, 0.5, 0.0, 0.0, 0.0], [0, 0, 0, 0, 0.5])) if s is not None]
    assert switches == [0.5]


def test_harsh_accel_counted_once_per_episode():
    # 10 → 20 → 30 km/h (1초마다 +10, 4윈도우씩 반복) — 연속된 급가속은 1회
    speeds = [10.0] * 4 + [20.0] * 4 + [30.0] * 4 + [30.0] * 8
    stream = WindowStream(brake_on=0.1)
    counts = [stream.step(w)[2] for w in _w([0.0] * len(speeds), speeds=speeds)]
    assert sum(counts) == 1


def test_uneven_gps_fix_timing_does_not_double_count():
    # GPS 갱신이 1.25초 → 0.75초로 흔들리면, "4윈도우 전과 비교"는 두 번의 갱신을 1초 변화로 합쳐
    # 20 → 30.75(+10.75) 를 급가속으로 셌다(2026-10-25 실주행 18회 vs 실제 11회). 실제 변화율은 5, 6 km/h/s.
    speeds = [20.0] * 5 + [26.25] * 3 + [30.75] * 8
    stream = WindowStream(brake_on=0.1)
    counts = [stream.step(w)[2] for w in _w([0.0] * len(speeds), speeds=speeds)]
    assert sum(counts) == 0


# ── 지표 ──


def test_relative_metrics_use_own_p90():
    # 같은 모양을 깊이만 절반으로 밟아도(센서 부착 차이) 상대 지표는 같아야 한다
    shape = ([0.0] * 4 + [0.4, 0.8, 0.8, 0.0]) * 10
    deep = metrics_from_windows(_w(shape), brake_on=0.1)
    shallow = metrics_from_windows(_w([x / 2 for x in shape]), brake_on=0.1)
    assert deep.rel_rise == pytest.approx(shallow.rel_rise)
    assert deep.full_press_ratio == shallow.full_press_ratio == 1.0
    assert deep.hold_sec == 0.75
    assert deep.harsh_accel_per_h is None   # 속도 기록 없음
    assert deep.switch_sec is None          # 브레이크 없음


def test_tracker_state_roundtrip():
    tracker = StyleTracker()
    for w in _w(([0.0] * 4 + [0.5, 0.5, 0.0]) * 5, speeds=[30.0] * 35):
        tracker.add_window(w.level, w.brake, 0.1, w.speed_kmh)
    restored = load_tracker(tracker.to_json())
    assert restored.metrics() == tracker.metrics()
    assert len(restored.events) == 5


# ── 진단 ──


REF = Reference(
    centroids={"beginner": {"rel_rise": 0.3, "hold_sec": 3.5}, "aggressive": {"rel_rise": 0.7, "hold_sec": 4.5}},
    scale={"rel_rise": 0.1, "hold_sec": 0.5},
    trained_at="2026-10-25", chunks={"beginner": 2, "aggressive": 3},
)


def _metrics(**kw) -> StyleMetrics:
    base = dict(rel_rise=0.3, full_press_ratio=0.3, hold_sec=3.5, start_rel_rise=None, switch_sec=None,
                harsh_accel_per_h=None, harsh_decel_per_h=None)
    return StyleMetrics(**{**base, **kw})


def test_nearest_centroid_picks_closest_style():
    assert diagnose(_metrics(rel_rise=0.65, hold_sec=4.4), REF).label == "aggressive"
    assert diagnose(_metrics(rel_rise=0.32, hold_sec=3.6), REF).label == "beginner"


def test_reference_parse_roundtrip_and_broken_file():
    text = json.dumps({"centroids": REF.centroids, "scale": REF.scale, "trained_at": REF.trained_at, "chunks": REF.chunks})
    assert parse_reference(text) == REF
    assert parse_reference("{broken") is None


def test_report_hides_diagnosis_before_gate():
    tracker = StyleTracker()
    for w in _w([0.0, 0.5, 0.0] * (DEMO_GATE_EVENTS - 1)):
        tracker.add_window(w.level, w.brake, 0.1, w.speed_kmh)
    report = json.loads(tracker.report_json(demo=True))
    assert report["events"] == DEMO_GATE_EVENTS - 1
    assert not report["ready"]
    assert report["label"] is None
