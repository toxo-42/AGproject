package com.example.agproject

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

  // 👇 이제 하드코딩된 주소는 필요 없습니다! 변수로만 선언합니다.
  private var targetAddress: String? = null

  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothLeScanner: BluetoothLeScanner? = null
  private var bluetoothGatt: BluetoothGatt? = null
  private var tts: TextToSpeech? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter
    bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    tts = TextToSpeech(this, this)
  }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      tts?.setLanguage(Locale.KOREAN)
    }
  }

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 👇 메인 화면에서 넘겨준 주소를 여기서 받습니다!
    targetAddress = intent?.getStringExtra("TARGET_ADDRESS")

    if (targetAddress == null) {
      Log.e(TAG, "주소가 전달되지 않았습니다! 서비스를 종료합니다.")
      stopSelf()
      return START_NOT_STICKY
    }

    startForegroundServiceNotification("타겟 감시 중: $targetAddress")

    // 받은 주소로 스캔 시작
    startTargetScan()

    return START_NOT_STICKY
  }

  override fun onDestroy() {
    disconnectGatt()
    tts?.shutdown()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  @SuppressLint("MissingPermission")
  private fun startTargetScan() {
    if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return

    Log.d(TAG, "🎯 타겟 스캔 시작: $targetAddress")

    val scanSettings = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .build()

    // 받아온 주소로 필터 생성
    val targetFilter = ScanFilter.Builder()
      .setDeviceAddress(targetAddress)
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
        bluetoothLeScanner?.stopScan(this)
        connectToDevice(it.device)
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun connectToDevice(device: BluetoothDevice) {
    bluetoothGatt = device.connectGatt(this, false, gattCallback)
  }

  private val gattCallback = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        Log.i(TAG, "✅ [성공] 기기 연결됨!")
        updateNotification("기기 연결됨 - 안전 감시 중 🛡️")
        speakOut("시스템이 연결되었습니다. 안전 운전 하세요.")
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        Log.w(TAG, "🔌 연결 끊김. 재연결 시도...")
        startTargetScan()
      }
    }
  }

  private fun speakOut(text: String) {
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ID")
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
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(1, createNotification(content))
  }

  private fun createNotification(content: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("AG Guard")
      .setContentText(content)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val serviceChannel = NotificationChannel(CHANNEL_ID, "AG Guard Channel", NotificationManager.IMPORTANCE_LOW)
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