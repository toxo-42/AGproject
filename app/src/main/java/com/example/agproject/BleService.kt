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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BleService : Service(), TextToSpeech.OnInitListener {

  private val channelId = "BleServiceChannel"
  private val tag = "BleService"

  private var targetAddress: String? = null

  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothLeScanner: BluetoothLeScanner? = null
  private var bluetoothGatt: BluetoothGatt? = null
  private var tts: TextToSpeech? = null

  // 에러 팝업이 떠있는지 체크하는 변수
  private var isErrorDialogShowing = false

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
        tts?.setPitch(1.1f)
        tts?.setSpeechRate(1.3f)
      }
    } else {
      Log.e(tag, "TTS 초기화 실패!")
    }
  }

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 팝업에서 "확인" 버튼 눌렀을 때 실행됨
    if (intent?.action == "ACTION_CLEAR_ERROR") {
      Log.i(tag, "✅ 사용자 확인 완료 -> 복구 시퀀스(0xAA) 시작")
      handleErrorClearSequence()
      return START_NOT_STICKY
    }

    val newAddress = intent?.getStringExtra("TARGET_ADDRESS")
    if (newAddress != null) {
      targetAddress = newAddress
    }

    if (targetAddress == null) {
      Log.e(tag, "주소가 전달되지 않았습니다! 서비스를 종료합니다.")
      stopSelf()
      return START_NOT_STICKY
    }

    startForegroundServiceNotification("타겟 감시 중: $targetAddress")
    startTargetScan()

    return START_NOT_STICKY
  }

  // [핵심] 에러 해제 시퀀스
  private fun handleErrorClearSequence() {
    // 1. 0xAA 바이트 준비
    val resetCommand = byteArrayOf(0xAA.toByte())

    // 2. 735f (에러 채널)로 전송!
    writeToErrorCharacteristic(resetCommand)

    // 3. 210ms 후 감시 재개
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      isErrorDialogShowing = false // 다시 감시 시작
      Log.i(tag, "🔄 모듈 에러 감시 재개 (Dialog Flag Reset)")
    }, 210)
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
    if (bluetoothGatt != null) return

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

  @Suppress("DEPRECATION")
  private val gattCallback = object : BluetoothGattCallback() {

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        Log.i(tag, "[성공] 기기 연결됨! 서비스를 탐색합니다...")
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
        val serviceUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca4917301")
        val charUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca491735e") // 데이터용
        val errUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca491735f")  // 에러 제어용

        val service = gatt?.getService(serviceUuid)
        val characteristic = service?.getCharacteristic(charUuid)

        // 에러 특성은 write 용도지만, 일단 변수는 찾아둠 (나중에 쓰려고)
        val errCharacteristic = service?.getCharacteristic(errUuid)

        // 2. 데이터 특성(735e) 구독 (여기서 MODULE_ERR가 들어옴)
        if (characteristic != null) {
          enableNotification(gatt, characteristic)

          Log.i(tag, "데이터 채널(735e) 활성화 완료")
          speakOut("시스템 가동. 감지를 시작합니다.")

          android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            sendTimeSyncBinary()
          }, 500)

          saveStatus("정상 연결")
          sendBroadcastToActivity("ACTION_UUID_MATCHED")
        } else {
          Log.e(tag, "주요 특성(735e) 없음")
          handleUuidMismatch()
          return
        }

        // 3. 에러 특성(735f) 발견 확인 로그
        if (errCharacteristic != null) {
          Log.i(tag, "에러 제어 채널(735f) 확인됨 (Ready to Write)")
        } else {
          Log.w(tag, "에러 제어 채널(735f)을 찾을 수 없음")
        }
      } else {
        Log.w(tag, "서비스 발견 실패: $status")
      }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      val receivedData = characteristic.getStringValue(0) ?: ""
      Log.d(tag, "수신된 데이터: $receivedData")

      // [수정] 735e로 들어오든 735f로 들어오든 상관없이 내용 검사!

      // 1. MODULE_ERR 체크 (735e로 들어오는 것 확실히 잡음)
      if (receivedData.contains("MODULE_ERR")) {
        if (!isErrorDialogShowing) {
          isErrorDialogShowing = true // 깃발 올리기

          Log.w(tag, "[주의] 장치 오류 감지")
          speakOut("장치 오류입니다. 장치를 점검해주세요.")
          updateNotification("주의: 장치 오류 발생")

          // 메인 화면에 팝업 띄우라고 방송
          val intent = Intent("ACTION_MODULE_ERROR")
          intent.setPackage(packageName)
          sendBroadcast(intent)
        } else {
          Log.d(tag, "에러 중복 수신 무시됨 (사용자 확인 대기 중)")
        }
        return // 처리 했으니 종료
      }

      // 2. 나머지 데이터 체크
      if (receivedData.contains("PEDAL_ERR")) {
        Log.e(tag, "[위험] 페달 오조작 감지됨!")
        speakOut("경고!!! 페달 조작을 확인하세요!!")
        updateNotification("경고: 페달 오조작 감지됨!")
      } else if (receivedData.contains("NETWORK_ERR")) {
        Log.e(tag, "[주의] 모듈 통신 오류 감지됨!")
        speakOut("기기 통신 시스템 오류입니다. 장치를 확인 해주세요.")
        updateNotification("주의: 기기 통신 시스템 오류!")
      } else {
        Log.i(tag, "정상 데이터 수신중: $receivedData")
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
    return NotificationCompat.Builder(this, channelId)
      .setContentTitle("PeOb")
      .setContentText(content)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    intent.setPackage(packageName)
    sendBroadcast(intent)
  }

  // [기존] 데이터 전송용 (735e로 보냄 - 시간 동기화용)
  @SuppressLint("MissingPermission")
  private fun writeToModule(dataBytes: ByteArray) {
    if (bluetoothGatt == null) return

    val serviceUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca4917301")
    val charUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca491735e") // 735e

    val service = bluetoothGatt?.getService(serviceUuid)
    val characteristic = service?.getCharacteristic(charUuid)

    if (characteristic != null) {
      val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        bluetoothGatt?.writeCharacteristic(characteristic, dataBytes, writeType)
      } else {
        characteristic.writeType = writeType
        characteristic.value = dataBytes
        @Suppress("DEPRECATION")
        bluetoothGatt?.writeCharacteristic(characteristic)
      }
    }
  }

  // 에러 해제 전송용 (735f로 보냄 - 0xAA 전송용)
  @SuppressLint("MissingPermission")
  private fun writeToErrorCharacteristic(dataBytes: ByteArray) {
    if (bluetoothGatt == null) {
      Log.e(tag, "연결된 기기가 없어 전송 실패")
      return
    }

    val serviceUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca4917301")
    val errUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca491735f") // 🔥 735f (에러용)

    val service = bluetoothGatt?.getService(serviceUuid)
    val characteristic = service?.getCharacteristic(errUuid)

    if (characteristic != null) {
      val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

      // 로그 추가: 내가 뭘 보내는지 확인
      val hexString = dataBytes.joinToString(separator = " ") { "0x%02X".format(it) }
      Log.d(tag, "[에러해제 전송] 타겟: 735f / 데이터: $hexString")

      val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        bluetoothGatt?.writeCharacteristic(characteristic, dataBytes, writeType) == BluetoothStatusCodes.SUCCESS
      } else {
        characteristic.writeType = writeType
        characteristic.value = dataBytes
        @Suppress("DEPRECATION")
        bluetoothGatt?.writeCharacteristic(characteristic) ?: false
      }

      if(success) Log.i(tag, "에러 해제 신호(0xAA) 전송 성공")
      else Log.e(tag, "에러 해제 신호 전송 실패")

    } else {
      Log.e(tag, "에러 특성(735f)을 찾을 수 없음")
    }
  }

  private fun sendTimeSyncBinary() {
    val currentTimeMillis = System.currentTimeMillis()
    val sec: Long = currentTimeMillis / 1000
    val usec: Long = (currentTimeMillis % 1000) * 1000

    val buffer = ByteBuffer.allocate(16)
    buffer.order(ByteOrder.LITTLE_ENDIAN)

    buffer.putLong(sec)
    buffer.putLong(usec)

    val dataBytes = buffer.array()

    Log.d(tag, "시간 동기화(Binary 16bytes): sec=$sec, usec=$usec")
    writeToModule(dataBytes) // 735e로 전송
  }

  @SuppressLint("MissingPermission")
  private fun enableNotification(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic) {
    if (gatt == null) return
    gatt.setCharacteristicNotification(characteristic, true)
    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
    if (descriptor != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
      } else {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
      }
    }
  }

  private fun handleUuidMismatch() {
    Log.e(tag, "목표 서비스/특성을 찾을 수 없음 (UUID 확인 필요)")
    saveStatus("경고: 인증 코드가 다릅니다. (UUID 불일치)")
    val intent = Intent("ACTION_UUID_MISMATCH")
    intent.setPackage(packageName)
    applicationContext.sendBroadcast(intent)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      stopSelf()
    }, 500)
  }
}