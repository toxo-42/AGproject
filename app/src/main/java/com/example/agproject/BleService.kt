package com.example.agproject

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat


class BleService : Service() {

  private val CHANNEL_ID = "BleServiceChannel"
  private val TAG = "BleService" // 로그 필터용 태그

  // 블루투스 관련 변수
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothLeScanner: BluetoothLeScanner? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()

    // 블루투스 매니저 초기화
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter
    bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
  }

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

    // 1. 알림 띄우기 (필수)
    val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("AG Guard")
      .setContentText("주변 블루투스 신호를 감시하고 있습니다...")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setOngoing(true)
      .build()

    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(1, notification,
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(1, notification)
    }

    // 2. 스캔 시작
    startBleScan()

    return START_NOT_STICKY
  }

  // 서비스 종료 시 (STOP 버튼)
  override fun onDestroy() {
    stopBleScan() // 스캔 중단 (배터리 절약)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? {
    return null
  }

  // --- 블루투스 스캔 로직 ---

  // 스캔 결과를 받는 콜백 (기기 찾기)
  private val scanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      super.onScanResult(callbackType, result)

      result?.let {
        // 찾은 기기 이름과 신호세기(RSSI)를 로그에 출력
        val deviceName = it.device.name ?: "Unknown"
        val deviceAddress = it.device.address
        val rssi = it.rssi

        Log.d(TAG, "발견됨! 이름: $deviceName | 주소: $deviceAddress | 신호: $rssi")
      }
    }

    override fun onScanFailed(errorCode: Int) {
      super.onScanFailed(errorCode)
      Log.e(TAG, "스캔 실패 에러 코드: $errorCode")
    }
  }

  @SuppressLint("MissingPermission")
  private fun startBleScan() {
    if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
      Log.e(TAG, "블루투스가 꺼져있거나 지원하지 않음")
      return
    }

    Log.d(TAG, "블루투스 스캔 시작...")
    bluetoothLeScanner?.startScan(scanCallback)
  }

  @SuppressLint("MissingPermission")
  private fun stopBleScan() {
    Log.d(TAG, "블루투스 스캔 종료")
    bluetoothLeScanner?.stopScan(scanCallback)
  }

  // --- 알림 채널 생성 ---
  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val serviceChannel = NotificationChannel(
        CHANNEL_ID,
        "AG Guard Service Channel",
        NotificationManager.IMPORTANCE_LOW
      )
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(serviceChannel)
    }
  }
}