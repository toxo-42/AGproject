"""앱이 기록한 주행 CSV(4Hz, 판정 윈도우당 1행)를 스키마 버전과 무관하게 하나의 구조로 읽는다.

CSV 형식은 수집 방식이 바뀌면서 여러 번 달라졌다(BleService.kt 의 CSV 주석 참고):
  - 2026-07: style(강/보통/약) 컬럼 + 수동 label 토글. accel_rate 는 07-18 이후 파일에만 있음
  - 2026-09-25: style → persona(beginner/normal/aggressive/none), trial_id/trial_type/trial_phase 추가
  - 2026-09-26: accel_median(윈도우 엑셀 중앙값) 추가
학습·분석 스크립트는 이 모듈의 Row/Session 만 보고, 컬럼 차이는 여기서만 흡수한다.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

# 옛 style 라벨 → persona id (2026-09-25 사용자 합의: 강=난폭, 보통=평범, 약=초보)
STYLE_TO_PERSONA = {"strong": "aggressive", "normal": "normal", "weak": "beginner"}
NO_PERSONA = "none"

# 재현 실험 종류 id (앱 Trial.kt 의 TrialType.id 와 같아야 한다)
TRIAL_PANIC = "panic_slam"
TRIAL_HARD_NORMAL = "hard_normal"
TRIAL_SLOW_DEEP = "slow_deep"
TRIAL_FREE_ACT = "free_act"

# CSV 1행 = 판정 윈도우 0.25초
ROWS_PER_SEC = 4.0


@dataclass(frozen=True)
class Row:
    accel: float              # 윈도우 마지막 샘플
    level: float              # 레벨 판정에 쓰는 값: accel_median 이 있으면 그것, 없으면 accel 로 근사
    brake: float
    rate: float | None        # 50ms 간격 엑셀 델타 최댓값(임계값과 무관한 값). 옛 CSV 엔 없음
    label: int                # 1 = 오조작(재현), 0 = 정상
    persona: str              # beginner/normal/aggressive/none
    trial_id: str | None      # 재현 실험 회차("시작시각-회차"), 실험 밖이면 None
    trial_type: str | None
    trial_phase: str | None   # ready/action/rest

    @property
    def is_normal_driving(self) -> bool:
        """정상 주행으로 볼 수 있는 행 — 실험 밖 정상 구간, 또는 자유 조작 실험(정상 연기)"""
        return self.label == 0 and (self.trial_type is None or self.trial_type == TRIAL_FREE_ACT)


@dataclass(frozen=True)
class Session:
    name: str
    rows: list[Row]
    has_label_column: bool    # False 면 옛 정상 주행 세션(라벨 개념 없음 → 전부 정상으로 취급)

    @property
    def has_rate(self) -> bool:
        return any(r.rate is not None for r in self.rows)


def _float(s: str | None) -> float | None:
    if s is None or s in ("", "none"):
        return None
    return float(s)


def _text(s: str | None) -> str | None:
    return None if s is None or s in ("", "none") else s


def persona_of(raw: dict) -> str:
    """CSV 원시 행의 페르소나 — persona 컬럼, 없으면 옛 style 컬럼을 매핑, 둘 다 없으면 NO_PERSONA"""
    persona = _text(raw.get("persona"))
    if persona is not None:
        return persona
    style = _text(raw.get("style"))
    return STYLE_TO_PERSONA.get(style or "", NO_PERSONA)


def parse_rows(raw_rows: list[dict]) -> list[Row]:
    """csv.DictReader 결과 → Row 목록. accel/brake 가 비어 있는 행은 건너뛴다."""
    rows: list[Row] = []
    for raw in raw_rows:
        accel = _float(raw.get("accel"))
        brake = _float(raw.get("brake"))
        if accel is None or brake is None:
            continue
        median = _float(raw.get("accel_median"))
        rows.append(Row(
            accel=accel,
            level=median if median is not None else accel,
            brake=brake,
            rate=_float(raw.get("accel_rate")),
            label=1 if raw.get("label") == "1" else 0,
            persona=persona_of(raw),
            trial_id=_text(raw.get("trial_id")),
            trial_type=_text(raw.get("trial_type")),
            trial_phase=_text(raw.get("trial_phase")),
        ))
    return rows


def load_session(path: Path) -> Session | None:
    """CSV 파일 하나를 읽는다. accel/brake 컬럼이 없거나 행이 없으면 None."""
    with path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fields = reader.fieldnames or []
        raw_rows = list(reader)
    if "accel" not in fields or "brake" not in fields:
        return None
    rows = parse_rows(raw_rows)
    if not rows:
        return None
    return Session(name=path.name, rows=rows, has_label_column="label" in fields)


def find_csv_files(paths: list[str]) -> list[Path]:
    files: list[Path] = []
    for p in paths:
        path = Path(p)
        if path.is_dir():
            files.extend(sorted(path.glob("*.csv")))
        elif path.suffix == ".csv":
            files.append(path)
    return files


def load_sessions(paths: list[str]) -> list[Session]:
    return [s for s in (load_session(p) for p in find_csv_files(paths)) if s is not None]


def default_data_dir() -> Path:
    """저장소 루트의 "수집 데이터" 디렉터리"""
    return Path(__file__).resolve().parents[2] / "수집 데이터"
