"""페달 오조작 판정 — ★ 이 파일이 Chaquopy로 그대로 이식된다.

계약(Contract):
  입력  samples: list[[accel, brake], ...]   # Kotlin이 윈도우째로 넘김
  출력  dict {"misop": bool, "score": float, "reason": str}

Kotlin <-> Python 경계를 단순하게 유지하려고:
  - 입력은 중첩 리스트(기본 자료형)만. numpy 객체를 인자로 받지 않는다.
  - 출력은 JSON 직렬화 가능한 dict. (Chaquopy에선 judge_json 사용 권장)

지금 로직은 '예시 휴리스틱'이다. 실제 판정 알고리즘은 여기만 갈아끼우면
PC/앱 양쪽에 동시에 반영된다.
"""

from __future__ import annotations

import json

# 판정 임계값 (튜닝 대상) ----------------------------------------------------
ACCEL_HIGH = 0.85     # 엑셀이 이 이상이면 '깊게 밟음'
BRAKE_LOW = 0.10      # 브레이크가 이 이하면 '거의 안 밟음'
HIGH_RATIO = 0.5      # 윈도우 내 '엑셀 깊게+브레이크 안밟음' 비율이 이 이상이면 오조작


def judge(samples: list[list[float]]) -> dict:
    """윈도우 하나를 받아 오조작 여부를 판정.

    예시 휴리스틱: 윈도우 안에서 '엑셀을 깊게 밟으면서 브레이크는 거의
    안 밟은' 샘플 비율이 임계값을 넘으면 오조작으로 본다.
    """
    if not samples:
        return {"misop": False, "score": 0.0, "reason": "empty"}

    hits = 0
    for accel, brake in samples:
        if accel >= ACCEL_HIGH and brake <= BRAKE_LOW:
            hits += 1

    score = hits / len(samples)
    misop = score >= HIGH_RATIO
    reason = (
        f"엑셀>={ACCEL_HIGH} & 브레이크<={BRAKE_LOW} 비율 {score:.2f}"
        f" ({'>=' if misop else '<'} {HIGH_RATIO})"
    )
    return {"misop": misop, "score": round(score, 3), "reason": reason}


def judge_json(samples_json: str) -> str:
    """Chaquopy 경계용: Kotlin이 JSON 문자열로 넘긴 윈도우를 판정해 JSON 문자열로 반환.

    입출력을 모두 JSON '문자열'로 주고받는 이유:
      Chaquopy는 Java의 ArrayList 를 Python list 로 자동 변환하지 않아서,
      윈도우(중첩 리스트)를 그대로 넘기면 'ArrayList object is not iterable' 이 난다.
      문자열로 직렬화해 넘기면 Java<->Python 컬렉션 변환 문제를 완전히 피한다.

    Kotlin 쪽:
      val samplesJson = JSONArray(...).toString()       // "[[0.95,0.0], ...]"
      val resultJson = py.callAttr("judge_json", samplesJson).toString()
    """
    samples = json.loads(samples_json)
    return json.dumps(judge(samples), ensure_ascii=False)
