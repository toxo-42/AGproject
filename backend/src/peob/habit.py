"""주행하면서 운전 습관(평소 엑셀 깊이)을 누적 학습해 개인 accel_high 를 정한다.

15초 캘리브레이션으로는 습관을 잴 수 없다는 분석(2026-10-01, §진행상황_및_로드맵.md
"평소 값 수렴 분석")에서 나온 방식:
  - 15초 구간 p90 은 기준보다 대부분 낮게 나옴(self 중앙 −0.17).
  - 주행 한 번 안에선 ~10분·엑셀 이벤트 ~100회면 ±0.05 안으로 안정.
  - 같은 사람도 주행마다 다르고(도로 상황), 주행을 누적하면 빠르게 수렴.
그래서 주행마다 판정 윈도우의 엑셀 중앙값을 히스토그램에 누적하고(주행 사이에도 이어짐),
누적 p90 으로 임계값을 계산한다. 척도는 CSV accel_median·prototype 학습 파이프라인과 같다.

적용 규칙(사용자 합의 2026-10-01):
  - 이벤트 GATE_EVENTS 회 전엔 None(기본값/수동 캘리브레이션 유지).
  - 개인값 = min(p90 + HABIT_OFFSET, HABIT_CAP). +0.20 이면 평소 세게 밟는 사람(dad p90 0.77)의
    임계값이 0.97 이 돼 급밟기(self 최대 0.89)가 도달 못 할 수 있어 +0.10·상한 0.90 으로 정함.
    PC 학습 결과가 채택돼 있으면(learned.py) p90 + HABIT_OFFSET 대신 학습 공식 slope × p90 + intercept 를 쓴다
    (상한은 그대로). 공식은 personal_accel_high 하나 — PC 검증(train_thresholds)도 이걸로 앱과 같은 값을 재현한다.
  - 기준값(base)에서 개인값으로 가중치 n/(n+BLEND_K) 만큼 이동 — 데이터가 늘수록 개인값 쪽으로.
  - 직전 적용값과 DEAD_BAND 미만 차이면 바꾸지 않는다(의미 없는 흔들림 방지).
  - 언제 적용할지(주행 시작 + 첫 게이트 도달 순간)는 호출하는 쪽(BleService)이 정한다.
"""

from __future__ import annotations

import json
from collections import deque

from .features import ACCEL_ACTIVE_THRESHOLD
from .learned import LearnedParams, load_learned

HIST_BINS = 100          # 0.01 간격 — 원본을 쌓지 않고 분포만 유지(10분에 수십만 샘플이라)
GATE_EVENTS = 100
BLEND_K = 100
HABIT_OFFSET = 0.10
HABIT_CAP = 0.90
DEAD_BAND = 0.02
# 경고가 뜨면 그 앞뒤는 "정상 습관"이 아니다 — 학습에 넣으면 임계값이 점점 올라가 오조작을 놓친다.
# 윈도우 0.25초 기준 앞 0.5초·뒤 2초(prototype train_thresholds 의 ONSET_LEAD_ROWS/DETECT_TAIL_ROWS 와 같은 근거).
PRE_FIRE_WINDOWS = 2
POST_FIRE_WINDOWS = 8


def personal_accel_high(p90: float, slope: float = 1.0, intercept: float = HABIT_OFFSET) -> float:
    """평소 p90 → 개인 accel_high. 기본 인자가 지금 규칙(p90 + HABIT_OFFSET), 학습값이 있으면 그 기울기·절편."""
    return min(max(slope * p90 + intercept, 0.0), HABIT_CAP)


class HabitLearner:
    """평소 엑셀 깊이 누적기. 인스턴스 하나 = 한 사용자의 누적 상태(주행을 넘어 JSON 으로 이어짐)."""

    def __init__(
        self, hist: list[int] | None = None, events: int = 0, applied: float | None = None,
        learned: LearnedParams | None = None,
    ) -> None:
        self.learned = learned   # PC 학습 결과(채택된 경우만) — 개인값 공식을 바꾼다. 저장하지 않음(앱 번들에서 매번 읽음)
        self.hist = list(hist) if hist is not None else [0] * (HIST_BINS + 1)
        self.events = events
        self.applied = applied   # 마지막으로 적용한 accel_high — 주행을 넘어 DEAD_BAND 비교에 쓴다
        # 아래는 주행 중에만 쓰는 상태(저장하지 않음)
        self._pending: deque[float] = deque()   # 경고 직전 구간을 버릴 수 있게 잠시 붙잡아 두는 윈도우
        self._cooldown = 0
        self._pressed = False

    @property
    def ready(self) -> bool:
        return self.events >= GATE_EVENTS

    def add_window(self, level: float, fired: bool) -> bool:
        """판정 윈도우 하나(엑셀 중앙값, 경고 발동 여부)를 반영. 이번 호출로 게이트를 처음 넘었으면 True."""
        was_ready = self.ready
        if fired:
            self._pending.clear()
            self._cooldown = POST_FIRE_WINDOWS
            self._pressed = False
        elif self._cooldown > 0:
            self._cooldown -= 1
        else:
            self._pending.append(level)
            if len(self._pending) > PRE_FIRE_WINDOWS:
                self._commit(self._pending.popleft())
        return self.ready and not was_ready

    def _commit(self, level: float) -> None:
        pressed = level >= ACCEL_ACTIVE_THRESHOLD
        if pressed:
            self.hist[min(int(level * HIST_BINS), HIST_BINS)] += 1
            if not self._pressed:
                self.events += 1
        self._pressed = pressed

    def p90(self) -> float | None:
        total = sum(self.hist)
        if total == 0:
            return None
        cumulative = 0
        for i, count in enumerate(self.hist):
            cumulative += count
            if cumulative >= 0.9 * total:
                return i / HIST_BINS
        return 1.0

    def update_applied(self, base: float) -> float | None:
        """지금 적용할 accel_high 를 계산해 기록하고 돌려준다. 게이트 전이면 None(base 그대로 쓰라는 뜻)."""
        p90 = self.p90()
        if not self.ready or p90 is None:
            return None
        learned = self.learned
        personal = personal_accel_high(p90) if learned is None else personal_accel_high(p90, learned.slope, learned.intercept)
        weight = self.events / (self.events + BLEND_K)
        value = round(base + weight * (personal - base), 4)
        if self.applied is not None and abs(value - self.applied) < DEAD_BAND:
            return self.applied
        self.applied = value
        return value

    def apply_json(self, thresholds_json_str: str) -> str:
        """Chaquopy 경계용: 임계값 JSON 의 accel_high 를 학습값으로 바꿔 돌려준다(게이트 전이면 그대로)."""
        thresholds = json.loads(thresholds_json_str)
        value = self.update_applied(thresholds["accel_high"])
        if value is not None:
            thresholds["accel_high"] = value
        return json.dumps(thresholds)

    def to_json(self) -> str:
        """저장용 상태. events/gate 는 화면의 "습관 학습 중 N/100" 표시에도 쓰인다."""
        return json.dumps({
            "hist": self.hist, "events": self.events, "applied": self.applied,
            "gate": GATE_EVENTS, "ready": self.ready,
        })


def load_learner(state_json: str) -> HabitLearner:
    """Chaquopy 경계용: 저장된 상태(JSON 문자열, 없으면 빈 문자열)와 번들된 학습 결과로 학습기를 만든다."""
    learned = load_learned()
    if not state_json:
        return HabitLearner(learned=learned)
    state = json.loads(state_json)
    return HabitLearner(state["hist"], state["events"], state.get("applied"), learned=learned)
