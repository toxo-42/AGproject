"""파이프라인 데모: 합성데이터 -> 슬라이딩 윈도우 -> judge -> 결과 출력.

실행:  uv run python src/run.py     (prototype 디렉터리에서)
"""

from __future__ import annotations

from judge import judge
from synth import SAMPLE_RATE_HZ, misoperation, normal_drive
from windowing import sliding_windows

WINDOW_SIZE = SAMPLE_RATE_HZ      # 1초치 (50샘플)
STRIDE = SAMPLE_RATE_HZ // 5      # 0.2초마다 판정 (10샘플)


def run_case(name: str, samples: list[list[float]]) -> None:
    print(f"\n=== {name} (총 {len(samples)}샘플) ===")
    for idx, window in enumerate(sliding_windows(samples, WINDOW_SIZE, STRIDE)):
        result = judge(window)
        t = idx * STRIDE / SAMPLE_RATE_HZ
        flag = "🚨 오조작" if result["misop"] else "정상"
        print(f"  t={t:4.1f}s  {flag}  score={result['score']:.2f}")


def main() -> None:
    run_case("정상 주행", normal_drive(seconds=4.0, seed=1))
    run_case("페달 오조작", misoperation(seconds=4.0, seed=1))


if __name__ == "__main__":
    main()
