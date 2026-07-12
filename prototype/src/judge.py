"""페달 오조작 판정 — ★ 이 파일이 Chaquopy로 그대로 이식된다.

계약(Contract):
  입력  samples: list[[accel, brake], ...]   # Kotlin이 윈도우째로 넘김
        profile: str                          # "strong" | "normal" | "weak"
  출력  dict {"misop": bool, "score": float, "profile": str, "reason": str}

Kotlin <-> Python 경계를 단순하게 유지하려고:
  - 입력은 중첩 리스트(기본 자료형)와 문자열만. numpy 객체를 인자로 받지 않는다.
  - 출력은 JSON 직렬화 가능한 dict. (Chaquopy에선 judge_json 사용 권장)

개인화 구조:
  '이 사람이 어느 프로필인가'를 정하는 일(프로필 판정)과 '지금 이 윈도우가
  오조작인가'를 정하는 일(오조작 판정)을 분리한다. judge()는 후자만 한다.
  프로필을 통계 규칙으로 정하든 학습된 분류기로 정하든 이 함수는 바뀌지 않는다.

지금 판정 로직은 '예시 휴리스틱'이다. 실제 알고리즘은 여기만 갈아끼우면
PC/앱 양쪽에 동시에 반영된다.
"""

from __future__ import annotations

import json

# 프로필별 판정 임계값 --------------------------------------------------------
# ⚠️ 전부 실데이터 튜닝 전 임시값. 아래는 '의도'일 뿐이며 측정으로 대체해야 한다.
#
#   accel_high : 엑셀이 이 이상이면 '깊게 밟음'
#   brake_low  : 브레이크가 이 이하면 '거의 안 밟음'
#   high_ratio : 윈도우 내 '엑셀 깊게 + 브레이크 안 밟음' 비율이 이 이상이면 오조작
#
# 의도: 평소 페달을 깊게 밟는 사람(strong)에겐 깊게 밟는 것 자체가 정상이므로
#       accel_high 를 올려 오경보를 줄이고, 약하게 밟는 사람(weak)은 낮춰 민감하게 한다.
#       brake_low 도 같은 방향으로 움직인다(강하게 밟는 사람은 '살짝 밟은' 값도 크다).
PROFILES: dict[str, dict[str, float]] = {
    "strong": {"accel_high": 0.92, "brake_low": 0.15, "high_ratio": 0.55},
    "normal": {"accel_high": 0.85, "brake_low": 0.10, "high_ratio": 0.50},
    "weak": {"accel_high": 0.75, "brake_low": 0.07, "high_ratio": 0.45},
}

DEFAULT_PROFILE = "normal"


def get_thresholds(profile: str) -> dict[str, float]:
    """프로필 이름 -> 임계값 세트. 모르는 이름이면 기본 프로필로 떨어진다.

    앱에서 프로필이 아직 판정되지 않았거나 저장값이 깨진 경우에도
    판정이 멈추지 않고 '보통' 기준으로 계속 동작해야 한다.
    """
    return PROFILES.get(profile, PROFILES[DEFAULT_PROFILE])


def thresholds_json(profile: str = DEFAULT_PROFILE) -> str:
    """Chaquopy 경계용: 프로필의 임계값 세트를 JSON 문자열로 반환.

    데이터 수집 화면이 그래프에 임계선을 그릴 때 쓴다. Kotlin 이 임계값을
    따로 하드코딩하지 않게 해서 PROFILES 를 단일 소스로 유지한다.
    """
    return json.dumps(get_thresholds(profile))


def judge_with_thresholds(samples: list[list[float]], thresholds: dict[str, float], label: str) -> dict:
    """윈도우 하나 + 임계값 세트를 직접 받아 오조작 여부를 판정.

    3단계 프로필(get_thresholds)이든, 캘리브레이션으로 계산한 연속형 임계값
    (calibration.calibrate_thresholds)이든 이 함수 입장에서는 그냥 숫자 3개일
    뿐이다 — '어디서 온 임계값인가'와 '지금 오조작인가'를 분리하기 위한 공용 코어.

    label 은 결과의 "profile" 필드에 그대로 실린다(로그/디버깅용 표시 이름).
    """
    if not samples:
        return {"misop": False, "score": 0.0, "profile": label, "reason": "empty"}

    accel_high = thresholds["accel_high"]
    brake_low = thresholds["brake_low"]
    high_ratio = thresholds["high_ratio"]

    hits = 0
    for accel, brake in samples:
        if accel >= accel_high and brake <= brake_low:
            hits += 1

    score = hits / len(samples)
    misop = score >= high_ratio
    reason = (
        f"[{label}] 엑셀>={accel_high} & 브레이크<={brake_low} 비율 {score:.2f}"
        f" ({'>=' if misop else '<'} {high_ratio})"
    )
    return {
        "misop": misop,
        "score": round(score, 3),
        "profile": label,
        "reason": reason,
    }


def judge(samples: list[list[float]], profile: str = DEFAULT_PROFILE) -> dict:
    """윈도우 하나를 받아 오조작 여부를 판정.

    예시 휴리스틱: 윈도우 안에서 '엑셀을 깊게 밟으면서 브레이크는 거의
    안 밟은' 샘플 비율이 임계값을 넘으면 오조작으로 본다.
    """
    return judge_with_thresholds(samples, get_thresholds(profile), profile)


def judge_json(samples_json: str, profile: str = DEFAULT_PROFILE) -> str:
    """Chaquopy 경계용: Kotlin이 JSON 문자열로 넘긴 윈도우를 판정해 JSON 문자열로 반환.

    입출력을 모두 JSON '문자열'로 주고받는 이유:
      Chaquopy는 Java의 ArrayList 를 Python list 로 자동 변환하지 않아서,
      윈도우(중첩 리스트)를 그대로 넘기면 'ArrayList object is not iterable' 이 난다.
      문자열로 직렬화해 넘기면 Java<->Python 컬렉션 변환 문제를 완전히 피한다.
      (profile 은 단순 문자열이라 Chaquopy가 그대로 변환해준다.)

    profile 은 기본값이 있으므로 Kotlin이 인자 1개로 호출해도 동작한다(= 기존 호출부 호환).

    Kotlin 쪽:
      val samplesJson = JSONArray(...).toString()       // "[[0.95,0.0], ...]"
      val resultJson = py.callAttr("judge_json", samplesJson, "normal").toString()
    """
    samples = json.loads(samples_json)
    return json.dumps(judge(samples, profile), ensure_ascii=False)


def judge_calibrated_json(samples_json: str, thresholds_json_str: str) -> str:
    """Chaquopy 경계용: 캘리브레이션으로 계산한 연속형 임계값으로 판정.

    3단계 프로필 대신 개인화된 임계값(calibration.calibrate_thresholds 로 만든
    {"accel_high":.., "brake_low":.., "high_ratio":..} 딕셔너리)을 쓸 때 이걸 호출한다.
    judge_json 과 마찬가지로 Kotlin<->Python 경계는 JSON 문자열로만 주고받는다.

    Kotlin 쪽:
      val thresholdsJson = JSONObject(calibratedMap).toString()
      val resultJson = py.callAttr("judge_calibrated_json", samplesJson, thresholdsJson).toString()
    """
    samples = json.loads(samples_json)
    thresholds = json.loads(thresholds_json_str)
    result = judge_with_thresholds(samples, thresholds, "personalized")
    return json.dumps(result, ensure_ascii=False)
