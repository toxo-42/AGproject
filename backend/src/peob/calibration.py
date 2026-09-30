"""캘리브레이션 세션 -> 연속형(continuous) 개인화 임계값.

한때 강/보통/약 3단계로 "분류"하는 방식(classify_profile, 강/보통/약 discrete
버킷)을 실험했었는데, normal↔strong 경계가 실측 데이터에서 자꾸 겹쳐서
불안정했다(§진행상황_및_로드맵.md Phase B). 그래서 분류 없이 "이 사람의
캘리브레이션 데이터로 직접 임계값을 계산"하는 이 방식으로 확정했다 — 버킷
경계 자체가 없으니 경계에서 오분류될 일도 없다.

judge.py 의 judge_with_thresholds() 가 기대하는 형태
({"accel_high":.., "brake_low":.., "high_ratio":..})를 그대로 반환한다.

⚠️ 1차 구현 근거 (2026-07-13 실험, §진행상황_및_로드맵.md Phase B 참고):
   9세션(강/보통/약 각 3개)에서 accel_high = baseline + offset 을 계산해
   그 세션 자기 자신(전부 정상 주행)에 대한 오탐률을 측정했다.
   - accel_active_mean 기준: +0.30을 더해야 오탐률이 2%대로 안정.
   - accel_active_p90 기준: +0.08~0.10만으로 전 세션 2% 이하로 안정 (더 효율적).
   그래서 accel_active_p90 + 오프셋을 채택했다.

   ⚠️ 이건 "정상 주행에서 오탐이 적은가"만 검증한 것이고, "진짜 오조작을
   놓치지 않고 잡아내는가"(민감도)는 아직 미검증이다 — 오조작 라벨 데이터가
   생기면 반드시 재검증 필요.

   high_ratio 는 아직 개인화 실험 전이라 기존 PROFILES["normal"]
   값을 그대로 재사용한다(임시). brake_low 는 2026-09-30 부터 캘리브레이션 중
   브레이크 기준값(offset)을 반영한다(BRAKE_LOW_MARGIN 주석 참고).

⚠️ 오프셋 재조정 (2026-07-13, 실기기 캘리브레이션 후 사용자 피드백):
   accel 0.6~0.7 유지하며 캘리브레이션했더니 accel_high=0.84 로 잡혔는데,
   "평소 밟는 범위"와 "브레이크로 착각해 콱 밟는 슬램"이 값으로 거의 안 갈렸다
   (오탐 방지 실험은 "정상 주행에서 오경보 안 나는가"만 봤지, "평소 범위와 슬램
   사이에 체감할 만한 여유가 있는가"는 안 봤었다). → offset 을 0.10 -> 0.20 으로
   올려 여유를 더 준다. 이러면 위 예시가 accel_high≈0.94 가 돼서, "고속도로에서
   좀 세게 밟기"와 "슬램"이 더 확실히 구분될 것으로 기대 — 실기기 재검증 필요.
"""

from __future__ import annotations

import json

from .features import extract_features

# accel_high = accel_active_p90 + 이 값. §진행상황_및_로드맵.md 실험(2026-07-13) 근거.
# 2026-07-13 재조정: 0.10 -> 0.20 (평소 범위와 슬램 사이 여유 확보, 위 모듈 docstring 참고).
ACCEL_HIGH_OFFSET = 0.20

# high_ratio/accel_rate_high 는 아직 개인화 안 함 — PROFILES["normal"]과 동일한 임시값.
# DEFAULT_BRAKE_LOW 는 브레이크 offset 이 없을 때의 하한(= 기존 고정값)으로 계속 쓴다.
DEFAULT_BRAKE_LOW = 0.10

# brake_low = max(DEFAULT_BRAKE_LOW, 캘리브레이션 중 brake_p90 + 이 값).
# ⚠️ 2026-09-30 실차 부착 테스트: 케이스를 브레이크 페달에 고정하는 압력(또는 케이스 모델링) 때문에
#   발을 뗀 상태에서도 브레이크 값이 ~0.2 로 떠 있었다. 고정 brake_low=0.10 이면 모든 샘플이
#   "브레이크 밟음"으로 탈락해 misop 이 영영 안 떠서 캘리브레이션 후에도 경고가 안 났다.
#   offset 은 부착할 때마다 달라질 수 있어 하드코딩하지 않고, 캘리브레이션(브레이크에서 발을 뗀 상태)의
#   브레이크 분포를 그 부착 상태의 0점으로 보고 이 여유만큼 위를 기준선으로 잡는다.
#   offset 이 없으면 max() 로 기존 0.10 이 그대로 유지된다. 여유값은 실측 튜닝 전 임시값.
BRAKE_LOW_MARGIN = 0.10

DEFAULT_HIGH_RATIO = 0.50
DEFAULT_ACCEL_RATE_HIGH = 0.30


def calibrate_thresholds(samples: list[list[float]]) -> dict[str, float]:
    """캘리브레이션 세션(수십 초~) 하나를 받아 개인화된 임계값 세트를 반환.

    judge.py 의 judge_with_thresholds(window, thresholds, label) 에 그대로 넘기면 된다.
    """
    f = extract_features(samples)
    accel_high = min(f["accel_active_p90"] + ACCEL_HIGH_OFFSET, 1.0)
    brake_low = min(max(DEFAULT_BRAKE_LOW, f["brake_p90"] + BRAKE_LOW_MARGIN), 1.0)
    return {
        "accel_high": round(accel_high, 4),
        "brake_low": round(brake_low, 4),
        "high_ratio": DEFAULT_HIGH_RATIO,
        "accel_rate_high": DEFAULT_ACCEL_RATE_HIGH,
    }


def preset_thresholds(accel_high: float) -> dict[str, float]:
    """accel_high 만 고정값으로 정한 임계값 세트 — 캘리브레이션 없이 쓸 때(개발자 수집 페르소나 프리셋).

    나머지 값은 calibrate_thresholds() 와 같은 기본값을 쓴다(한곳에서 관리).
    """
    return {
        "accel_high": round(min(accel_high, 1.0), 4),
        "brake_low": DEFAULT_BRAKE_LOW,
        "high_ratio": DEFAULT_HIGH_RATIO,
        "accel_rate_high": DEFAULT_ACCEL_RATE_HIGH,
    }


def preset_thresholds_json(accel_high: float) -> str:
    """Chaquopy 경계용: preset_thresholds() 를 JSON 문자열로 반환."""
    return json.dumps(preset_thresholds(accel_high))


def calibrate_thresholds_json(samples_json: str) -> str:
    """Chaquopy 경계용: 캘리브레이션 세션(JSON 문자열)을 받아 임계값 세트를 JSON으로 반환.

    Kotlin 쪽:
      val samplesJson = JSONArray(...).toString()   // 캘리브레이션 동안 모은 원시 샘플 전체
      val thresholdsJson = py.callAttr("calibrate_thresholds_json", samplesJson).toString()
      // 이 thresholdsJson을 SharedPreferences에 저장하고, judge_calibrated_json에 그대로 넘긴다.
    """
    samples = json.loads(samples_json)
    return json.dumps(calibrate_thresholds(samples))
