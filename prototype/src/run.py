"""파이프라인 데모: 합성데이터 -> 슬라이딩 윈도우 -> judge -> 결과 출력.

실행:  uv run python src/run.py     (prototype 디렉터리에서)

같은 주행 데이터를 프로필 3개로 각각 판정해, 프로필에 따라 경고가 실제로
달라지는지 확인한다.

synth(200Hz)와 BleService(WINDOW_SIZE=50 @ 200Hz)가 같은 샘플레이트를 쓰므로,
아래 WINDOW_SIZE=50은 앱과 동일하게 0.25초짜리 윈도우다. STRIDE도 WINDOW_SIZE와
같게 둬서 앱처럼 겹치지 않는(tumbling) 윈도우로 판정한다 — 여기서 튜닝한
high_ratio를 그대로 앱에 옮겨도 되는 상태.
"""

from __future__ import annotations

from judge import PROFILES, judge
from synth import SAMPLE_RATE_HZ, misoperation, normal_drive
from windowing import sliding_windows

WINDOW_SIZE = SAMPLE_RATE_HZ // 4   # 0.25초치 (50샘플) — BleService.WINDOW_SIZE와 동일
STRIDE = WINDOW_SIZE                # 겹치지 않는 윈도우 (앱의 tumbling 버퍼와 동일)


def run_case(name: str, samples: list[list[float]], profile: str, *, verbose: bool) -> int:
    """윈도우별로 판정하고, 오조작으로 잡힌 윈도우 수를 돌려준다."""
    if verbose:
        print(f"\n=== {name} / 프로필={profile} (총 {len(samples)}샘플) ===")
    misop_count = 0
    for idx, window in enumerate(sliding_windows(samples, WINDOW_SIZE, STRIDE)):
        result = judge(window, profile)
        if result["misop"]:
            misop_count += 1
        if verbose:
            t = idx * STRIDE / SAMPLE_RATE_HZ
            flag = "🚨 오조작" if result["misop"] else "정상"
            print(f"  t={t:4.1f}s  {flag}  score={result['score']:.2f}")
    return misop_count


def main() -> None:
    cases = {
        "정상 주행": normal_drive(seconds=4.0, seed=1),
        "페달 오조작": misoperation(seconds=4.0, seed=1),
    }

    # 기본 프로필은 윈도우별 상세 출력, 나머지는 집계만.
    summary: dict[tuple[str, str], int] = {}
    for case_name, samples in cases.items():
        for profile in PROFILES:
            verbose = profile == "normal"
            summary[(case_name, profile)] = run_case(
                case_name, samples, profile, verbose=verbose
            )

    # 프로필별 민감도 비교 — strong 일수록 덜 잡혀야(오경보가 적어야) 한다.
    total = len(list(sliding_windows(next(iter(cases.values())), WINDOW_SIZE, STRIDE)))
    print(f"\n=== 요약: 오조작으로 판정된 윈도우 수 (전체 {total}개 중) ===")
    for case_name in cases:
        cells = "  ".join(f"{p}={summary[(case_name, p)]}" for p in PROFILES)
        print(f"  {case_name}: {cells}")


if __name__ == "__main__":
    main()
