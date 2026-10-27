"""PC 학습 결과(learned_thresholds.json)를 앱 판정에 반영한다 — 파이프라인의 "배포 → 추론" 연결부.

학습(prototype/src/train_thresholds.py)이 이 패키지 안의 learned_thresholds.json 에 결과를 쓰고,
앱은 Chaquopy 로 이 패키지를 통째로 번들하므로 다시 빌드하면 새 학습값이 들어간다(사람이 숫자를 옮기지 않는다).

검증 게이트를 통과한(adopt: true) 결과만 쓴다. 파일이 없거나, 채택되지 않았거나, 형식이 틀리면 None →
호출하는 쪽은 지금 규칙(습관 학습 p90 + HABIT_OFFSET, 변화율 DEFAULT_ACCEL_RATE_HIGH)을 그대로 쓴다.
즉 학습이 실패해도 앱의 안전성은 학습 도입 전 수준 아래로 내려가지 않는다.
"""

from __future__ import annotations

import json
import pkgutil
from dataclasses import dataclass

LEARNED_FILE = "learned_thresholds.json"


@dataclass(frozen=True)
class LearnedParams:
    """채택된 학습 파라미터. accel_high 공식(slope × p90 + intercept)의 p90 은 습관 학습이 잰다."""

    slope: float
    intercept: float
    accel_rate_high: float
    trained_at: str
    sessions: int
    synthetic_sessions: int

    def accel_high(self, p90: float) -> float:
        return self.slope * p90 + self.intercept


def parse_learned(text: str) -> LearnedParams | None:
    """학습 결과 JSON 문자열 → 채택된 경우에만 LearnedParams."""
    try:
        doc = json.loads(text)
        if doc.get("adopt") is not True:
            return None
        data = doc.get("data", {})
        return LearnedParams(
            slope=float(doc["accel_high"]["slope"]),
            intercept=float(doc["accel_high"]["intercept"]),
            accel_rate_high=float(doc["accel_rate_high"]),
            trained_at=str(doc.get("trained_at", "")),
            sessions=int(data.get("sessions", 0)),
            synthetic_sessions=int(data.get("synthetic_sessions", 0)),
        )
    except (ValueError, KeyError, TypeError, AttributeError):
        return None


def load_learned() -> LearnedParams | None:
    """패키지에 번들된 학습 결과를 읽는다. pkgutil.get_data 는 PC 파일 시스템과 Chaquopy 에셋 양쪽에서 동작한다."""
    try:
        raw = pkgutil.get_data(__package__, LEARNED_FILE)
    except OSError:
        return None
    return parse_learned(raw.decode("utf-8")) if raw else None


def apply_learned(thresholds: dict, learned: LearnedParams | None) -> dict:
    """임계값 세트의 변화율 트리거를 학습값으로 바꾼다. accel_high 는 습관 학습(habit)이 학습 공식으로 정한다."""
    if learned is None:
        return thresholds
    return {**thresholds, "accel_rate_high": round(learned.accel_rate_high, 4)}


def apply_learned_json(thresholds_json: str) -> str:
    """Chaquopy 경계용: 사용자 판정 경로의 임계값 JSON 에 학습값을 반영한다(개발자 페르소나 프리셋엔 쓰지 않음)."""
    return json.dumps(apply_learned(json.loads(thresholds_json), load_learned()))


def learned_info_json() -> str:
    """Chaquopy 경계용: 화면 표시용 요약. 채택된 학습값이 없으면 빈 문자열."""
    learned = load_learned()
    if learned is None:
        return ""
    return json.dumps({
        "trained_at": learned.trained_at,
        "sessions": learned.sessions,
        "synthetic_sessions": learned.synthetic_sessions,
    })
