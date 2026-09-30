package com.example.agproject

/** 지금 밟고 있는 페달. 페달 테두리 표시(PedalEdgeOverlay)의 입력이다. */
enum class PedalState { NONE, ACCEL, BRAKE, BOTH }

/**
 * 200Hz 샘플을 받아 페달 상태가 "바뀌는 순간"만 알려주는 트래커. 안드로이드 API에 의존하지 않는다.
 *
 * - 밟음 기준 PRESS_ON(0.10)은 backend features.py 의 ACCEL_ACTIVE_THRESHOLD /
 *   SIMULTANEOUS_PRESS_THRESHOLD 와 같은 값 — 판정 로직과 "밟았다"의 정의를 맞춘다.
 * - 뗌 기준을 밟음 기준보다 HYSTERESIS(0.05) 낮게 둔다: 경계값 근처 센서 노이즈로 상태가
 *   켜졌다 꺼졌다 반복하며 테두리가 깜빡이는 것을 막는다.
 * - 브레이크 밟음 기준은 캘리브레이션의 brake_low 로 바꿀 수 있다(brakePressOn). 실차 부착 시 발을 떼도
 *   브레이크가 ~0.2 로 떠 있는 예압(offset) 때문에 고정 0.10 이면 늘 "브레이크 밟음"으로 잡혔다.
 *   brake_low 는 판정도 "이 이하면 브레이크 안 밟음"으로 쓰는 값이라 테두리와 판정의 기준이 같아진다.
 *
 * BleService 의 RAW 수신 흐름에서만 호출한다 — sampleBuffer 와 같은 가정으로 내부 동기화는 없다.
 */
class PedalStateTracker {
  private var accelPressed = false
  private var brakePressed = false
  var state: PedalState = PedalState.NONE
    private set

  /** 브레이크 밟음 기준. 캘리브레이션이 바뀔 때 다른 스레드에서 갱신될 수 있어 @Volatile. */
  @Volatile var brakePressOn: Double = PRESS_ON

  /** 샘플 하나를 반영하고, 상태가 바뀌었으면 새 상태를, 아니면 null 을 돌려준다. */
  fun update(accel: Double, brake: Double): PedalState? {
    accelPressed = isPressed(accelPressed, accel, PRESS_ON)
    brakePressed = isPressed(brakePressed, brake, brakePressOn)
    val next = when {
      accelPressed && brakePressed -> PedalState.BOTH
      accelPressed -> PedalState.ACCEL
      brakePressed -> PedalState.BRAKE
      else -> PedalState.NONE
    }
    if (next == state) return null
    state = next
    return next
  }

  /** 연결이 끊기는 등 스트림이 끊겼을 때 — 다음 연결의 첫 밟음이 다시 "전환"으로 잡히게 한다. */
  fun reset() {
    accelPressed = false
    brakePressed = false
    state = PedalState.NONE
  }

  private fun isPressed(wasPressed: Boolean, value: Double, pressOn: Double): Boolean =
    if (wasPressed) value >= pressOn - HYSTERESIS else value >= pressOn

  companion object {
    const val PRESS_ON = 0.10
    const val HYSTERESIS = 0.05
  }
}
