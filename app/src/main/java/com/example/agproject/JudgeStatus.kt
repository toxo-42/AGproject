package com.example.agproject

/**
 * 오조작 판정이 지금 실제로 돌고 있는지 — 연결 상태(MonitorState)와는 독립이다(재연결 중이면서 판정 오류일 수 있다).
 * 값은 BleService.judgeStatus 에서 읽고, 바뀔 때마다 BleService.ACTION_MONITOR_STATE 가 방송된다.
 *
 * 만든 이유: Python 판정 호출이 실패하면 경고가 하나도 안 뜨는데 화면은 "감시 중"으로 보였다(2026-10-28 분석).
 * 안전 기능이 조용히 꺼지는 게 가장 위험한 실패라, 꺼졌으면 화면·알림에 드러낸다.
 */
enum class JudgeStatus {
  /** 판정 중 */
  ACTIVE,

  /** 브레이크 영점 조절 중이라 일부러 판정을 멈춤(정상) */
  CALIBRATING,

  /** 판정 상태기계를 못 만들었거나 판정 호출이 계속 실패 — 오조작 경고가 동작하지 않는다 */
  FAILED;

  companion object {
    // 판정 호출이 이만큼 연속 실패하면 FAILED(윈도우 0.25초 × 4 = 1초). 한 번의 일시적 실패로는 알리지 않는다 — 임시값.
    const val FAILURE_LIMIT = 4

    fun of(calibrating: Boolean, hasDetector: Boolean, consecutiveFailures: Int): JudgeStatus = when {
      calibrating -> CALIBRATING
      !hasDetector -> FAILED
      consecutiveFailures >= FAILURE_LIMIT -> FAILED
      else -> ACTIVE
    }
  }
}
