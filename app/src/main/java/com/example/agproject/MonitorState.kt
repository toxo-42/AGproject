package com.example.agproject

/**
 * 오조작 감시 서비스(BleService)의 현재 상태. 화면은 이 값만 보고 "지금 보호받고 있나"를 표시한다.
 * 값은 BleService.monitorState 에서 읽고, 바뀔 때마다 BleService.ACTION_MONITOR_STATE 가 방송된다.
 */
enum class MonitorState {
  /** 서비스 꺼짐 — 감시 안 함 */
  STOPPED,

  /** 서비스 켜짐, 기기 검색·연결·서비스 탐색 중 */
  CONNECTING,

  /** 연결 완료 + 모듈 확인됨 — 데이터 수신·판정 중 (캘리브레이션 여부는 별도) */
  MONITORING,

  /** 연결이 끊겨 다시 찾는 중 */
  RECONNECTING,

  /** 연결은 됐지만 우리 모듈이 아님(UUID 불일치) — 서비스는 곧 스스로 종료 */
  WRONG_DEVICE;

  /** 사용자가 감시를 "켜 둔" 상태인지 (시작/중지 버튼 표시 기준) */
  val isActive: Boolean
    get() = this == CONNECTING || this == MONITORING || this == RECONNECTING
}
