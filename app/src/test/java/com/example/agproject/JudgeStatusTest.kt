package com.example.agproject

import org.junit.Assert.assertEquals
import org.junit.Test

class JudgeStatusTest {

  @Test
  fun 영점_조절_중이면_다른_조건과_무관하게_CALIBRATING() {
    // 영점 조절 중엔 detector 를 일부러 비운다 — 오류로 보면 안 된다
    assertEquals(JudgeStatus.CALIBRATING, JudgeStatus.of(calibrating = true, hasDetector = false, consecutiveFailures = 0))
    assertEquals(JudgeStatus.CALIBRATING, JudgeStatus.of(calibrating = true, hasDetector = true, consecutiveFailures = 99))
  }

  @Test
  fun 상태기계가_없으면_FAILED() {
    assertEquals(JudgeStatus.FAILED, JudgeStatus.of(calibrating = false, hasDetector = false, consecutiveFailures = 0))
  }

  @Test
  fun 연속_실패가_기준에_닿아야_FAILED() {
    val below = JudgeStatus.FAILURE_LIMIT - 1
    assertEquals(JudgeStatus.ACTIVE, JudgeStatus.of(calibrating = false, hasDetector = true, consecutiveFailures = below))
    assertEquals(JudgeStatus.FAILED, JudgeStatus.of(calibrating = false, hasDetector = true, consecutiveFailures = JudgeStatus.FAILURE_LIMIT))
  }

  @Test
  fun 정상이면_ACTIVE() {
    assertEquals(JudgeStatus.ACTIVE, JudgeStatus.of(calibrating = false, hasDetector = true, consecutiveFailures = 0))
  }
}
