"""슬라이딩 윈도우 분할.

★ 중요: 실제 앱에서는 이 역할을 Kotlin(BleService)이 맡는다.
   BLE로 들어온 샘플을 Kotlin이 버퍼에 모았다가, 일정 크기 윈도우가
   차면 judge()에 통째로 넘긴다. 여기서는 PC에서 그 흐름을 흉내 낸다.

샘플 1개 = [accel, brake]
"""

from __future__ import annotations

from collections.abc import Iterator


def sliding_windows(
    samples: list[list[float]],
    window_size: int,
    stride: int,
) -> Iterator[list[list[float]]]:
    """samples를 window_size 길이로, stride 간격으로 잘라서 하나씩 내준다.

    window_size: 한 번 판정에 쓸 샘플 개수 (예: 50Hz * 1초 = 50)
    stride: 다음 윈도우까지 건너뛸 샘플 수 (예: 10 -> 0.2초마다 판정)
    """
    if window_size <= 0 or stride <= 0:
        raise ValueError("window_size, stride는 1 이상이어야 함")

    i = 0
    while i + window_size <= len(samples):
        yield samples[i : i + window_size]
        i += stride
