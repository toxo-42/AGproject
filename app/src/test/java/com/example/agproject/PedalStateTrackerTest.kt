package com.example.agproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PedalStateTrackerTest {

  @Test
  fun 밟는_순간에만_전환을_알린다() {
    val t = PedalStateTracker()
    assertNull(t.update(accel = 0.0, brake = 0.0))
    assertEquals(PedalState.ACCEL, t.update(accel = 0.3, brake = 0.0))
    // 계속 밟고 있으면 다시 알리지 않는다
    assertNull(t.update(accel = 0.5, brake = 0.0))
    assertNull(t.update(accel = 0.8, brake = 0.0))
  }

  @Test
  fun 경계값_근처_노이즈로는_깜빡이지_않는다() {
    val t = PedalStateTracker()
    assertEquals(PedalState.BRAKE, t.update(accel = 0.0, brake = 0.10))
    // PRESS_ON 아래로 내려가도 뗌 기준(0.05) 이상이면 밟은 상태 유지
    assertNull(t.update(accel = 0.0, brake = 0.07))
    assertNull(t.update(accel = 0.0, brake = 0.11))
    assertNull(t.update(accel = 0.0, brake = 0.06))
    // 뗌 기준 아래로 내려가야 뗀 것
    assertEquals(PedalState.NONE, t.update(accel = 0.0, brake = 0.04))
    // 뗀 상태에선 PRESS_ON 미만은 다시 밟은 게 아니다
    assertNull(t.update(accel = 0.0, brake = 0.08))
  }

  @Test
  fun 엑셀에서_브레이크로_옮기면_각각_전환된다() {
    val t = PedalStateTracker()
    assertEquals(PedalState.ACCEL, t.update(accel = 0.4, brake = 0.0))
    assertEquals(PedalState.NONE, t.update(accel = 0.0, brake = 0.0))
    assertEquals(PedalState.BRAKE, t.update(accel = 0.0, brake = 0.4))
  }

  @Test
  fun 동시에_밟으면_BOTH() {
    val t = PedalStateTracker()
    assertEquals(PedalState.ACCEL, t.update(accel = 0.4, brake = 0.0))
    assertEquals(PedalState.BOTH, t.update(accel = 0.4, brake = 0.2))
    assertEquals(PedalState.BRAKE, t.update(accel = 0.0, brake = 0.2))
  }

  @Test
  fun 브레이크_예압은_brake_low_기준이면_밟음으로_안_잡힌다() {
    // 실차 부착 시 발을 떼도 브레이크가 0.2 로 떠 있는 상황, 캘리브레이션 brake_low = 0.3
    val t = PedalStateTracker().apply { brakePressOn = 0.3 }
    assertNull(t.update(accel = 0.0, brake = 0.2))
    // 엑셀을 밟아도 BOTH 가 아니라 ACCEL
    assertEquals(PedalState.ACCEL, t.update(accel = 0.4, brake = 0.2))
    assertEquals(PedalState.NONE, t.update(accel = 0.0, brake = 0.2))
    // 실제로 브레이크를 밟으면 BRAKE, 예압 수준(0.25 미만)으로 돌아와야 뗀 것
    assertEquals(PedalState.BRAKE, t.update(accel = 0.0, brake = 0.5))
    assertNull(t.update(accel = 0.0, brake = 0.27))
    assertEquals(PedalState.NONE, t.update(accel = 0.0, brake = 0.2))
  }

  @Test
  fun reset_후_첫_밟음은_다시_전환으로_잡힌다() {
    val t = PedalStateTracker()
    t.update(accel = 0.4, brake = 0.0)
    t.reset()
    assertEquals(PedalState.ACCEL, t.update(accel = 0.4, brake = 0.0))
  }
}
