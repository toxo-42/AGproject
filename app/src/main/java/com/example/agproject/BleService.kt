package com.example.agproject

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class BleService : Service(), TextToSpeech.OnInitListener
{

  // 1. 변수 이름을 소문자 시작(camelCase)으로 변경하여 경고 해결
  private val channelId = "BleServiceChannel"
  private val tag = "BleService"

  private var targetAddress: String? = null

  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothLeScanner: BluetoothLeScanner? = null
  private var bluetoothGatt: BluetoothGatt? = null
  private var tts: TextToSpeech? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter
    bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    tts = TextToSpeech(this, this)
  }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      val result = tts?.setLanguage(Locale.KOREAN)

      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.e(tag, "이 언어는 지원되지 않습니다.")
      } else {
        // 👇 [여기서부터 추가!] 여성 목소리 찾기 로직 👇

        // 1. 폰에 있는 모든 목소리 리스트를 가져와
        val voiceList = tts?.voices

        // 2. 그중에서 '한국어'이면서 이름에 'female'이 들어간 목소리를 찾아
        val femaleVoice = voiceList?.find {
          it.locale.language == "ko" && it.name.contains("female", ignoreCase = true)
        }

        // 3. 찾았으면 그 목소리로 설정!
        if (femaleVoice != null) {
          tts?.voice = femaleVoice
          Log.i(tag, "여성 목소리 설정 완료: ${femaleVoice.name}")
        } else {
          Log.w(tag, "여성 목소리를 찾지 못해 기본 목소리로 설정합니다.")
        }

        // 4. 톤과 속도 설정 (여성 목소리는 기본 1.0이 제일 자연스러움)
        tts?.setPitch(1.0f)
        tts?.setSpeechRate(1.5f)
      }
    } else {
      Log.e(tag, "TTS 초기화 실패!")
    }
  }
  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    targetAddress = intent?.getStringExtra("TARGET_ADDRESS")

    if (targetAddress == null) {
      Log.e(tag, "주소가 전달되지 않았습니다! 서비스를 종료합니다.")
      stopSelf()
      return START_NOT_STICKY
    }

    startForegroundServiceNotification("타겟 감시 중: $targetAddress")
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

    Log.d(tag, "타겟 스캔 시작: $targetAddress")

    val scanSettings = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .build()

    val targetFilter = ScanFilter.Builder()
      .setDeviceAddress(targetAddress)
      .build()

    val filters = mutableListOf(targetFilter)

    try {
      bluetoothLeScanner?.startScan(filters, scanSettings, scanCallback)
    } catch (e: Exception) {
      Log.e(tag, "스캔 에러: ${e.message}")
    }
  }

  private val scanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result?.let {
        Log.i(tag, "기기 발견! 연결 시도 중...")
        bluetoothLeScanner?.stopScan(this)
        connectToDevice(it.device)
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun connectToDevice(device: BluetoothDevice) {
    bluetoothGatt = device.connectGatt(this, false, gattCallback)
  }

  // 2. Deprecation(구형 코드 사용) 경고를 무시하도록 설정
  @Suppress("DEPRECATION")
  private val gattCallback = object : BluetoothGattCallback() {

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        Log.i(tag, "[성공] 기기 연결됨! 서비스를 탐색합니다...")
        // 상단바 텍스트 변경
        updateNotification("기기 연결됨 - 오조작 감지 중...")
        gatt?.discoverServices()
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        Log.w(tag, "연결 끊김. 재연결 시도...")
        startTargetScan()
      }
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        // 변수 이름 소문자로 변경
        val serviceUuid = "d74d5c87-3d2b-46b3-b8a8-d64ca4917301"
        val charUuid = "d74d5c87-3d2b-46b3-b8a8-d64ca491735e"

        val service = gatt?.getService(UUID.fromString(serviceUuid))
        val characteristic = service?.getCharacteristic(UUID.fromString(charUuid))

        if (characteristic != null) {
          gatt.setCharacteristicNotification(characteristic, true)

          val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
          if (descriptor != null) {
            // 안드로이드 13(Tiramisu) 분기 처리
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
              // 구형 방식 (경고 억제됨)
              descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
              gatt.writeDescriptor(descriptor)
            }
          }
          Log.i(tag, "데이터 수신 모드 활성화 완료 (Notify ON)")
          speakOut("시스템 가동. 감지를 시작합니다.")

          saveStatus("정상 연결")
          sendBroadcastToActivity("ACTION_UUID_MATCHED")

        } else {
          Log.e(tag, "목표 서비스/특성을 찾을 수 없음 (UUID 확인 필요)")
          saveStatus("경고: 인증 코드가 다릅니다. (UUID 불일치)")

          val intent = Intent("ACTION_UUID_MISMATCH")
          intent.setPackage(packageName)
          applicationContext.sendBroadcast(intent)

          // UUID 불일치시 백그라운드 실행 X
          android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stopSelf()
          }, 500)
        }
      }

    }
    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      // 구형 방식 getStringValue 사용 (경고 억제됨)
      val receivedData = characteristic.getStringValue(0) ?: ""
      Log.d(tag, "수신된 데이터: $receivedData")

      if (receivedData.contains("ERR") || receivedData.contains("1")) {
        Log.e(tag, "[위험] 페달 오조작 감지됨!")
        speakOut("경고! 페달 조작을 확인하세요!!")
        updateNotification("경고: 페달 오조작 감지됨!")
      }
    }
  }

  private fun speakOut(text: String) {
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ID")
  }

  private fun startForegroundServiceNotification(content: String) {
    val notification = createNotification(content)
    // 3. SDK_INT >= 26 체크 제거 (항상 26 이상이므로)
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
    // channelId 변수 사용
    return NotificationCompat.Builder(this, channelId)
      // 상단바 앱 이름
      .setContentTitle("PeOb")
      .setContentText(content)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // channelId 변수 사용
      val serviceChannel = NotificationChannel(channelId, "PeOb Channel", NotificationManager.IMPORTANCE_LOW)
      getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }
  }

  @SuppressLint("MissingPermission")
  private fun disconnectGatt() {
    bluetoothGatt?.disconnect()
    bluetoothGatt?.close()
    bluetoothGatt = null
  }

  private fun saveStatus(statusMsg: String) {
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    prefs.edit().putString("CONNECTION_STATUS", statusMsg).apply()
  }

  private fun sendBroadcastToActivity(action: String) {
    val intent = Intent(action)
    intent.setPackage(packageName) // 우리 앱한테만 보내기
    sendBroadcast(intent)
  }
}