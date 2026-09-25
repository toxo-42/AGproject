package com.example.agproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 경고 애니메이션 공식이 범위를 벗어나거나 끊기지 않는지 확인 */
class PedalGuideMotionTest {

  // 10분 동안 1/120초 간격 — 오래 떠 있어도 값이 튀지 않는지까지 본다
  private val times = (0 until 72_000).map { it / 120f }

  @Test
  fun 모든_값이_정해진_범위_안에_있다() {
    for (t in times) {
      for (k in 0 until 3) {
        val phase = PedalGuideMotion.ringPhase(t, k, 3)
        assertTrue("ringPhase t=$t", phase in 0f..1f)
        assertTrue("ringAlpha t=$t", PedalGuideMotion.ringAlpha(phase) in 0f..1f)
      }
      assertTrue("footMove t=$t", PedalGuideMotion.footMove(t) in 0f..1f)
      assertTrue("footAlpha t=$t", PedalGuideMotion.footAlpha(t) in 0f..1f)
      assertTrue("accelGlow t=$t", PedalGuideMotion.accelGlow(t) in 0.55f..1f)
      assertTrue("jitterX t=$t", PedalGuideMotion.jitterX(t) in -1.5f..1.5f)
      assertTrue("jitterY t=$t", PedalGuideMotion.jitterY(t) in -1.5f..1.5f)
    }
  }

  @Test
  fun 떨림은_프레임_사이에_순간이동하지_않는다() {
    // value noise 가 연속적이어야 "덜덜 튀는" 느낌이 아니라 "과열된 떨림"이 된다
    for (i in 1 until times.size) {
      val jump = kotlin.math.abs(PedalGuideMotion.jitterX(times[i]) - PedalGuideMotion.jitterX(times[i - 1]))
      assertTrue("t=${times[i]} jump=$jump", jump < 0.5f)
    }
  }

  @Test
  fun 화살표는_엑셀에서_출발해_브레이크에_도착한다() {
    assertEquals(0f, PedalGuideMotion.footMove(0f), 1e-4f)
    assertEquals(1f, PedalGuideMotion.footMove(1.6f * 0.7f), 1e-4f)
    assertEquals(1f, PedalGuideMotion.footAlpha(0.5f), 1e-4f)
  }

  @Test
  fun 박자는_광과민_기준보다_느리다() {
    // 초당 3회 초과 깜빡임 금지 → 한 박자가 1/3초보다 길어야 한다
    assertTrue(PedalGuideMotion.BEAT_SEC > 1f / 3f)
  }
}
