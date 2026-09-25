package com.example.agproject

import androidx.annotation.StringRes

/**
 * 안내형 재현 실험(개발자 학습 데이터 수집, 정차 중) 종류.
 * 횟수·동작 시간은 여기 값만 바꾸면 된다(2026-09-25 초기값, 사용자 합의: 20/10/10 + 자유 조작).
 *
 * @param id CSV trial_type 컬럼 값 — 한 번 쓰면 바꾸지 말 것(학습 스크립트가 이 값으로 구분)
 * @param label 동작(ACTION) 구간의 CSV label — 1=오조작, 0=정상. 준비·휴식 구간은 항상 0
 * @param actionMs 한 회차에서 페달을 조작하는 시간
 */
enum class TrialType(
  val id: String,
  val label: Int,
  @StringRes val nameRes: Int,
  @StringRes val instructionRes: Int,
  val defaultReps: Int,
  val actionMs: Long,
) {
  // 판정 지속시간(무장 후 약 2초 유지)을 넘길 수 있게 4초
  PANIC_SLAM("panic_slam", 1, R.string.trial_panic_slam, R.string.trial_panic_slam_desc, 20, 4_000L),
  HARD_NORMAL("hard_normal", 0, R.string.trial_hard_normal, R.string.trial_hard_normal_desc, 10, 3_000L),
  SLOW_DEEP("slow_deep", 0, R.string.trial_slow_deep, R.string.trial_slow_deep_desc, 10, 5_000L),
  // 실주행에서 흉내 내기 어려운 초보·난폭의 정상 페달 패턴을 정차 중 연기로 보충
  FREE_ACT("free_act", 0, R.string.trial_free_act, R.string.trial_free_act_desc, 1, 120_000L);

  /** 오조작 재현(감지돼야 정상)인지, 정상 조작(감지되면 오탐)인지 */
  val isMisop: Boolean get() = label == 1

  companion object {
    fun fromId(id: String?): TrialType? = entries.find { it.id == id }
  }
}

/** 한 회차 안의 구간. CSV trial_phase 컬럼 값으로도 쓰인다. */
enum class TrialPhase(val id: String) {
  READY("ready"),   // 카운트다운 — 발 올려놓고 대기
  ACTION("action"), // 조작 — 이 구간만 TrialType.label 이 찍힌다
  REST("rest"),     // 발 떼고 휴식
}

data class TrialStep(val rep: Int, val phase: TrialPhase, val durationMs: Long)

object TrialTiming {
  const val READY_MS = 3_000L
  const val REST_MS = 3_000L
  const val MIN_REPS = 1
  const val MAX_REPS = 50
}

/**
 * 실험 한 번의 전체 진행 순서를 만든다(rep 은 1부터). 회차마다 준비 → 조작 → 휴식이고,
 * 마지막 회차 뒤엔 휴식이 없다. 화면은 이 목록을 순서대로 재생하기만 한다.
 */
fun buildTrialSchedule(
  type: TrialType,
  reps: Int,
  readyMs: Long = TrialTiming.READY_MS,
  restMs: Long = TrialTiming.REST_MS,
): List<TrialStep> {
  require(reps >= 1) { "reps must be >= 1" }
  return (1..reps).flatMap { rep ->
    buildList {
      add(TrialStep(rep, TrialPhase.READY, readyMs))
      add(TrialStep(rep, TrialPhase.ACTION, type.actionMs))
      if (rep < reps) add(TrialStep(rep, TrialPhase.REST, restMs))
    }
  }
}

/** 실험 한 번에 걸리는 총 시간(준비·휴식 포함) — 설정 화면에 미리 보여주는 값 */
fun trialTotalDurationMs(type: TrialType, reps: Int): Long = buildTrialSchedule(type, reps).sumOf { it.durationMs }

/** 실험 결과 요약 — 감지된 회차 번호로 "N회 중 M회 감지/오탐"을 계산한다. */
data class TrialSummary(val type: TrialType, val reps: Int, val detectedReps: Set<Int>) {
  val detectedCount: Int get() = detectedReps.size

  /** 오조작 재현인데 감지 안 된 회차(놓침) */
  val missedReps: List<Int> get() = if (type.isMisop) (1..reps).filterNot { it in detectedReps } else emptyList()
}
