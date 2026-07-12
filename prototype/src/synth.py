"""200Hz 합성 센서 데이터 생성기 (실기기 샘플레이트와 동일하게 맞춤).

실기기 RAW 데이터가 아직 안 들어오므로, 정상 주행과 페달 오조작 패턴을
가짜로 만들어 판정 로직(judge)을 PC에서 먼저 굴려보기 위한 모듈이다.

센서 채널은 2개: 엑셀(accelerator), 브레이크(brake). 값 범위는 0.0~1.0
(0=안 밟음, 1=완전히 밟음)으로 가정한다. 실제 스케일은 펌웨어 RAW 포맷
확정되면 맞추면 된다.

샘플 1개 = [accel, brake]  (judge가 받는 형식과 동일)
"""

from __future__ import annotations

import math
import random

# 실기기는 50Hz 배치 × 4샘플 = 200Hz로 들어온다(BLE_RAW_스트림_규격.md).
# 이전엔 여기가 50Hz를 가정해 앱(BleService.WINDOW_SIZE=50 @ 200Hz = 0.25초)과
# 윈도우 길이가 4배 어긋나 있었다 -> 실기기와 동일하게 200Hz로 통일.
SAMPLE_RATE_HZ = 200


def _clamp(x: float) -> float:
    return max(0.0, min(1.0, x))


def normal_drive(seconds: float = 4.0, seed: int | None = None) -> list[list[float]]:
    """정상 주행: 엑셀/브레이크를 번갈아, 완만하게 밟는 패턴."""
    rng = random.Random(seed)
    n = int(seconds * SAMPLE_RATE_HZ)
    out: list[list[float]] = []
    for i in range(n):
        t = i / SAMPLE_RATE_HZ
        # 완만한 가속 (사인파) + 약간의 노이즈
        accel = _clamp(0.3 + 0.2 * math.sin(t) + rng.uniform(-0.03, 0.03))
        brake = _clamp(0.0 + rng.uniform(0.0, 0.05))
        out.append([accel, brake])
    return out


def misoperation(seconds: float = 4.0, seed: int | None = None) -> list[list[float]]:
    """페달 오조작: 브레이크를 밟으려다 엑셀을 급격히 끝까지 밟는 패턴.

    급발진 사고의 전형 - 멈추려는 의도인데 엑셀이 풀로 들어가고
    브레이크는 0에 가깝게 유지된다.
    """
    rng = random.Random(seed)
    n = int(seconds * SAMPLE_RATE_HZ)
    out: list[list[float]] = []
    spike_at = n // 2  # 중간 지점에서 오조작 발생
    for i in range(n):
        if i < spike_at:
            accel = _clamp(0.25 + rng.uniform(-0.03, 0.03))
            brake = _clamp(0.0 + rng.uniform(0.0, 0.05))
        else:
            # 급격히 엑셀 풀, 브레이크는 거의 0
            accel = _clamp(0.95 + rng.uniform(-0.05, 0.05))
            brake = _clamp(0.0 + rng.uniform(0.0, 0.03))
        out.append([accel, brake])
    return out
