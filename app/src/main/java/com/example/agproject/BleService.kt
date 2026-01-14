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

        val voiceList = tts?.voices

        val femaleVoice = voiceList?.find {
          it.locale.language == "ko" && it.name.contains("female", ignoreCase = true)
        }
        if (femaleVoice != null) {
          tts?.voice = femaleVoice
        }
        // 4. 톤과 속도 설정
        tts?.setPitch(1.1f)
        tts?.setSpeechRate(1.3f)
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
        // UUID STATUS
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
          // 시간 동기화 실행 함수
          android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            sendTimeSync()
          }, 500)
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

      if (receivedData.contains("PEDAL_ERR")) {
        Log.e(tag, "[위험] 페달 오조작 감지됨!")
        speakOut("경고!!! 페달 조작을 확인하세요!!")
        updateNotification("경고: 페달 오조작 감지됨!")
      }else if (receivedData.contains("MODULE_ERR")) {
        Log.e(tag, "[주의] 기기 연결 오류 감지됨!")
        speakOut("기기 연결 오류입니다. 장치를 확인 해주세요.")
        updateNotification("주의: 장치 연결 오류!")
      }else if (receivedData.contains("NETWORK_ERR")) {
        Log.e(tag, "[주의] 모듈 통신 오류 감지됨!")
        speakOut("기기 통신 시스템 오류입니다. 장치를 확인 해주세요.")
        updateNotification("주의: 기기 통신 시스템 오류!")
      }else{
        Log.i(tag, "정상 데이터 수신중: $receivedData")
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
  // Data -> Module function
  @SuppressLint("MissingPermission")
  private fun writeToModule(message: String) {
    if (bluetoothGatt == null) {
      Log.e(tag, "❌ 연결된 기기가 없어 전송 실패")
      return
    }

    val serviceUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca4917301")
    val charUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca491735e")

    val service = bluetoothGatt?.getService(serviceUuid)
    val characteristic = service?.getCharacteristic(charUuid)

    if (characteristic != null) {
      val dataBytes = message.toByteArray(Charsets.UTF_8)
      val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // 혹은 WRITE_TYPE_NO_RESPONSE

      // 안드로이드 버전에 따라 다르게 처리
      val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 최신 폰 (Android 13 이상): 함수 안에 데이터랑 타입을 다 넣어줘야 함
        bluetoothGatt?.writeCharacteristic(characteristic, dataBytes, writeType) == BluetoothStatusCodes.SUCCESS
      } else {
        // 구형 폰 (Android 12 이하) (값 먼저 넣고 전송)
        characteristic.writeType = writeType
        characteristic.value = dataBytes
        @Suppress("DEPRECATION") // 구형 방식 경고 무시 태그
        bluetoothGatt?.writeCharacteristic(characteristic) ?: false
      }

      if (success) {
        Log.i(tag, "데이터 전송 성공: $message")
      } else {
        Log.e(tag, "데이터 전송 실패")
      }

    } else {
      Log.e(tag, "쓰기 가능한 특성을 찾을 수 없음")
    }
  }

  // 현재 시간을 모듈에 동기화
  private fun sendTimeSync() {
    // 방법 1: Unix Timestamp (숫자만 보내는 방식 )
    // 예: 1705324567 (1970년 1월 1일부터 흐른 초)
    val currentTimestamp = System.currentTimeMillis() / 1000
    val command = "TIME:$currentTimestamp"

    // 방법 2: 날짜 문자열
    // val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA)
    // val dateStr = sdf.format(java.util.Date())
    // val command = "TIME:$dateStr"

    Log.d(tag, "시간 동기화 시도: $command")
    writeToModule(command)
  }
}