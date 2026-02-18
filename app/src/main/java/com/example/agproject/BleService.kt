package com.example.agproject

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BleService : Service() {

  private val channelId = "BleCriticalChannel_v2"
  private val tag = "BleService"
  private var targetAddress: String? = null

  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothLeScanner: BluetoothLeScanner? = null
  private var bluetoothGatt: BluetoothGatt? = null

  // 에러 팝업이 떠있는지 체크하는 변수
  private var isErrorDialogShowing = false

  private var isCapacityDialogShowing = false

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter
    bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
  }

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 팝업에서 "확인" 버튼 눌렀을 때 실행됨
    if (intent?.action == "ACTION_CLEAR_ERROR") {
      Log.i(tag, "사용자 확인 완료 -> 복구 시퀀스(0xAA) 시작")
      handleErrorClearSequence()
      return START_NOT_STICKY
    }

    if (intent?.action == "ACTION_CLEAR_CAPACITY"){
      Log.i(tag, "용량 부족 경고 확인")
      handleCapacityClearSequence()

      return START_NOT_STICKY
    }

    // 디버깅용 나중에 삭제
//    if (intent?.action == "ACTION_DEBUG_PEDAL") {
//      Log.w(tag, "🔧 디버깅: 페달 에러 시뮬레이션 시작")
//
//      // 1. 소리 재생
//      playVoiceFile("PEDAL")
//
//      // 2. 상단바 문구 변경
//      updateNotification("경고: 페달 오조작 감지됨! (TEST)")
//
//      // 3. 🚨 화면 깨우기 & 전체 화면 알림 (핵심!)
//      showCriticalNotification("위험! 페달 오조작!", "즉시 페달 위치를 확인하세요!!")
//
//      // 👇 [추가] 이거 넣으면 무조건 뜹니다! (테스트용 강제 실행)
//      val forceIntent = Intent(this, CriticalActivity::class.java)
//      forceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//      startActivity(forceIntent)
//
//      return START_NOT_STICKY
//    }


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

  // 에러 해제 시퀀스
  private fun handleErrorClearSequence() {
    // 1. 0xAA 바이트 준비
    val resetCommand = byteArrayOf(0xAA.toByte())

    // 2. 735f (에러 채널)로 전송
    writeToErrorCharacteristic(resetCommand)

    // 3. 210ms 후 감시 재개
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      isErrorDialogShowing = false // 다시 감시 시작
      Log.i(tag, "🔄 모듈 에러 감시 재개 (Dialog Flag Reset)")
    }, 210)
  }

  private fun handleCapacityClearSequence() {
    //1. 0xAB byte 준비
    val comfirmCommend = byteArrayOf(0xAB.toByte())

    //2. 735f channel transmission
    writeToErrorCharacteristic(comfirmCommend)

    //3. 210ms 이후 감시 재개
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      isCapacityDialogShowing = false
      Log.i(tag, "용량 감시 경고 재개")
    },210)
  }

  override fun onDestroy() {
    disconnectGatt()
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

        // 데이터 특성(735e) 구독 (여기서 MODULE_ERR가 들어옴)
        if (characteristic != null) {
          enableNotification(gatt, characteristic)

          Log.i(tag, "데이터 채널(735e) 활성화 완료")

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

        // 에러 특성(735f) 발견 확인 로그
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

      // MODULE_ERR 체크
      if (receivedData.contains("MODULE_ERR")) {
        if (!isErrorDialogShowing) {
          isErrorDialogShowing = true // 깃발 올리기

          Log.w(tag, "[주의] 장치 오류 감지")
          playVoiceFile("ERROR")
          updateNotification("주의: 장치 오류 발생")

          showHeadsUpNotification("장치 오류", "장치를 점검해주세요!")

          // 메인 화면에 팝업 띄우라고 방송
          val intent = Intent("ACTION_MODULE_ERROR")
          intent.setPackage(packageName)
          sendBroadcast(intent)
        } else {
          Log.d(tag, "에러 중복 수신 무시됨 (사용자 확인 대기 중)")
        }
        return
      }

      // Pedal_err logic
      if (receivedData.contains("PEDAL_ERR")) {
        Log.e(tag, "[실제상황] 페달 오조작 감지됨! (Critical Alert)")

        // 1. 소리 & 상단바 알림
        playVoiceFile("PEDAL")
        updateNotification("위험! 페달 오조작 감지됨!")

        // 2. [Lock Screen 대응] 화면이 꺼져있을 때 깨우는 알림
        showCriticalNotification("위험! 페달 오조작!", "즉시 브레이크를 확인하세요!!")

        // 3. [Background 대응] 앱이 켜져있거나 백그라운드일 때 강제 화면 전환
        // 권한이 있는지 확인하고 실행해야 안 튕깁니다!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          if (android.provider.Settings.canDrawOverlays(this@BleService)) {
            try {
              val forceIntent = Intent(this@BleService, CriticalActivity::class.java)
              forceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
              startActivity(forceIntent)
              Log.i(tag, "[성공] Overlay 권한으로 화면 강제 전환")
            } catch (e: Exception) {
              Log.e(tag, "액티비티 강제 실행 실패: ${e.message}")
            }
          } else {
            Log.w(tag, "Overlay 권한 없음: 백그라운드 화면 전환 불가 (헤드업 알림만 뜸)")
          }
        } else {
          // 옛날 폰(Android 9 이하)은 권한 없이도 그냥 됩니다.
          val forceIntent = Intent(this@BleService, CriticalActivity::class.java)
          forceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
          startActivity(forceIntent)
        }
      }

      if (receivedData.contains("SD_SMALL")){
        if(!isCapacityDialogShowing){
          isCapacityDialogShowing = true

          Log.w(tag, "[주의] SD카드 용량 부족 감지됨!")
          updateNotification("주의: SD카드 용량 부족")
          showHeadsUpNotification(" 용량 부족", "SD카드 용량을 확인하세요.")
          playVoiceFile("SD")


          val intent = Intent("ACTION_SD_CARD_WARNING")
          intent.setPackage(packageName)
          sendBroadcast(intent)

        }
      }
    }
  }

  private fun playVoiceFile(type: String) {
    // 저장된 성별 확인
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val gender = prefs.getString("TTS_GENDER", "female") ?: "female"

    // 상황과 성별에 맞는 MP3 파일 ID 찾기
    var soundResId = 0

    if (gender == "male") {
      // 남성일 때 파일 매칭
      soundResId = when(type) {
        "PEDAL" -> R.raw.voice_pedal_male
        "SD" -> R.raw.voice_sd_male
        "ERROR" -> R.raw.voice_error_male
        else -> 0
      }
    } else {
      // 여성일 때 파일 매칭
      soundResId = when(type) {
        "PEDAL" -> R.raw.voice_pedal_female
        "SD" -> R.raw.voice_sd_female
        "ERROR" -> R.raw.voice_error_female
        else -> 0
      }
    }

    // 재생 (파일이 존재할 경우만)
    if (soundResId != 0) {
      try {
        // MediaPlayer 생성 및 재생
        val mediaPlayer = android.media.MediaPlayer.create(this, soundResId)
        mediaPlayer.setOnCompletionListener {
          it.release() // 재생 끝나면 메모리 청소
        }
        mediaPlayer.start()
      } catch (e: Exception) {
        Log.e(tag, "MP3 재생 실패: ${e.message}")
      }
    }
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
      .setContentIntent(getPendingIntent())
      .setOngoing(true)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // IMPORTANCE_LOW -> IMPORTANCE_HIGH 헤드업 알림 필수 조건!
      val serviceChannel = NotificationChannel(
        channelId,
        "PeOb Safety Channel",
        NotificationManager.IMPORTANCE_HIGH
      )

      // 알림 올 때 진동 Feedback
      serviceChannel.enableVibration(true)
      serviceChannel.description = "안전 장비의 긴급 경고를 알립니다."

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

  // 데이터 전송용 (735e로 보냄 - 시간 동기화용)
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
    val errUuid = UUID.fromString("d74d5c87-3d2b-46b3-b8a8-d64ca491735f") // 735f (에러용)

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

  private fun showHeadsUpNotification(title: String, content: String) {
    val manager = getSystemService(NotificationManager::class.java)

    val notification = NotificationCompat.Builder(this, channelId)
      .setContentTitle(title)
      .setContentText(content)
      .setSmallIcon(android.R.drawable.stat_sys_warning) // 경고 아이콘
      .setPriority(NotificationCompat.PRIORITY_HIGH) // 중요도 최상
      .setDefaults(Notification.DEFAULT_ALL) // 소리/진동 기본값 사용
      .setAutoCancel(true) // 터치하면 사라짐
      .setContentIntent(getPendingIntent())
      .build()

    // 중요: ID를 1번(서비스용)과 다르게 999번(경고용)으로 줍니다.
    manager.notify(999, notification)
  }

  private fun getPendingIntent(): PendingIntent? {
    val intent = Intent(this, MainActivity::class.java)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    return PendingIntent.getActivity(this,0, intent, flags)
  }

  // CriticalActivity를 깨우는 비상 알림 함수
  private fun showCriticalNotification(title: String, content: String) {
    val manager = getSystemService(NotificationManager::class.java)

    // 1. 목표 변경: MainActivity -> CriticalActivity
    val fullScreenIntent = Intent(this, CriticalActivity::class.java)
    // 중요: 새로운 태스크로 실행해야 기존 앱 위에 덮어씌워지지 않고 독립적으로 뜸
    fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    val fullScreenPendingIntent = android.app.PendingIntent.getActivity(
      this,
      999,
      fullScreenIntent,
      android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )

    // 2. 알림 설정 (헤드업 + 전체화면)
    val builder = NotificationCompat.Builder(this, channelId)
      .setSmallIcon(android.R.drawable.stat_sys_warning)
      .setContentTitle(title)
      .setContentText(content)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setFullScreenIntent(fullScreenPendingIntent, true) // 👈 여기가 핵심!
      .setAutoCancel(true)

    manager.notify(888, builder.build())
  }
}