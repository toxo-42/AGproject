package com.example.agproject

/**
 * BleService 가 화면에 공개하는 상태 한 묶음 — 화면은 BleService.state 하나만 읽는다.
 *
 * 규칙(2026-10-28, 흩어져 있던 companion 전역 변수 6개를 묶음):
 *  - 쓰기는 BleService 만(updateState). 값 하나를 바꿀 때도 copy() 로 새 묶음을 만들어 통째로 바꿔 끼운다
 *    → 읽는 쪽은 항상 한 시점의 일관된 값을 본다(예: "감시 중"인데 판정 상태는 아직 옛 값인 중간 상태가 없다).
 *  - 무엇이 바뀌었는지는 방송으로 알린다(감시·판정 = ACTION_MONITOR_STATE, 임계값 = ACTION_THRESHOLDS_CHANGED,
 *    학습 진행 = ACTION_LEARNING_PROGRESS). 화면은 방송을 받으면 이 묶음을 다시 읽는다.
 *  - prefs 가 아니라 메모리에 두는 이유: 프로세스가 죽으면 기본값(STOPPED)으로 돌아가 "꺼졌는데 감시 중으로 보이는"
 *    낡은 값이 남지 않는다.
 */
data class ServiceState(
  /** 감시 상태 — 화면이 "지금 보호받고 있나"를 표시하는 단일 출처(MonitorState 참고). */
  val monitor: MonitorState = MonitorState.STOPPED,

  /** 판정이 실제로 돌고 있는지(JudgeStatus 참고). 감시 중(monitor.isActive)일 때만 의미가 있다. */
  val judge: JudgeStatus = JudgeStatus.ACTIVE,

  /**
   * 지금 판정에 실제로 적용 중인 임계값 JSON — 수집 그래프 임계선 등이 그릴 단일 출처.
   * 습관 학습·페르소나·캘리브레이션이 섞여 결정되므로 화면이 prefs 를 따로 읽어 재구성하면 어긋난다.
   * null = 서비스 꺼짐 또는 판정 안 함(영점 조절 중·판정 오류).
   */
  val appliedThresholdsJson: String? = null,

  /** 앱에 번들된 PC 학습 결과 요약(peob.learned.learned_info_json). 채택된 학습값이 없으면 null. 화면의 "AI 학습" 표시용. */
  val learnedInfoJson: String? = null,

  /**
   * 학습 진행 실시간 값 — 서비스 메모리의 최신값(prefs 저장은 1분 주기라 화면이 그걸 읽으면 늦다).
   * liveHabitStateJson = peob.habit HabitLearner.to_json(메인 "습관 학습 n/100"),
   * liveStyleReportJson = peob.style StyleTracker.report_json(진단 화면이 그대로 그림).
   * 서비스가 꺼지면 null → 화면은 prefs 저장본을 읽는다.
   */
  val liveHabitStateJson: String? = null,
  val liveStyleReportJson: String? = null,
)
