"""오조작 라벨 데이터로 로지스틱 회귀 1차 실험 — Phase D 선행 실험.

지금 확정된 임계값 기반 judge.py 를 대체하려는 게 아니라, "라벨 데이터가 쌓이면 학습
기반으로 넘어갈 수 있는가"를 지금 있는 최소 데이터로 먼저 찔러보는 실험이다.

⚠️ 데이터가 매우 작다 — label 컬럼이 있고 실제로 오조작을 재현한 세션이 2개뿐이고,
전부 개발자 1인이 같은 날 인위적으로 재현한 것이다. 여기서 나온 수치는 "진짜 성능"이
아니라 "파이프라인이 굴러가는지, 방향이 말이 되는지" 확인 수준으로만 봐야 한다
(§진행상황_및_로드맵.md Phase D — 후순위, 아직 착수 전인 이유가 바로 이 데이터 부족).

sklearn/numpy 미사용 — prototype/pyproject.toml 의 "의존성 없음"(Chaquopy 이식 리스크
최소화) 확정 결정과 같은 기조. 나중에 학습된 가중치를 그대로 judge.py/Chaquopy 추론
경로에 옮기려면 순수 Python 구현이어야 이식이 바로 된다.

feature: accel, brake, accel_delta_1(직전 행 대비, 0.25초 델타), accel_delta_4(4행 전
대비, 1초 델타) — judge.py의 trigger(변화율) 개념을 로지스틱 회귀가 스스로 가중치를
배우게 하려는 의도로 같은 계열의 신호를 그대로 넣었다. CSV가 200Hz 원시가 아니라
4Hz(판정 윈도우당 1행)라 judge.py의 RATE_LAG_SAMPLES(50ms)만큼 촘촘한 델타는 여기선
못 만든다 — 대신 0.25초/1초 델타로 근사한다.

실행:  uv run python src/train_logreg.py [csv ...]
       인자 없으면 ../../수집 데이터 에서 accel/brake가 있는 CSV를 전부 모은다.

⚠️ (2026-07-18 수정) 처음엔 label 컬럼이 있는(=오조작 재현) 세션만 썼는데, 그러면 모델이
"엑셀 재현 세션에서 높았던 것 = 오조작"만 배우고 style 세션(수집 데이터 (1)/(2)/(3).csv,
strong 스타일의 정상적으로 강한 가속 포함)은 한 번도 label=0으로 못 봤다 — precision이
낮았던 진짜 원인이 브레이크 변주 부족이 아니라 이 negative 예시 부족이었을 가능성이 큼
(사용자 지적: 실주행에서 엑셀·브레이크 동시 조작은 애초에 안 일어나서 그쪽 대조군은
비현실적). → label 컬럼이 없는 세션(style/캘리브레이션)은 오조작 재현을 안 한 정상
주행이므로 전부 label=0으로 취급해 학습에 포함시킨다.
"""

from __future__ import annotations

import csv
import math
import random
import sys
from pathlib import Path

FEATURE_NAMES = ["accel", "brake", "accel_delta_1", "accel_delta_4"]


def _parse_float(s: str | None) -> float | None:
    if s in (None, "none", ""):
        return None
    return float(s)


