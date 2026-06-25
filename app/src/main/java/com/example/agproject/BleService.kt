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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
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

  // notify 구독은 GATT 작업이 직렬화되므로 한 번에 하나씩 -> 큐로 순차 처리
  private val notifyQueue = kotlin.collections.ArrayDeque<BluetoothGattCharacteristic>()

  // ── 페달 오조작 로컬 판정용 (Python judge 호출) ──────────────────
  // RAW 샘플을 모았다가 윈도우가 차면 Python judge_json 에 통째로 넘긴다.
  // 윈도잉 책임은 Kotlin 쪽(여기). Python은 판정만 한다.
  private val sampleBuffer = ArrayList<List<Double>>(WINDOW_SIZE)
  private var lastMisopShown = false   // 같은 오조작 연속 트리거 방지(예시)
  private var lastCnt = -1             // 직전 샘플 cnt (누락 검출용, -1 = 아직 없음)

  companion object {
    // Nordic UART Service (NUS) 표준 UUID
    private val NUS_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val NUS_RX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // 폰 -> 기기 (Write)
    private val NUS_TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // 기기 -> 폰 (Notify, 문자열)

    // 대용량 raw 바이너리 전용 attr (TODO: 펌웨어 쪽 UUID 확정되면 교체)
    private val RAW_DATA = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E") // 기기 -> 폰 (Notify, raw)

    // CCCD: notify on/off 표준 디스크립터
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ── RAW 디코딩 포맷 (BLE_RAW_스트림_규격.md §3) ─────────────────
    // 한 notify = 배치 프레임 1개: count(1B) + RawSample × N
    // RawSample(10B, little-endian): int32 Q31 break, int32 Q31 accel, uint16 cnt
    private const val BYTES_PER_SAMPLE = 10
    private const val Q31_SCALE = 2147483648.0 // 2^31. raw / Q31_SCALE = 0.0~1.0 정규화 힘
    // STM32 200Hz 원천 스트림 기준. 50샘플 = 0.25초 윈도우 (튜닝 대상)
    private const val WINDOW_SIZE = 50
  }

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter
    bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    // Chaquopy Python 런타임 시작 (앱 생명주기 동안 한 번만)
    if (!Python.isStarted()) {
      Python.start(AndroidPlatform(this))
    }
    selfTestJudge() // 연동 관통 확인용 (실데이터 없을 때)
  }

  // judge.py 가 안드로이드 안에서 실제로 호출되는지 확인하는 자가진단.
  // 더미 윈도우 하나를 만들어 호출하고 결과 JSON 을 Logcat 에 찍는다.
  // RAW 디코딩/실데이터 연동이 끝나면 제거해도 된다.
  private fun selfTestJudge() {
    try {
      val dummy = org.json.JSONArray()
      repeat(WINDOW_SIZE) { dummy.put(org.json.JSONArray(listOf(0.95, 0.0))) } // 전형적 오조작 패턴
      val resultJson = Python.getInstance()
        .getModule("judge")
        .callAttr("judge_json", dummy.toString())
        .toString()
      Log.i(tag, "[Chaquopy 자가진단] judge_json -> $resultJson")
    } catch (e: Exception) {
      Log.e(tag, "[Chaquopy 자가진단] 실패: ${e.message}", e)
    }
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

    // 2. RX 채널로 전송
    writeToRx(resetCommand)

    // 3. 210ms 후 감시 재개
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      isErrorDialogShowing = false // 다시 감시 시작
      Log.i(tag, "🔄 모듈 에러 감시 재개 (Dialog Flag Reset)")
    }, 210)
  }

  private fun handleCapacityClearSequence() {
    //1. 0xAB byte 준비
    val comfirmCommend = byteArrayOf(0xAB.toByte())

    //2. RX 채널로 전송
    writeToRx(comfirmCommend)

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
      if (status != BluetoothGatt.GATT_SUCCESS) {
        Log.w(tag, "서비스 발견 실패: $status")
        return
      }

      val service = gatt?.getService(NUS_SERVICE)
      val txChar = service?.getCharacteristic(NUS_TX)   // 문자열 수신
      val rawChar = service?.getCharacteristic(RAW_DATA) // raw 수신

      // TX(문자열)는 필수. 없으면 잘못된 기기
      if (txChar == null) {
        Log.e(tag, "NUS TX 특성($NUS_TX) 없음")
        handleUuidMismatch()
        return
      }

      // 구독 대상 큐에 적재 (TX -> RAW 순서로 하나씩 구독)
      notifyQueue.clear()
      notifyQueue.add(txChar)
      if (rawChar != null) {
        notifyQueue.add(rawChar)
      } else {
        Log.w(tag, "RAW 채널($RAW_DATA) 없음 (펌웨어 미구현?)")
      }

      saveStatus("정상 연결")
      sendBroadcastToActivity("ACTION_UUID_MATCHED")

      // 규격 §6: notify 구독 '전에' MTU를 키워야 한다.
      // 미요청 시 MTU=23 -> payload 20B -> 41B 배치 프레임이 아예 안 들어옴(데이터 0의 1순위 원인).
      // MTU 협상 결과는 onMtuChanged 로 이어지고, 거기서 구독을 시작한다.
      gatt.requestMtu(247) // 펌웨어 preferred 와 동일. 실제값은 양측 min 으로 협상됨
    }

    // MTU 협상 완료 -> 연결 주기 단축 후 notify 구독 시작
    @SuppressLint("MissingPermission")
    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
      Log.i(tag, "MTU 협상 완료: $mtu (status=$status)")
      // 규격 §6: 50Hz 배치를 빠짐없이 받으려면 connection interval <= 20ms 필요
      gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
      subscribeNext(gatt) // 첫 구독 시작 -> 이후 onDescriptorWrite 콜백으로 이어짐
    }

    // 구독 한 건 완료될 때마다 호출 -> 다음 대상 구독, 큐가 비면 시간 동기화
    override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
      subscribeNext(gatt)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      when (characteristic.uuid) {
        NUS_TX -> handleTextMessage(characteristic.getStringValue(0) ?: "")
        RAW_DATA -> handleRawData(characteristic.value ?: ByteArray(0))
        else -> Log.d(tag, "알 수 없는 특성 수신: ${characteristic.uuid}")
      }
    }
  }

  // 큐에서 다음 특성을 꺼내 notify 구독. 비어 있으면 모든 구독 완료로 보고 시간 동기화.
  @SuppressLint("MissingPermission")
  private fun subscribeNext(gatt: BluetoothGatt?) {
    val next = notifyQueue.removeFirstOrNull()
    if (next == null) {
      // 규격 §5: 시간 동기화(과거 16B sec/usec)는 제거됨. 절대시각은 폰 수신시각 + cnt 로 처리.
      Log.i(tag, "모든 notify 구독 완료 -> 데이터 수신 대기")
      return
    }
    Log.i(tag, "notify 구독 시작: ${next.uuid}")
    enableNotification(gatt, next)
  }

  // ── 기기 -> 폰 문자열 메시지 처리 ─────────────────────────────
  // TODO: 에러 구조 전면 개편 예정. 지금은 패턴 예시 하나(MODULE_ERR)만 구현.
  //       새 구조 확정되면 아래 when 분기에 케이스만 추가하면 됨.
  private fun handleTextMessage(msg: String) {
    Log.d(tag, "수신(TX): $msg")
    when {
      msg.contains("MODULE_ERR") -> onModuleError()
      // TODO: SD_SMALL 등 새 에러 구조에 맞춰 케이스 추가
      //       (엑셀/페달 오조작은 더 이상 문자열로 받지 않음 -> RAW 데이터 로컬 판단으로 이관)
    }
  }

  // [예시] 모듈 오류 처리: 중복 수신은 사용자 확인 전까지 무시
  private fun onModuleError() {
    if (isErrorDialogShowing) {
      Log.d(tag, "에러 중복 수신 무시 (사용자 확인 대기 중)")
      return
    }
    isErrorDialogShowing = true

    Log.w(tag, "[주의] 장치 오류 감지")
    playVoiceFile("ERROR")
    updateNotification("주의: 장치 오류 발생")
    showHeadsUpNotification("장치 오류", "장치를 점검해주세요!")
    sendBroadcastToActivity("ACTION_MODULE_ERROR")
  }

  // ── 기기 -> 폰 raw 바이너리 처리 (엑셀/페달 오조작 로컬 판단 입력부) ──────────
  // 50Hz 로 들어오는 배치 프레임(count + RawSample×N)을 디코딩해 윈도우에 쌓고,
  // 윈도우가 차면 Python judge_json 으로 판정한다. (BLE_RAW_스트림_규격.md §3)
  private fun handleRawData(data: ByteArray) {
    if (data.isEmpty()) {
      Log.w(tag, "수신(RAW): 빈 프레임 폐기")
      return
    }

    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    // 프레임 = count(1B) + RawSample × count. 길이 검증으로 깨진 프레임 폐기.
    val count = buf.get().toInt() and 0xFF
    val expected = 1 + count * BYTES_PER_SAMPLE
    if (data.size != expected) {
      Log.w(tag, "수신(RAW): 길이 불일치 ${data.size}B (기대 ${expected}B, count=$count) -> 폐기")
      return
    }
    Log.d(tag, "수신(RAW): count=$count (${data.size}B)")

    repeat(count) {
      val brkRaw = buf.int                    // int32 Q31 (offset 0: break)
      val accRaw = buf.int                    // int32 Q31 (offset 4: accel)
      val cnt = buf.short.toInt() and 0xFFFF  // uint16 시퀀스 (offset 8)

      // cnt 누락 검출: 200Hz 등간격이라 직전과의 차가 1보다 크면 그만큼 빠진 것.
      // wrap-around(65536) 고려해 (현재 - 직전) and 0xFFFF 로 계산.
      if (lastCnt >= 0) {
        val gap = (cnt - lastCnt) and 0xFFFF
        if (gap > 1) Log.w(tag, "샘플 누락 의심: cnt $lastCnt -> $cnt (gap=$gap)")
      }
      lastCnt = cnt

      // Q31 raw -> 0.0~1.0 정규화 힘. judge.py 는 [accel, brake] 순서를 기대하므로 주의.
      val brake = brkRaw / Q31_SCALE
      val accel = accRaw / Q31_SCALE
      sampleBuffer.add(listOf(accel, brake))
    }

    // 윈도우가 차면 Python judge 호출 후 버퍼 비움
    if (sampleBuffer.size >= WINDOW_SIZE) {
      val window = ArrayList(sampleBuffer)   // 복사본 전달
      sampleBuffer.clear()
      judgeWindow(window)
    }
  }

  // 윈도우 하나를 Python judge_json 에 넘겨 오조작 여부를 판정한다.
  private fun judgeWindow(window: List<List<Double>>) {
    try {
      // 윈도우를 JSON 문자열로 직렬화해 전달 (Chaquopy ArrayList 변환 이슈 회피)
      val samplesJson = org.json.JSONArray()
      for (sample in window) samplesJson.put(org.json.JSONArray(sample))
      val resultJson = Python.getInstance()
        .getModule("judge")
        .callAttr("judge_json", samplesJson.toString())
        .toString()
      val result = JSONObject(resultJson)
      val misop = result.getBoolean("misop")
      val score = result.getDouble("score")
      Log.d(tag, "판정: misop=$misop score=$score")

      if (misop && !lastMisopShown) {
        lastMisopShown = true
        onPedalMisoperation()
      } else if (!misop) {
        lastMisopShown = false // 정상으로 돌아오면 다음 오조작 다시 경고
      }
    } catch (e: Exception) {
      Log.e(tag, "judge 호출 실패: ${e.message}", e)
    }
  }

  // 페달 오조작 감지 시 경고 트리거 (기존 음성/헤드업 알림 재사용)
  private fun onPedalMisoperation() {
    Log.w(tag, "[경고] 페달 오조작 감지")
    playVoiceFile("SD")
    updateNotification("주의: 페달 오조작 감지")
    showHeadsUpNotification("페달 오조작", "브레이크를 확인하세요!")
    sendBroadcastToActivity("ACTION_PEDAL_MISOP")
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
        "SD" -> R.raw.voice_sd_male
        "ERROR" -> R.raw.voice_error_male
        else -> 0
      }
    } else {
      // 여성일 때 파일 매칭
      soundResId = when(type) {
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

  // 폰 -> 기기 전송 (NUS RX 단일 채널). 시간 동기화/0xAA/0xAB 모두 이 함수로.
  @SuppressLint("MissingPermission")
  private fun writeToRx(dataBytes: ByteArray) {
    val gatt = bluetoothGatt ?: run {
      Log.e(tag, "연결된 기기가 없어 전송 실패")
      return
    }

    val characteristic = gatt.getService(NUS_SERVICE)?.getCharacteristic(NUS_RX)
    if (characteristic == null) {
      Log.e(tag, "RX 특성($NUS_RX)을 찾을 수 없음")
      return
    }

    val hexString = dataBytes.joinToString(separator = " ") { "0x%02X".format(it) }
    Log.d(tag, "[RX 전송] $hexString")

    val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      gatt.writeCharacteristic(characteristic, dataBytes, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
      characteristic.writeType = writeType
      characteristic.value = dataBytes
      @Suppress("DEPRECATION")
      gatt.writeCharacteristic(characteristic)
    }

    if (success) Log.i(tag, "RX 전송 성공") else Log.e(tag, "RX 전송 실패")
  }

  @SuppressLint("MissingPermission")
  private fun enableNotification(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic) {
    if (gatt == null) return
    gatt.setCharacteristicNotification(characteristic, true)
    val descriptor = characteristic.getDescriptor(CCCD)
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
}