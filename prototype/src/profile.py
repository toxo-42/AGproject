"""캘리브레이션 데이터로 운전자 프로필(강/보통/약) 판정.

judge.py 가 "이 윈도우가 오조작인가"만 본다면, 여기는 그 반대편 절반인
"이 사람이 어느 프로필인가"를 담당한다. features.py 로 뽑은 요약값을 보고
strong/normal/weak 중 하나로 분류한다(§진행상황_및_로드맵.md Phase B).

이 함수의 출력(프로필 이름)은 judge.py 의 PROFILES 키와 그대로 맞물린다:
    profile = classify_profile(session_samples)
    result = judge(window, profile)

⚠️ 1차 구현: 개발자 본인이 강/보통/약 스타일로 각 3세션(90초 안팎)씩 수집한
   9개 세션의 accel_active_p90 값을 보고 잡은 컷오프다.
   - weak  세션: 0.483, 0.643, 0.699
   - normal 세션: 0.725, 0.754, 0.804
   - strong 세션: 0.800, 0.880, 0.888
   weak ↔ normal 경계(0.699 vs 0.725)는 깔끔하지만, normal ↔ strong 경계는
   거의 겹친다(0.804 vs 0.800) — strong 세션 중 하나가 normal 최댓값보다도
   낮게 나왔다. 실사용자 데이터가 쌓이면 반드시 재조정해야 하는 임시값이다.
"""

from __future__ import annotations

from features import extract_features

# accel_active_p90 컷오프 — 9세션(스타일당 3개) 실측 기반. 모듈 docstring 참고.
WEAK_NORMAL_CUTOFF = 0.71
NORMAL_STRONG_CUTOFF = 0.80


def classify_profile(samples: list[list[float]]) -> str:
    """세션 시계열(캘리브레이션 40~90초 분량)을 받아 프로필 이름을 반환.

    accel_active_p90(페달을 밟고 있을 때만의 90퍼센타일)이 9세션 중
    가장 안정적으로 갈렸던 지표라 1차 판정 기준으로 채택했다.
    """
    features = extract_features(samples)
    p90 = features["accel_active_p90"]
    if p90 < WEAK_NORMAL_CUTOFF:
        return "weak"
    if p90 < NORMAL_STRONG_CUTOFF:
        return "normal"
    return "strong"
