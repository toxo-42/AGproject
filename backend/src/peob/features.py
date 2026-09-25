"""주행 세션(페달 압력 시계열) -> 개인별 feature 추출.

judge()가 "이 윈도우가 오조작인가"를 판단한다면, 여기는 "이 사람이 평소
어떻게 페달을 밟는가"를 몇 개의 숫자로 요약한다. 이 요약값(feature)이
나중에 "이 사람은 strong/normal/weak 중 어디인가"를 정하는 프로필 판정
로직의 입력이 된다(§진행상황_및_로드맵.md Phase B).

judge()와 달리 이건 짧은 윈도우(0.25초) 단위가 아니라, 한 세션(수십 초~
수분) 전체를 한 번에 받는다고 가정한다 — 평소 습관은 순간이 아니라
누적된 패턴에서 나오기 때문.

샘플 1개 = [accel, brake]  (judge/synth 와 동일 포맷)

⚠️ 여기 뽑는 feature들은 '무엇을 잴지'에 대한 1차 구현이고, 값의 의미가
   실제로 프로필과 상관관계가 있는지는 아직 실데이터로 검증 전이다.
   기준값(예: "accel_mean이 얼마 이상이면 strong")은 나중에 실데이터를
   보면서 정한다 — 이 파일은 측정 도구만 제공한다.

⚠️ 실기기 CSV로 처음 돌려보니 accel_mean/accel_p90 같은 '세션 전체' 통계는
   idle(페달 안 밟은) 구간에 파묻혀 무의미했다(실주행 세션 대부분이 idle이고
   실제로 밟는 순간은 전체의 0.4~5% 정도였음). 그래서 "밟고 있을 때만" 조건부로
   보는 accel_active_*, 그리고 얼마나 자주/오래 밟는지 보는 press_* 지표를
   추가했다 — 세션 전체 평균보다 이쪽이 운전 습관을 더 잘 드러낼 것으로 본다.
"""

from __future__ import annotations

import math

# 엑셀/브레이크를 "동시에 밟았다"고 볼 최소 압력. 페달을 밟는 도중 자연스레
# 스치는 잡음과 구분하기 위한 값 — 임시값, 실데이터로 조정 필요.
SIMULTANEOUS_PRESS_THRESHOLD = 0.1

# 엑셀을 "밟고 있다"고 볼 최소 압력. accel_active_*/press_* 지표가 이 값을
# 기준으로 idle과 press를 구분한다 — 임시값, 실데이터로 조정 필요.
ACCEL_ACTIVE_THRESHOLD = 0.1

# 실기기 샘플레이트(BLE_RAW_스트림_규격.md, BleService와 동일: 50Hz 배치 × 4샘플).
# press_rate_per_min/press_duration_mean_sec 처럼 '시간' 단위가 필요한 지표에서만 쓰인다.
DEFAULT_SAMPLE_RATE_HZ = 200


def _mean(xs: list[float]) -> float:
    return sum(xs) / len(xs)


def _std(xs: list[float]) -> float:
    if len(xs) < 2:
        return 0.0
    m = _mean(xs)
    variance = sum((x - m) ** 2 for x in xs) / len(xs)
    return math.sqrt(variance)


def _percentile(xs: list[float], p: float) -> float:
    """p는 0~100. 선형 보간 방식(numpy.percentile 기본값과 동일한 아이디어)."""
    if not xs:
        return 0.0
    s = sorted(xs)
    if len(s) == 1:
        return s[0]
    rank = (p / 100) * (len(s) - 1)
    lo = math.floor(rank)
    hi = math.ceil(rank)
    if lo == hi:
        return s[lo]
    frac = rank - lo
    return s[lo] + (s[hi] - s[lo]) * frac


def _press_event_lengths(accels: list[float], threshold: float) -> list[int]:
    """threshold 이상인 연속 구간(= 한 번 밟은 것)마다 그 길이(샘플 수)를 뽑는다.

    예: [0, 0, 0.5, 0.6, 0.4, 0, 0.2, 0] , threshold=0.1
        -> 두 번의 press: 길이 3, 길이 1
    """
    lengths: list[int] = []
    current = 0
    for a in accels:
        if a >= threshold:
            current += 1
        else:
            if current > 0:
                lengths.append(current)
            current = 0
    if current > 0:
        lengths.append(current)
    return lengths


def extract_features(
    samples: list[list[float]],
    sample_rate_hz: float = DEFAULT_SAMPLE_RATE_HZ,
) -> dict[str, float]:
    """세션 하나(전체 시계열)를 받아 개인별 feature 딕셔너리를 반환.

    입력이 비어있으면 모든 feature를 0.0으로 채운 딕셔너리를 반환한다
    (호출부가 매번 빈 세션을 예외 처리하지 않아도 되게).
    """
    keys = [
        "accel_mean", "accel_std", "accel_p90",
        "brake_mean", "brake_std",
        "max_accel_jump",
        "simultaneous_press_ratio",
        "accel_active_mean", "accel_active_p90",
        "press_rate_per_min", "press_duration_mean_sec",
    ]
    if not samples:
        return {k: 0.0 for k in keys}

    accels = [s[0] for s in samples]
    brakes = [s[1] for s in samples]

    max_accel_jump = 0.0
    for i in range(1, len(accels)):
        jump = abs(accels[i] - accels[i - 1])
        if jump > max_accel_jump:
            max_accel_jump = jump

    sim_hits = sum(
        1 for a, b in zip(accels, brakes)
        if a >= SIMULTANEOUS_PRESS_THRESHOLD and b >= SIMULTANEOUS_PRESS_THRESHOLD
    )

    # "밟고 있을 때만" 조건부 통계 — idle 구간(대부분)에 파묻히지 않게 분리.
    active_accels = [a for a in accels if a >= ACCEL_ACTIVE_THRESHOLD]
    accel_active_mean = _mean(active_accels) if active_accels else 0.0
    accel_active_p90 = _percentile(active_accels, 90) if active_accels else 0.0

    # 얼마나 자주(rate), 얼마나 오래(duration) 밟는지 — 세기(accel_active_*)와는
    # 별개로 '조작 빈도/지속시간' 자체가 습관을 드러낼 수 있다.
    event_lengths = _press_event_lengths(accels, ACCEL_ACTIVE_THRESHOLD)
    duration_sec = len(samples) / sample_rate_hz
    press_rate_per_min = (len(event_lengths) / duration_sec) * 60 if duration_sec > 0 else 0.0
    press_duration_mean_sec = (
        _mean(event_lengths) / sample_rate_hz if event_lengths else 0.0
    )

    return {
        "accel_mean": round(_mean(accels), 4),
        "accel_std": round(_std(accels), 4),
        "accel_p90": round(_percentile(accels, 90), 4),
        "brake_mean": round(_mean(brakes), 4),
        "brake_std": round(_std(brakes), 4),
        "max_accel_jump": round(max_accel_jump, 4),
        "simultaneous_press_ratio": round(sim_hits / len(samples), 4),
        "accel_active_mean": round(accel_active_mean, 4),
        "accel_active_p90": round(accel_active_p90, 4),
        "press_rate_per_min": round(press_rate_per_min, 4),
        "press_duration_mean_sec": round(press_duration_mean_sec, 4),
    }