def load_session(path: Path) -> tuple[list[list[float]], list[int]] | None:
    """CSV 한 개를 (X, y)로 변환.

    label 컬럼이 있으면 그 값을 그대로 쓴다(오조작 재현 세션). label 컬럼이 없는 세션
    (style 라벨링, 순수 캘리브레이션)은 오조작 재현을 애초에 안 한 정상 주행이므로 전부
    label=0인 negative 예시로 취급한다 — 위 모듈 docstring 2026-07-18 수정 참고.
    """
    with path.open(encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if not rows or "accel" not in rows[0] or "brake" not in rows[0]:
        return None

    accels = [_parse_float(r.get("accel")) for r in rows]
    brakes = [_parse_float(r.get("brake")) for r in rows]
    if "label" in rows[0]:
        labels = [1 if r.get("label") == "1" else 0 for r in rows]
    else:
        labels = [0] * len(rows)

    X: list[list[float]] = []
    y: list[int] = []
    for i in range(len(rows)):
        if accels[i] is None or brakes[i] is None:
            continue
        d1 = accels[i] - accels[i - 1] if i >= 1 and accels[i - 1] is not None else 0.0
        d4 = accels[i] - accels[i - 4] if i >= 4 and accels[i - 4] is not None else 0.0
        X.append([accels[i], brakes[i], d1, d4])
        y.append(labels[i])
    return X, y


def standardize(X: list[list[float]]) -> tuple[list[list[float]], list[float], list[float]]:
    """평균 0/표준편차 1로 스케일링(경사하강 수렴 안정화). mean/std를 같이 돌려줘서
    검증/추론 시 같은 변환을 재사용한다(학습 데이터 기준으로만 계산 — 검증 데이터로
    스케일을 다시 재면 정보 누설)."""
    n = len(X)
    dims = len(X[0])
    means = [sum(row[j] for row in X) / n for j in range(dims)]
    stds = []
    for j in range(dims):
        var = sum((row[j] - means[j]) ** 2 for row in X) / n
        stds.append(math.sqrt(var) or 1.0)
    Xs = [[(row[j] - means[j]) / stds[j] for j in range(dims)] for row in X]
    return Xs, means, stds


def apply_scale(X: list[list[float]], means: list[float], stds: list[float]) -> list[list[float]]:
    return [[(row[j] - means[j]) / stds[j] for j in range(len(row))] for row in X]


def sigmoid(z: float) -> float:
    if z < -60:
        return 0.0
    if z > 60:
        return 1.0
    return 1.0 / (1.0 + math.exp(-z))


def train_logreg(
    X: list[list[float]], y: list[int], lr: float = 0.1, epochs: int = 2000, l2: float = 0.01, seed: int = 0
) -> tuple[list[float], float]:
    """배치 경사하강법. label=1이 소수라 class weight로 불균형을 보정한다."""
    n = len(X)
    dims = len(X[0])
    pos = sum(y)
    neg = n - pos
    w_pos = n / (2 * pos) if pos else 1.0
    w_neg = n / (2 * neg) if neg else 1.0

    rng = random.Random(seed)
    w = [rng.uniform(-0.01, 0.01) for _ in range(dims)]
    b = 0.0

    for _ in range(epochs):
        grad_w = [0.0] * dims
        grad_b = 0.0
        for xi, yi in zip(X, y):
            z = sum(w[j] * xi[j] for j in range(dims)) + b
            p = sigmoid(z)
            weight = w_pos if yi == 1 else w_neg
            err = (p - yi) * weight
            for j in range(dims):
                grad_w[j] += err * xi[j]
            grad_b += err
        for j in range(dims):
            w[j] -= lr * (grad_w[j] / n + l2 * w[j])
        b -= lr * (grad_b / n)
    return w, b


def evaluate(X: list[list[float]], y: list[int], w: list[float], b: float, threshold: float = 0.5) -> dict:
    tp = fp = tn = fn = 0
    for xi, yi in zip(X, y):
        z = sum(w[j] * xi[j] for j in range(len(w))) + b
        pred = 1 if sigmoid(z) >= threshold else 0
        if pred == 1 and yi == 1:
            tp += 1
        elif pred == 1 and yi == 0:
            fp += 1
        elif pred == 0 and yi == 0:
            tn += 1
        else:
            fn += 1
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    accuracy = (tp + tn) / len(y) if y else 0.0
    return {"n": len(y), "tp": tp, "fp": fp, "tn": tn, "fn": fn,
            "precision": round(precision, 3), "recall": round(recall, 3), "accuracy": round(accuracy, 3)}


def find_csv_files(paths: list[str]) -> list[Path]:
    files: list[Path] = []
    for p in paths:
        path = Path(p)
        if path.is_dir():
            files.extend(sorted(path.glob("*.csv")))
        elif path.suffix == ".csv":
            files.append(path)
    return files


def main() -> None:
    args = sys.argv[1:]
    if not args:
        default_dir = Path(__file__).resolve().parents[2] / "수집 데이터"
        args = [str(default_dir)]

    sessions: list[tuple[str, list[list[float]], list[int]]] = []
    for path in find_csv_files(args):
        loaded = load_session(path)
        if loaded is None:
            continue
        X, y = loaded
        sessions.append((path.name, X, y))
        print(f"{path.name}: {len(y)}행 (label=1: {sum(y)}, label=0: {len(y) - sum(y)})")

    if not sessions:
        print("label=1이 있는 CSV를 못 찾았습니다.")
        return

    if len(sessions) < 2:
        print("\n⚠️ 라벨 있는 세션이 1개뿐이라 세션 단위 검증(leave-one-session-out)을 못 합니다.")
        print("   오조작 재현 세션을 하나 더 모으면 서로 교차검증할 수 있습니다.")
        return

    # 세션 단위 leave-one-session-out — 같은 재현 이벤트의 연속된 행이 학습/검증에
    # 동시에 섞이면(무작위 행 단위 분할) 사실상 답을 보고 맞히는 leakage가 생긴다.
    # 세션째로 통째로 빼는 게 훨씬 정직한 "본 적 없는 상황에서도 되는가" 검증이다.
    print("\n=== Leave-one-session-out 교차검증 ===")
    for i, (test_name, X_test_raw, y_test) in enumerate(sessions):
        train_X: list[list[float]] = []
        train_y: list[int] = []
        for j, (_, X_j, y_j) in enumerate(sessions):
            if j == i:
                continue
            train_X.extend(X_j)
            train_y.extend(y_j)

        X_train, means, stds = standardize(train_X)
        X_test = apply_scale(X_test_raw, means, stds)
        w, b = train_logreg(X_train, train_y)

        print(f"\n검증 세션 = {test_name} (나머지로 학습, n_train={len(train_y)})")
        print(f"  가중치({', '.join(FEATURE_NAMES)}): {[round(v, 3) for v in w]}  절편={round(b, 3)}")
        print(f"  결과: {evaluate(X_test, y_test, w, b)}")

    # 최종 계수 — 지금 있는 데이터 전부로 다시 학습(참고용, 아직 배포/적용 대상 아님).
    all_X = [x for _, X, _ in sessions for x in X]
    all_y = [v for _, _, y in sessions for v in y]
    X_all, means, stds = standardize(all_X)
    w, b = train_logreg(X_all, all_y)
    print(f"\n=== 전체 데이터로 재학습한 최종 계수 (참고용, 아직 미배포) ===")
    print(f"  가중치({', '.join(FEATURE_NAMES)}): {[round(v, 4) for v in w]}")
    print(f"  절편: {round(b, 4)}")
    print(f"  표준화 평균: {[round(v, 4) for v in means]}")
    print(f"  표준화 표준편차: {[round(v, 4) for v in stds]}")
    print("\n⚠️ 세션 2개, 개발자 1인, 인위적 재현 데이터입니다. 방향성 확인 이상의 의미를")
    print("   두지 마세요 — 실제 judge.py 대체는 세션이 훨씬 더 쌓인 뒤에 재검토할 것.")


if __name__ == "__main__":
    main()
