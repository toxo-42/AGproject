"""운전 성향 진단 — 판정 윈도우(0.25초) 흐름에서 페달·차량 지표를 누적하고, 성향 기준점과 비교한다 (Phase F-3).

안전 판정(judge/habit)과는 완전히 분리된 기능이다 — 진단 결과는 경고 로직에 들어가지 않는다
(3단계 프로필 분류가 경계에서 불안정해 폐기된 전례, §진행상황_및_로드맵.md 07-13).

지표는 "A안: 상대·시간 지표"(2026-10-25 사용자 결정, analyze_driving_style.py 분석 근거):
  센서 부착 상태에 따라 밟은 깊이의 절대값이 2배까지 달라져서(같은 self 세션 간 0.13~0.31),
  깊이·속도는 "내 평소 깊이(p90)" 대비 비율로만 쓴다. 차량 가감속(GPS)은 부착과 무관한 절대 지표.

흐름(파이프라인 — 임계값 학습과 같은 모양):
  앱:  윈도우마다 StyleTracker.add_window → 지표(StyleMetrics) → 기준점(style_reference.json)과 최근접 중심 비교
  PC:  페르소나 라벨 CSV → prototype/src/train_style_reference.py → style_reference.json (앱 번들)
이벤트 추출은 한 윈도우씩 받는 WindowStream 하나뿐이다 — PC 일괄 계산도 이걸 반복 호출해 앱과 같은 값을 낸다.
"""

from __future__ import annotations

import json
import math
import pkgutil
from collections import deque
from dataclasses import asdict, dataclass

from .features import ACCEL_ACTIVE_THRESHOLD

WINDOWS_PER_SEC = 4          # 판정 윈도우 0.25초(BleService.WINDOW_SIZE @ 200Hz)
# 정차 후 출발: 브레이크를 STOP_HOLD_SEC 이상 밟다가 뗀 뒤 STOP_TO_START_SEC 안에 엑셀.
# 4Hz 에선 엑셀 첫 윈도우엔 이미 브레이크를 뗀 상태라 "시작 순간 밟고 있었나"로 보면 하나도 안 잡힌다.
STOP_HOLD_SEC = 2.0
STOP_TO_START_SEC = 1.0
SWITCH_MAX_SEC = 3.0         # 엑셀 뗀 뒤 이 안에 브레이크를 밟아야 "페달 전환"
FULL_PRESS_RATIO = 0.9       # 평소 깊이(p90)의 이 비율 이상 도달한 이벤트 = "끝까지 밟기"
# 급가속·급감속 — ⚠️ 임시값. 교통안전공단 위험운전행동(급가속·급감속 등)의 공식 수치는 확인하지 못했다(2026-10-25 검색).
HARSH_MIN_SPEED_KMH = 6.0
HARSH_ACCEL_KMH_PER_S = 8.0
HARSH_DECEL_KMH_PER_S = 8.0
MAX_FIX_GAP_SEC = 3.0        # GPS 값이 이보다 오래 안 바뀌었다가 바뀌면(터널 등) 변화율을 재지 않는다

MIN_SAMPLES = 3              # 정차 후 출발·전환 지표는 이만큼 모여야 값으로 낸다
MIN_MOVING_SEC = 60.0        # 급가감속 지표는 주행 속도 기록이 이만큼 있어야 낸다
MAX_EVENTS = 2000            # 최근 이벤트만 유지(습관 변화 반영 + 저장 크기 제한)
HIST_BINS = 100

GATE_EVENTS = 300            # 진단 표시 최소 이벤트(약 40분 주행). 직설적 표기라 오판 비용이 커서 넉넉히
DEMO_GATE_EVENTS = 20        # 개발자 설정 "진단 데모 모드" — 시연용(약 3분)

REFERENCE_FILE = "style_reference.json"


@dataclass(frozen=True)
class Window:
    level: float               # 윈도우 엑셀 중앙값(CSV accel_median)
    brake: float
    speed_kmh: float | None    # GPS 차량 속도, 모르면 None


@dataclass(frozen=True)
class PressEvent:
    peak: float
    rise: float                # 0.25초당 최대 증가량(밟는 속도, 절대값)
    duration_sec: float
    after_stop: bool


