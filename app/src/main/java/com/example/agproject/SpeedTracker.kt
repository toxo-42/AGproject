package com.example.agproject

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

// GPS 차량 속도 추적 (Phase F-2: 운전 성향 진단용 — 페달 입력 대비 차가 실제로 어떻게 움직였나).
// BleService 가 소유하고, CSV 한 행을 쓸 때마다 current() 로 최신 속도를 읽는다.
// ⚠️ 좌표(위도/경도)는 의도적으로 보관하지 않는다 — 진단엔 속도만 필요하고, 위치 기록은
//    프라이버시 정책(§5 "아직 정해야 할 것") 확정 전이라 남기지 않는다.
class SpeedTracker(private val context: Context) {

  data class Speed(val kmh: Double, val accuracyKmh: Double?)

  private val tag = "SpeedTracker"
  private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

  // 위치 콜백은 메인 루퍼, current() 는 BLE 콜백 스레드에서 읽는다.
  @Volatile private var lastFix: Location? = null
  private var started = false

  // 람다 대신 object: API 29 이하 기기에선 onStatusChanged 등이 default 메서드가 아니라서
  // 구현이 없으면 호출 시 AbstractMethodError 가 난다.
  private val listener = object : LocationListener {
    override fun onLocationChanged(location: Location) { lastFix = location }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { lastFix = null }
    @Deprecated("API 29 이하 호환용")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
  }

  fun hasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  // 이미 시작했으면 아무것도 안 한다(onStartCommand 메인 경로가 여러 번 불릴 수 있음).
  // 권한이 없거나 GPS 가 꺼져 있어도 판정·로깅은 계속돼야 하므로 실패는 로그만 남긴다.
  fun start() {
    if (started) return
    if (!hasPermission()) {
      Log.w(tag, "위치 권한 없음 → 속도 기록 생략")
      return
    }
    try {
      locationManager.requestLocationUpdates(
        LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, Looper.getMainLooper()
      )
      started = true
      Log.i(tag, "GPS 속도 추적 시작")
    } catch (e: Exception) {
      Log.w(tag, "GPS 속도 추적 시작 실패: ${e.message}")
    }
  }

  fun stop() {
    if (!started) return
    locationManager.removeUpdates(listener)
    started = false
    lastFix = null
  }

  // 최신 속도. 수신된 적 없거나, 오래됐거나(터널 등), 속도 정보가 없는 fix 면 null.
  fun current(): Speed? {
    val fix = lastFix ?: return null
    val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
    if (ageMs > STALE_MS || !fix.hasSpeed()) return null
    val accuracy = if (fix.hasSpeedAccuracy()) fix.speedAccuracyMetersPerSecond * MS_TO_KMH else null
    return Speed(kmh = fix.speed * MS_TO_KMH, accuracyKmh = accuracy)
  }

  companion object {
    private const val UPDATE_INTERVAL_MS = 1000L   // GPS 칩 대부분이 1Hz — 더 빨리 요청해도 의미 없음
    private const val STALE_MS = 3000L             // 3회 연속 fix 를 못 받으면 "모름"으로 기록
    private const val MS_TO_KMH = 3.6
  }
}
