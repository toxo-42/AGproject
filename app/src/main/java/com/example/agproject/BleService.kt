package com.example.agproject

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BleService : Service() {

  private val CHANNEL_ID = "BleServiceChannel"

  // 서비스가 생성될 때 1번 실행
  override fun onCreate() {
    super.onCreate()
    // 알림 채널 만들기 (안드로이드 8.0 이상 필수)
    createNotificationChannel()
  }

  // 서비스 시작 요청이 올 때마다 실행 (버튼 누를 때)
  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

    // 1. 알림(Notification) 생성
    val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("AG Guard")
      .setContentText("백그라운드 감시 중입니다...")
      .setSmallIcon(android.R.drawable.ic_dialog_info) // 기본 아이콘 사용
      .setOngoing(true) // 사용자가 알림을 못 지우게 설정
      .build()

    // 2. 포그라운드 서비스 시작 (이게 없으면 5초 뒤 앱 죽음)
    // 안드로이드 14 대응: type을 명시해야 함 (Manifest와 일치)
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(1, notification,
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(1, notification)
    }

    // TODO: 여기서 나중에 블루투스 스캔을 시작할 것입니다. (STEP 3)

    // 시스템에 의해 종료되어도 다시 살리지 않음 (필요시 START_STICKY 변경)
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    // 서비스 종료 시 정리할 것들
  }

  override fun onBind(intent: Intent?): IBinder? {
    return null
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val serviceChannel = NotificationChannel(
        CHANNEL_ID,
        "AG Guard Service Channel",
        NotificationManager.IMPORTANCE_LOW // 알림 소리 없이 조용히 뜨게 설정
      )
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(serviceChannel)
    }
  }
}