@dataclass(frozen=True)
class StyleMetrics:
    """진단 입력. None = 그 지표를 낼 데이터가 없음(브레이크 미부착, GPS 없음 등)."""

    rel_rise: float                 # 밟는 속도 중앙값 ÷ 평소 깊이
    full_press_ratio: float         # 끝까지 밟은 이벤트 비율
    hold_sec: float                 # 이벤트 평균 유지 시간
    start_rel_rise: float | None    # 정차 후 출발 밟는 속도 중앙값 ÷ 평소 깊이
    switch_sec: float | None        # 엑셀 → 브레이크 전환 시간 중앙값
    harsh_accel_per_h: float | None
    harsh_decel_per_h: float | None


def _median(values: list[float]) -> float:
    s = sorted(values)
    return s[len(s) // 2]


class WindowStream:
    """윈도우를 하나씩 받아 엑셀 이벤트·페달 전환·급가감속을 뽑는다. 상태는 주행 하나 안에서만 유효."""

    def __init__(self, brake_on: float) -> None:
        self.brake_on = brake_on
        self._prev_level = 0.0
        self._start_prev_level = 0.0
        self._event_levels: list[float] | None = None   # 진행 중인 엑셀 이벤트의 레벨들
        self._after_stop = False
        self._brake_run = 0
        self._windows_since_long_stop = 10**9
        self._windows_since_release: int | None = None
        self._last_speed: float | None = None   # 마지막으로 바뀐 GPS 속도 값
        self._windows_since_speed_change = 0
        self._in_harsh_accel = False
        self._in_harsh_decel = False

    def step(self, w: Window) -> tuple[PressEvent | None, float | None, int, int, bool]:
        """(끝난 이벤트, 페달 전환 시간, 급가속 시작 수, 급감속 시작 수, 이 윈도우가 주행 중인가)"""
        event = self._step_press(w)
        switch = self._step_switch(w)
        accel, decel, moving = self._step_speed(w.speed_kmh)
        self._prev_level = w.level
        return event, switch, accel, decel, moving

    def _step_press(self, w: Window) -> PressEvent | None:
        if w.brake >= self.brake_on:
            self._brake_run += 1
        else:
            if self._brake_run >= STOP_HOLD_SEC * WINDOWS_PER_SEC:
                self._windows_since_long_stop = 0
            self._brake_run = 0
        self._windows_since_long_stop += 1

        pressed = w.level >= ACCEL_ACTIVE_THRESHOLD
        if pressed and self._event_levels is None:
            self._event_levels = [w.level]
            self._start_prev_level = self._prev_level
            self._after_stop = self._windows_since_long_stop <= STOP_TO_START_SEC * WINDOWS_PER_SEC + 1
            return None
        if pressed and self._event_levels is not None:
            self._event_levels.append(w.level)
            return None
        if not pressed and self._event_levels is not None:
            levels = [self._start_prev_level] + self._event_levels
            rise = max((b - a for a, b in zip(levels, levels[1:])), default=0.0)
            event = PressEvent(
                peak=max(self._event_levels),
                rise=max(rise, 0.0),
                duration_sec=len(self._event_levels) / WINDOWS_PER_SEC,
                after_stop=self._after_stop,
            )
            self._event_levels = None
            return event
        return None

    def _step_switch(self, w: Window) -> float | None:
        if self._prev_level >= ACCEL_ACTIVE_THRESHOLD > w.level:
            self._windows_since_release = 0
        elif self._windows_since_release is not None:
            self._windows_since_release += 1
        if self._windows_since_release is None:
            return None
        if w.brake >= self.brake_on:
            gap = self._windows_since_release / WINDOWS_PER_SEC
            self._windows_since_release = None
            return gap if gap <= SWITCH_MAX_SEC else None
        if w.level >= ACCEL_ACTIVE_THRESHOLD:
            self._windows_since_release = None   # 브레이크 없이 다시 엑셀 — 전환 아님
        return None

    def _step_speed(self, speed: float | None) -> tuple[int, int, bool]:
        # GPS 는 약 1Hz 라 같은 값이 여러 윈도우 반복된다 → 값이 바뀐 순간에만, 직전 값이 바뀐 뒤 실제로 흐른
        # 시간으로 나눠 km/h/s 를 낸다. "4윈도우(1초) 전과 비교"는 갱신 간격이 0.78~1.05초로 흔들려 두 번의 갱신을
        # 1초 변화로 합치는 경우가 생겨 급가감속을 1.6배쯤 많이 셌다(2026-10-25 실주행 18회 vs 실제 11회).
        moving = speed is not None and speed >= HARSH_MIN_SPEED_KMH
        if speed is None:
            self._last_speed = None
            return 0, 0, moving
        if self._last_speed is None:
            self._last_speed, self._windows_since_speed_change = speed, 0
            return 0, 0, moving
        self._windows_since_speed_change += 1
        if speed == self._last_speed:
            return 0, 0, moving   # 새 fix 가 아니거나 속도 그대로 — 진행 중인 급가감속 상태는 유지
        elapsed = self._windows_since_speed_change / WINDOWS_PER_SEC
        rate = (speed - self._last_speed) / elapsed
        base_moving = self._last_speed >= HARSH_MIN_SPEED_KMH and elapsed <= MAX_FIX_GAP_SEC
        self._last_speed, self._windows_since_speed_change = speed, 0
        accel = base_moving and rate >= HARSH_ACCEL_KMH_PER_S
        decel = base_moving and rate <= -HARSH_DECEL_KMH_PER_S
        new_accel = int(accel and not self._in_harsh_accel)
        new_decel = int(decel and not self._in_harsh_decel)
        self._in_harsh_accel, self._in_harsh_decel = accel, decel
        return new_accel, new_decel, moving


class StyleTracker:
    """한 사용자의 성향 누적 상태(주행을 넘어 JSON 으로 이어짐). WindowStream 은 주행마다 새로 시작한다."""

    def __init__(self, hist: list[int] | None = None, events: list[list] | None = None,
                 switches: list[float] | None = None, harsh_accel: int = 0, harsh_decel: int = 0,
                 moving_sec: float = 0.0) -> None:
        self.hist = list(hist) if hist is not None else [0] * (HIST_BINS + 1)
        self.events: deque[PressEvent] = deque((PressEvent(*e) for e in (events or [])), maxlen=MAX_EVENTS)
        self.switches: deque[float] = deque(switches or [], maxlen=MAX_EVENTS)
        self.harsh_accel = harsh_accel
        self.harsh_decel = harsh_decel
        self.moving_sec = moving_sec
        self._stream: WindowStream | None = None

    def add_window(self, level: float, brake: float, brake_on: float, speed_kmh: float | None) -> None:
        if self._stream is None or self._stream.brake_on != brake_on:
            self._stream = WindowStream(brake_on)   # 영점 조절로 brake_low 가 바뀌면 그 기준으로 새로
        if level >= ACCEL_ACTIVE_THRESHOLD:
            self.hist[min(int(level * HIST_BINS), HIST_BINS)] += 1
        event, switch, accel, decel, moving = self._stream.step(Window(level, brake, speed_kmh))
        if event is not None:
            self.events.append(event)
        if switch is not None:
            self.switches.append(switch)
        self.harsh_accel += accel
        self.harsh_decel += decel
        if moving:
            self.moving_sec += 1 / WINDOWS_PER_SEC

    def p90(self) -> float | None:
        total = sum(self.hist)
        if total == 0:
            return None
        cumulative = 0
        for i, count in enumerate(self.hist):
            cumulative += count
            if cumulative >= 0.9 * total:
                return max(i / HIST_BINS, ACCEL_ACTIVE_THRESHOLD)
        return 1.0

    def metrics(self) -> StyleMetrics | None:
        p90 = self.p90()
        if p90 is None or not self.events:
            return None
        events = list(self.events)
        starts = [e.rise for e in events if e.after_stop]
        hours = self.moving_sec / 3600
        has_speed = self.moving_sec >= MIN_MOVING_SEC
        return StyleMetrics(
            rel_rise=_median([e.rise for e in events]) / p90,
            full_press_ratio=sum(e.peak >= FULL_PRESS_RATIO * p90 for e in events) / len(events),
            hold_sec=sum(e.duration_sec for e in events) / len(events),
            start_rel_rise=_median(starts) / p90 if len(starts) >= MIN_SAMPLES else None,
            switch_sec=_median(list(self.switches)) if len(self.switches) >= MIN_SAMPLES else None,
            harsh_accel_per_h=self.harsh_accel / hours if has_speed else None,
            harsh_decel_per_h=self.harsh_decel / hours if has_speed else None,
        )

    def to_json(self) -> str:
        return json.dumps({
            "hist": self.hist,
            "events": [[e.peak, e.rise, e.duration_sec, e.after_stop] for e in self.events],
            "switches": list(self.switches),
            "harsh_accel": self.harsh_accel, "harsh_decel": self.harsh_decel, "moving_sec": self.moving_sec,
        })

    def report_json(self, demo: bool) -> str:
        """Chaquopy 경계용: 진단 화면이 그대로 그리는 결과(진행도, 유형, 지표, 기준점)."""
        gate = DEMO_GATE_EVENTS if demo else GATE_EVENTS
        m = self.metrics()
        ref = load_reference()
        ready = m is not None and len(self.events) >= gate
        result = diagnose(m, ref) if ready and ref is not None else None
        return json.dumps({
            "events": len(self.events), "gate": gate, "ready": ready,
            "label": result.label if result else None,
            "distances": result.distances if result else None,
            "metrics": asdict(m) if m else None,
            "reference": {"centroids": ref.centroids, "trained_at": ref.trained_at, "chunks": ref.chunks} if ref else None,
        })


def load_tracker(state_json: str) -> StyleTracker:
    """Chaquopy 경계용: 저장된 상태(없으면 빈 문자열)로 누적기를 만든다."""
    if not state_json:
        return StyleTracker()
    s = json.loads(state_json)
    return StyleTracker(s["hist"], s["events"], s["switches"], s["harsh_accel"], s["harsh_decel"], s["moving_sec"])


def metrics_from_windows(windows: list[Window], brake_on: float) -> StyleMetrics | None:
    """PC 일괄 계산 — 앱과 같은 경로(StyleTracker)로 한 구간의 지표를 낸다."""
    tracker = StyleTracker()
    for w in windows:
        tracker.add_window(w.level, w.brake, brake_on, w.speed_kmh)
    return tracker.metrics()


# ── 진단: 최근접 중심(nearest centroid) ─────────────────────────────────────


@dataclass(frozen=True)
class Reference:
    """성향별 지표 중심값과 지표별 척도(표준편차). PC 학습이 라벨 데이터에서 만든다."""

    centroids: dict[str, dict[str, float]]   # 성향 id → 지표 이름 → 값
    scale: dict[str, float]
    trained_at: str
    chunks: dict[str, int]                   # 성향별 학습 구간 수(근거 표시용)


@dataclass(frozen=True)
class Diagnosis:
    label: str
    distances: dict[str, float]


def parse_reference(text: str) -> Reference | None:
    try:
        doc = json.loads(text)
        return Reference(
            centroids={k: {f: float(v) for f, v in c.items()} for k, c in doc["centroids"].items()},
            scale={f: float(v) for f, v in doc["scale"].items()},
            trained_at=str(doc.get("trained_at", "")),
            chunks={k: int(v) for k, v in doc.get("chunks", {}).items()},
        )
    except (ValueError, KeyError, TypeError, AttributeError):
        return None


def load_reference() -> Reference | None:
    try:
        raw = pkgutil.get_data(__package__, REFERENCE_FILE)
    except OSError:
        return None
    return parse_reference(raw.decode("utf-8")) if raw else None


def diagnose(m: StyleMetrics, ref: Reference) -> Diagnosis | None:
    """내 지표와 각 성향 중심의 표준화 거리 — 양쪽 다 값이 있는 지표만 쓴다. 쓸 지표가 없으면 None."""
    mine = {k: v for k, v in asdict(m).items() if v is not None}
    distances: dict[str, float] = {}
    for label, centroid in ref.centroids.items():
        feats = [f for f in centroid if f in mine and ref.scale.get(f, 0) > 0]
        if not feats:
            continue
        distances[label] = round(math.sqrt(sum(((mine[f] - centroid[f]) / ref.scale[f]) ** 2 for f in feats) / len(feats)), 4)
    if not distances:
        return None
    return Diagnosis(label=min(distances, key=distances.__getitem__), distances=distances)
