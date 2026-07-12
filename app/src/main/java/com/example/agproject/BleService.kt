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

  // ── RAW 원시 샘플 CSV 로깅 (Phase A: 학습 데이터 수집) ──────────────
  // 첫 RAW 샘플이 실제로 도착한 시점에 lazy 로 세션 파일을 연다(연결 실패 시 빈 파일 안 남김).
  // 저장 위치: filesDir/logs/pedal_yyyyMMdd_HHmmss.csv (권한 불필요, adb pull 로 회수)
  // 컬럼: recv_epoch_ms,cnt,accel,brake  (원시 샘플 1개당 1줄, 판정 윈도우와 무관하게 전부 기록)
  //
  // BLE notify 콜백은 여러 바인더 스레드에서 올라온다 → BufferedWriter 접근은 전부 csvLock 아래에서.
  // csvClosed 는 종료 후 뒤늦게 도착한 콜백이 새 세션 파일을 되살리는 것을 막는다.
  private val csvLock = Any()
  private var csvWriter: java.io.BufferedWriter? = null
  private var csvClosed = false

  // ── 라벨링 (Phase A: 지도학습용 정답 수집) ────────────────────────
  // MainActivity 의 '오조작' 토글이 ACTION_SET_LABEL 로 갱신한다.
  // BLE 콜백 스레드가 읽고 UI 스레드가 쓰므로 @Volatile 필요.
  @Volatile private var currentLabel = LABEL_NORMAL

  // 수집 화면이 떠 있는 동안만 true. BLE 콜백 스레드가 읽고 메인 스레드가 쓴다.
  @Volatile private var liveStreamEnabled = false

  // ── 로그 스로틀링 ────────────────────────────────────────────────
  // 배치 50Hz / 판정 4Hz 로 로그를 찍으면 logcat 링버퍼가 몇 분 만에 밀려 나가
  // 정작 필요한 연결·오류 로그가 사라진다. 요약과 상태 변화만 남긴다.
  private var rawBatchCount = 0
  private var rawSampleCount = 0
  private var lastRawLogMs = 0L
  private var lastJudgeLogMs = 0L
  private var lastLoggedMisop: Boolean? = null
  private var lastTextMessage: String? = null

  // 이번 세션의 운전 습관 프로필. 파일명에 남기고, judge_json 에도 그대로 넘긴다.
  // 세션 도중 바뀌면 CSV 와 판정 기준이 어긋나므로 서비스 시작 시점에 한 번만 읽는다.
  // onStartCommand(메인 스레드)가 쓰고 BLE 콜백 스레드가 읽는다.
  @Volatile private var sessionProfile = DEFAULT_PROFILE

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

    // 로그 스로틀 주기 (수신 요약 / 판정 하트비트)
    private const val RAW_LOG_INTERVAL_MS = 5_000L
    private const val JUDGE_LOG_INTERVAL_MS = 10_000L

    // ── 라벨링/프로필 (Phase A) ─────────────────────────────────────
    // MainActivity 가 오조작 토글을 누를 때 보내는 인텐트.
    const val ACTION_SET_LABEL = "ACTION_SET_LABEL"
    const val EXTRA_LABEL = "LABEL"

    const val LABEL_NORMAL = 0   // 정상 주행 구간
    const val LABEL_MISOP = 1    // 오조작 재현 구간

    // 프로필은 SharedPreferences("AgPrefs") 에 저장된다. judge.py 의 PROFILES 키와 반드시 일치.
    const val PREF_PROFILE = "DRIVER_PROFILE"
    const val DEFAULT_PROFILE = "normal"
    val VALID_PROFILES = setOf("strong", "normal", "weak")

    // ── 실시간 그래프 스트림 (DataCollectActivity 전용) ──────────────
    // 50Hz 브로드캐스트라 평소엔 낭비다. 수집 화면이 떠 있는 동안만 켠다.
    const val ACTION_SET_LIVE_STREAM = "ACTION_SET_LIVE_STREAM"
    const val EXTRA_LIVE_STREAM = "LIVE_STREAM"

    const val ACTION_LIVE_SAMPLES = "ACTION_LIVE_SAMPLES"
    const val EXTRA_ACCELS = "ACCELS"   // FloatArray, 배치 내 샘플 순서
    const val EXTRA_BRAKES = "BRAKES"   // FloatArray, 같은 길이
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

    // 오조작 라벨 토글. 이후 기록되는 CSV 행의 label 컬럼이 이 값으로 찍힌다.
    if (intent?.action == ACTION_SET_LABEL) {
      currentLabel = if (intent.getIntExtra(EXTRA_LABEL, LABEL_NORMAL) == LABEL_MISOP) {
        LABEL_MISOP
      } else {
        LABEL_NORMAL
      }
      Log.i(tag, "라벨 변경: label=$currentLabel (${if (currentLabel == LABEL_MISOP) "오조작" else "정상"})")
      return START_NOT_STICKY
    }

    // 실시간 그래프 스트림 on/off (수집 화면 진입/이탈)
    if (intent?.action == ACTION_SET_LIVE_STREAM) {
      liveStreamEnabled = intent.getBooleanExtra(EXTRA_LIVE_STREAM, false)
      Log.i(tag, "실시간 스트림: $liveStreamEnabled")
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

    // 이번 세션에 적용할 프로필 확정. 이후 CSV 파일명·judge_json 판정에 모두 쓰인다.
    sessionProfile = readProfile()
    Log.i(tag, "세션 프로필: $sessionProfile")

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
    // 순서 중요: GATT 를 먼저 끊어야 뒤늦은 notify 콜백이 세션 파일을 되살리지 않는다.
    disconnectGatt()
    closeCsvWriter()   // 세션 로그 파일 flush + close
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
    // 펌웨어가 MODULE_ERR 을 연속 송신할 수 있어(FSR 연결 불안정 감지 로직) 같은 메시지
    // 반복은 로그를 남기지 않는다. 메시지가 바뀌는 순간만 기록.
    if (msg != lastTextMessage) {
      Log.d(tag, "수신(TX): $msg")
      lastTextMessage = msg
    }
    when {
      msg.contains("MODULE_ERR") -> onModuleError()
      // TODO: SD_SMALL 등 새 에러 구조에 맞춰 케이스 추가
      //       (엑셀/페달 오조작은 더 이상 문자열로 받지 않음 -> RAW 데이터 로컬 판단으로 이관)
    }
  }

  // [예시] 모듈 오류 처리: 중복 수신은 사용자 확인 전까지 무시
  private fun onModuleError() {
    if (isErrorDialogShowing) {
      return   // 중복 수신 — 로그도 남기지 않는다(초당 수십 회 들어옴)
    }
    isErrorDialogShowing = true

    Log.w(tag, "[주의] 장치 오류 감지")
    playVoiceFile("ERROR")
    updateNotification("주의: 장치 오류 발생")
    showHeadsUpNotification("장치 오류", "장치를 점검해주세요!")
    sendBroadcastToActivity("ACTION_MODULE_ERROR")
  }

  // ── RAW 원시 샘플 CSV 로깅 헬퍼 (Phase A) ──────────────────────────
  // 모두 csvLock 아래에서 호출된다고 가정한다(호출부: writeRawCsv/flushRawCsv/closeCsvWriter).
  // 최초 호출 시점에 세션 파일을 lazy 로 연다. 실패해도 판정 흐름은 계속되도록 예외를 삼킨다.
  private fun ensureCsvWriterLocked(): java.io.BufferedWriter? {
    if (csvClosed) return null   // 서비스 종료 후 도착한 콜백 → 새 세션 파일을 열지 않는다
    csvWriter?.let { return it }
    return try {
      val dir = java.io.File(filesDir, "logs").apply { if (!exists()) mkdirs() }
      val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      // 파일명에 프로필을 박아 두면 나중에 adb pull 만으로 어느 프로필 세션인지 알 수 있다.
      val file = java.io.File(dir, "pedal_${ts}_$sessionProfile.csv")
      java.io.BufferedWriter(java.io.FileWriter(file, true)).also {
        it.write("recv_epoch_ms,cnt,accel,brake,label\n")
        csvWriter = it
        Log.i(tag, "RAW 로깅 시작: ${file.absolutePath} (프로필=$sessionProfile)")
      }
    } catch (e: Exception) {
      Log.e(tag, "CSV 로깅 파일 생성 실패: ${e.message}", e)
      null
    }
  }

  // 저장된 프로필을 읽되, 값이 깨졌거나 없으면 기본 프로필로 떨어진다.
  // (judge.py 도 모르는 프로필명은 normal 로 fallback 하므로 양쪽이 일관)
  private fun readProfile(): String {
    val saved = getSharedPreferences("AgPrefs", MODE_PRIVATE).getString(PREF_PROFILE, null)
    return if (saved in VALID_PROFILES) saved!! else DEFAULT_PROFILE
  }

  private fun writeRawCsv(recvMs: Long, cnt: Int, accel: Double, brake: Double, label: Int) {
    synchronized(csvLock) {
      val w = ensureCsvWriterLocked() ?: return
      try {
        // accel/brake 는 소수 4자리로 반올림(Q31 원본보다 촘촘할 필요 없음 → 줄 크기 안정).
        w.write(
          "$recvMs,$cnt,${String.format(Locale.US, "%.4f", accel)}," +
            "${String.format(Locale.US, "%.4f", brake)},$label\n"
        )
      } catch (e: Exception) {
        Log.e(tag, "CSV 쓰기 실패: ${e.message}")
      }
    }
  }

  private fun flushRawCsv() {
    synchronized(csvLock) {
      try { csvWriter?.flush() } catch (e: Exception) { Log.w(tag, "CSV flush 실패: ${e.message}") }
    }
  }

  private fun closeCsvWriter() {
    synchronized(csvLock) {
      try {
        csvWriter?.flush()
        csvWriter?.close()
      } catch (e: Exception) {
        Log.w(tag, "CSV 닫기 실패: ${e.message}")
      }
      csvWriter = null
      csvClosed = true
    }
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
    // 배치마다 로그를 찍으면 50줄/초라 logcat 링버퍼가 몇 분 만에 밀려 나간다.
    // 수신이 살아 있다는 것만 확인하면 되므로 주기적으로 요약만 남긴다.
    rawBatchCount++
    rawSampleCount += count
    val nowMs = System.currentTimeMillis()
    if (lastRawLogMs == 0L) {
      lastRawLogMs = nowMs   // 첫 배치: 기준 시각만 잡고 요약은 다음 주기부터
    } else if (nowMs - lastRawLogMs >= RAW_LOG_INTERVAL_MS) {
      val elapsed = (nowMs - lastRawLogMs) / 1000.0
      val hz = rawSampleCount / elapsed
      Log.i(tag, "수신(RAW) 요약: ${rawBatchCount}배치 / ${rawSampleCount}샘플 (${"%.1f".format(hz)}Hz)")
      lastRawLogMs = nowMs
      rawBatchCount = 0
      rawSampleCount = 0
    }

    // 이 배치를 수신한 폰 로컬 시각(같은 배치 내 샘플은 cnt 로 순서 구분).
    val recvMs = nowMs
    // 라벨은 배치 시작 시점에 한 번만 읽는다. 배치 처리 도중 토글이 바뀌어도
    // 같은 배치(20ms) 안의 샘플들은 같은 라벨을 갖는 편이 해석하기 쉽다.
    val label = currentLabel

    // 실시간 그래프용 배치 버퍼 (스트림이 꺼져 있으면 만들지 않는다)
    val streaming = liveStreamEnabled
    val liveAccels = if (streaming) FloatArray(count) else null
    val liveBrakes = if (streaming) FloatArray(count) else null

    repeat(count) { i ->
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
      writeRawCsv(recvMs, cnt, accel, brake, label)   // 원시 샘플 + 라벨 CSV 축적 (Phase A)

      liveAccels?.set(i, accel.toFloat())
      liveBrakes?.set(i, brake.toFloat())
    }
    flushRawCsv()   // 배치 단위로 flush (앱 강제종료 시 손실 최소화)

    if (liveAccels != null && liveBrakes != null) {
      sendBroadcast(Intent(ACTION_LIVE_SAMPLES).apply {
        setPackage(packageName)
        putExtra(EXTRA_ACCELS, liveAccels)
        putExtra(EXTRA_BRAKES, liveBrakes)
      })
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
      // 프로필을 함께 넘겨 사용자별 임계값으로 판정한다 (judge.py PROFILES).
      val resultJson = Python.getInstance()
        .getModule("judge")
        .callAttr("judge_json", samplesJson.toString(), sessionProfile)
        .toString()
      val result = JSONObject(resultJson)
      val misop = result.getBoolean("misop")
      val score = result.getDouble("score")

      // 판정은 4Hz 로 나온다. 상태가 바뀌는 순간(정상<->오조작)은 항상 남기고,
      // 변화가 없으면 살아있다는 표시로 주기적 하트비트만 남긴다.
      val nowMs = System.currentTimeMillis()
      if (misop != lastLoggedMisop) {
        Log.i(tag, "판정 변화: misop=$misop score=$score profile=$sessionProfile")
        lastLoggedMisop = misop
        lastJudgeLogMs = nowMs
      } else if (nowMs - lastJudgeLogMs >= JUDGE_LOG_INTERVAL_MS) {
        Log.d(tag, "판정: misop=$misop score=$score profile=$sessionProfile")
        lastJudgeLogMs = nowMs
      }

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

  // 페달 오조작 감지 시 경고 트리거 (fullScreenIntent로 CriticalActivity 강제 실행)
  private fun onPedalMisoperation() {
    Log.w(tag, "[경고] 페달 오조작 감지")
    playVoiceFile("PEDAL")
    updateNotification("주의: 페달 오조작 감지")
    showCriticalNotification("위험! 페달 오조작!", "즉시 브레이크를 확인하세요!")
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

  // CriticalActivity를 깨우는 비상 알림 함수 (fullScreenIntent로 강제 전체화면 전환)
  private fun showCriticalNotification(title: String, content: String) {
    val manager = getSystemService(NotificationManager::class.java)

    val fullScreenIntent = Intent(this, CriticalActivity::class.java)
    fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    val fullScreenPendingIntent = PendingIntent.getActivity(
      this,
      999,
      fullScreenIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(this, channelId)
      .setSmallIcon(android.R.drawable.stat_sys_warning)
      .setContentTitle(title)
      .setContentText(content)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setFullScreenIntent(fullScreenPendingIntent, true)
      .setAutoCancel(true)
      .build()

    manager.notify(888, builder)
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