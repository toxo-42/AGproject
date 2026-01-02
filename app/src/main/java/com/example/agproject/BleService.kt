package com.example.agproject

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class BleService : Service(), TextToSpeech.OnInitListener {

  private val CHANNEL_ID = "BleServiceChannel"
  private val TAG = "BleService"

  // 👇 [중요] nRF Connect에서 확인한 주소로 매번 바꿔주세요 (테스트용)
  private val TARGET_ADDRESS = "DA:C9:40:53:C8:06"
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothLeScanner: BluetoothLeScanner? = null
  private var bluetoothGatt: BluetoothGatt? = null
  private var tts: TextToSpeech? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()

    // 블루투스 초기화
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter
    bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    // TTS(음성 안내) 준비
    tts = TextToSpeech(this, this)
  }

  // TTS 초기화 완료 시 호출되는 함수
  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      val result = tts?.setLanguage(Locale.KOREAN)
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.e(TAG, "TTS: 한국어 지원 안 함")
      } else {
        Log.i(TAG, "TTS: 음성 안내 준비 완료")

        // 👇 [추가] 준비되자마자 바로 말해보기 (테스트용)
        speakOut("음성 안내 시스템이 정상 작동 중입니다.")
      }
    }
  }

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 1. 알림 띄우기
    startForegroundServiceNotification("시스템 가동 중... 기기를 찾는 중")

    // 2. 스캔 시작
    startTargetScan()

    return START_NOT_STICKY
  }

  override fun onDestroy() {
    disconnectGatt()
    if (tts != null) {
      tts?.stop()
      tts?.shutdown()
    }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? {
    return null
  }

  // --- 1. 스캔 (Targeted Scan) ---
  @SuppressLint("MissingPermission")
  private fun startTargetScan() {
    if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return

    Log.d(TAG, "🎯 타겟 스캔 시작: $TARGET_ADDRESS")

    val scanSettings = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .build()

    // 주소 필터 (이름 필터 대신 이걸 씁니다)
    val targetFilter = ScanFilter.Builder()
      .setDeviceAddress(TARGET_ADDRESS)
      .build()

    val filters = mutableListOf(targetFilter)

    try {
      bluetoothLeScanner?.startScan(filters, scanSettings, scanCallback)
    } catch (e: Exception) {
      Log.e(TAG, "스캔 에러: ${e.message}")
    }
  }

  private val scanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result?.let {
        Log.i(TAG, "🔥 기기 발견! 연결 시도 중...")

        // 스캔 중단 (연결을 위해)
        bluetoothLeScanner?.stopScan(this)

        // 연결 시도
        connectToDevice(it.device)
      }
    }

    override fun onScanFailed(errorCode: Int) {
      Log.e(TAG, "❌ 스캔 실패: $errorCode")
    }
  }

  // --- 2. 연결 (Gatt Connect) ---
  @SuppressLint("MissingPermission")
  private fun connectToDevice(device: BluetoothDevice) {
    // 자동 연결 (autoConnect = false로 해야 빨리 붙음)
    bluetoothGatt = device.connectGatt(this, false, gattCallback)
  }

  // --- 3. 연결 상태 콜백 ---
  private val gattCallback = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        Log.i(TAG, "✅ [성공] 기기와 연결되었습니다!")

        // 알림 업데이트
        updateNotification("기기 연결됨 - 모니터링 중")

        // 음성 피드백 (오류 신호 받았다고 가정)
        speakOut("경고! 오류 신호가 감지되었습니다. 브레이크를 확인하세요.")

      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        Log.w(TAG, "🔌 연결 끊김. 다시 스캔합니다.")
        startTargetScan() // 재연결 시도
      }
    }
  }

  // --- 보조 기능 (TTS, 알림) ---
  private fun speakOut(text: String) {
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ID")
    Log.d(TAG, "🗣️ 음성 출력: $text")
  }

  private fun startForegroundServiceNotification(content: String) {
    val notification = createNotification(content)
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(1, notification)
    }
  }

  private fun updateNotification(content: String) {
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.notify(1, createNotification(content))
  }

  private fun createNotification(content: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("AG Guard")
      .setContentText(content)
      .setSmallIcon(android.R.drawable.ic_dialog_alert) // 아이콘 변경
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val serviceChannel = NotificationChannel(
        CHANNEL_ID, "AG Guard Channel", NotificationManager.IMPORTANCE_LOW
      )
      getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }
  }

  @SuppressLint("MissingPermission")
  private fun disconnectGatt() {
    bluetoothGatt?.disconnect()
    bluetoothGatt?.close()
    bluetoothGatt = null
  }
}