package com.example.agproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 안내형 재현 실험의 진행 순서·요약 계산 확인 */
class TrialScheduleTest {

  @Test
  fun 회차마다_준비_조작_휴식이고_마지막_회차엔_휴식이_없다() {
    val steps = buildTrialSchedule(TrialType.PANIC_SLAM, reps = 3)
    val phases = steps.map { it.rep to it.phase }
    assertEquals(
      listOf(
        1 to TrialPhase.READY, 1 to TrialPhase.ACTION, 1 to TrialPhase.REST,
        2 to TrialPhase.READY, 2 to TrialPhase.ACTION, 2 to TrialPhase.REST,
        3 to TrialPhase.READY, 3 to TrialPhase.ACTION,
      ),
      phases,
    )
  }

  @Test
  fun 조작_구간_길이는_실험_종류를_따른다() {
    for (type in TrialType.entries) {
      val action = buildTrialSchedule(type, reps = 1).single { it.phase == TrialPhase.ACTION }
      assertEquals(type.actionMs, action.durationMs)
    }
  }

  @Test
  fun 한_회만_하면_준비와_조작만_있다() {
    val steps = buildTrialSchedule(TrialType.FREE_ACT, reps = 1)
    assertEquals(listOf(TrialPhase.READY, TrialPhase.ACTION), steps.map { it.phase })
  }

  @Test(expected = IllegalArgumentException::class)
  fun 횟수가_0이면_거부한다() {
    buildTrialSchedule(TrialType.PANIC_SLAM, reps = 0)
  }

  @Test
  fun 오조작_재현은_놓친_회차를_알려준다() {
    val summary = TrialSummary(TrialType.PANIC_SLAM, reps = 5, detectedReps = setOf(1, 2, 4))
    assertEquals(3, summary.detectedCount)
    assertEquals(listOf(3, 5), summary.missedReps)
  }

  @Test
  fun 정상_조작은_놓침_개념이_없다() {
    val summary = TrialSummary(TrialType.HARD_NORMAL, reps = 5, detectedReps = setOf(2))
    assertEquals(1, summary.detectedCount)
    assertTrue(summary.missedReps.isEmpty())
  }

  @Test
  fun 총_시간은_준비_조작_휴식을_모두_더한_값이다() {
    // 자유 조작 3회: (준비 3초 + 조작 120초) × 3 + 휴식 3초 × 2 = 375초
    assertEquals(375_000L, trialTotalDurationMs(TrialType.FREE_ACT, reps = 3))
  }

  @Test
  fun 실험_종류_id는_중복되지_않는다() {
    val ids = TrialType.entries.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
  }
}
