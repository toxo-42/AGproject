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

  // ── RAW 원시 샘플 CSV 로깅 (Phase A/E: 학습 데이터 + 증거자료) ──────────
  // 첫 윈도우가 찰 때 lazy 로 세션 파일을 연다(연결 실패 시 빈 파일 안 남김).
  // 저장 위치: filesDir/logs/pedal_yyyyMMdd_HHmmss.csv (권한 불필요, adb pull 로 회수)
  // 컬럼: date,time,brake,accel,module_err,pedal_err,accel_exceed,label
  //   - 기록 주기는 200Hz 원시 샘플 전부가 아니라 **판정 윈도우 하나당 1행(4Hz)**이다.
  //     pedal_err/accel_exceed 가 애초에 4Hz 단위라 200Hz로 찍어도 값이 반복될 뿐이고,
  //     증거자료로서 사람이 열어볼 수 있는 크기가 더 중요하다고 판단(2026-07-13 사용자 결정,
  //     처음엔 200Hz 전부 남겼다가 "초당 데이터가 너무 많다"는 피드백으로 축소).
  //     brake/accel 은 그 윈도우의 마지막 샘플 값을 대표값으로 쓴다.
  //   - date/time: 사람이 읽을 수 있는 형식(원시 epoch 대신).
  //   - module_err: 그 순간 미해결 장치 오류가 있었는지("module_err"/"none",
  //     isErrorDialogShowing 그대로). 0/1 대신 문자열로 남겨 바로 알아볼 수 있게 함(2026-07-13).
  //   - pedal_err: 그 윈도우가 오조작으로 판정됐는지("pedal_err"/"none") — accel이 임계값을
  //     넘고 brake는 거의 안 밟힌 상태로 잡혔다는 뜻(judge.py 의 misop과 동일 개념, 컬럼명만
  //     사용자 요청으로 pedal_err로 통일).
  //   - accel_exceed: 대표 accel 값이 그 순간 개인화 임계값(accel_high)을 넘은 만큼
  //     (안 넘었거나 아직 캘리브레이션 전이면 0).
  //   - label: 수동 라벨링(개발자용 지도학습 정답, Phase A) 그대로 유지.
  //
  // BLE notify 콜백은 여러 바인더 스레드에서 올라온다 → BufferedWriter 접근은 전부 csvLock 아래에서.
  // csvClosed 는 종료 후 뒤늦게 도착한 콜백이 새 세션 파일을 되살리는 것을 막는다.
  private val csvLock = Any()
  private var csvWriter: java.io.BufferedWriter? = null
  private var csvClosed = false
  private val csvDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private val csvTimeFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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

  // ── 연속형 개인화 캘리브레이션 상태 ──────────────────────────────
  // 계산된 임계값이 있으면(null 이 아니면) judgeWindow 가 profile 대신 이걸 쓴다.
  // 이전 세션에 캘리브레이션한 값이 있으면 연결 시점에 prefs 에서 복원해 재사용한다
  // (Phase C: "매번 재분류 X").
  @Volatile private var calibratedThresholdsJson: String? = null

  // calibratedThresholdsJson 에서 accel_high 만 미리 파싱해둔 캐시.
  // writeRawCsv 가 200Hz 로 호출되는데 그때마다 JSON을 파싱하면 낭비라 갱신 시점에만 파싱한다.
  @Volatile private var currentAccelHigh: Double? = null

  // 캘리브레이션 수집 중에만 true. BLE 콜백 스레드가 쓰고 읽는다(단일 스레드 흐름이라 lock 불필요 —
  // sampleBuffer 와 달리 이 리스트는 judgeWindow 와 공유되지 않는다).
  @Volatile private var isCalibrating = false
  private val calibrationBuffer = ArrayList<List<Double>>()
  private var calibrationStartMs = 0L

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

    // ── 연속형 개인화 캘리브레이션 (Phase B, calibration.py 대응) ──────────
    // 3단계 프로필(discrete) 방식은 실측에서 경계가 불안정해 폐기했다(§진행상황_및_로드맵.md
    // Phase B). 캘리브레이션 세션에서 뽑은 accel_active_p90 + 오프셋으로 개인별 accel_high 를
    // 직접 계산하는 이 방식만 쓴다. 캘리브레이션 전에는 판정 자체를 하지 않는다(judgeWindow 참고).
    const val ACTION_START_CALIBRATION = "ACTION_START_CALIBRATION"
    const val ACTION_CALIBRATION_DONE = "ACTION_CALIBRATION_DONE"
    const val ACTION_CLEAR_CALIBRATION = "ACTION_CLEAR_CALIBRATION"
    const val EXTRA_THRESHOLDS_JSON = "THRESHOLDS_JSON"
    const val PREF_CALIBRATED_THRESHOLDS = "CALIBRATED_THRESHOLDS_JSON"
    const val CALIBRATION_DURATION_MS = 30_000L

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

    // 패턴 파악(연속형 캘리브레이션) 시작. 이미 감시 중인 세션에 바로 적용되며 재연결이 필요 없다.
    if (intent?.action == ACTION_START_CALIBRATION) {
      startCalibration()
      return START_NOT_STICKY
    }

    // 캘리브레이션 초기화 — 삭제 후 재설치한 것처럼 되돌린다. 다시 패턴 파악하기 전까지
    // judgeWindow 가 판정을 건너뛰므로(calibratedThresholdsJson == null) 오조작 감지도 꺼진다.
    if (intent?.action == ACTION_CLEAR_CALIBRATION) {
      applyCalibratedThresholds(null)
      getSharedPreferences("AgPrefs", MODE_PRIVATE).edit()
        .remove(PREF_CALIBRATED_THRESHOLDS)
        .apply()
      Log.i(tag, "캘리브레이션 초기화 — 판정 중단 상태로 복귀")
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

    // 이전에 캘리브레이션해 둔 임계값이 있으면 복원 — 매 주행마다 다시 캘리브레이션할 필요 없다.
    applyCalibratedThresholds(readCalibratedThresholds())
    if (calibratedThresholdsJson != null) {
      Log.i(tag, "캘리브레이션된 임계값 복원: $calibratedThresholdsJson")
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
      val file = java.io.File(dir, "pedal_${ts}.csv")
      java.io.BufferedWriter(java.io.FileWriter(file, true)).also {
        it.write("date,time,brake,accel,module_err,pedal_err,accel_exceed,label\n")
        csvWriter = it
        Log.i(tag, "RAW 로깅 시작: ${file.absolutePath}")
      }
    } catch (e: Exception) {
      Log.e(tag, "CSV 로깅 파일 생성 실패: ${e.message}", e)
      null
    }
  }

  private fun readCalibratedThresholds(): String? =
    getSharedPreferences("AgPrefs", MODE_PRIVATE).getString(PREF_CALIBRATED_THRESHOLDS, null)

  // calibratedThresholdsJson 과 currentAccelHigh(캐시)를 항상 같이 갱신 — 따로 손대면
  // 둘이 어긋나서 CSV의 accel_exceed 가 실제 판정 임계값과 다른 값을 쓰게 된다.
  private fun applyCalibratedThresholds(json: String?) {
    calibratedThresholdsJson = json
    currentAccelHigh = try {
      json?.let { JSONObject(it).getDouble("accel_high") }
    } catch (e: Exception) {
      null
    }
  }

  // "패턴 파악" 시작 — 이후 CALIBRATION_DURATION_MS 동안 들어오는 원시 샘플을 모은다.
  // 시작하는 순간 기존 캘리브레이션 값을 즉시 '미설정' 상태로 되돌린다 —
  // 그래야 패턴 파악 중에 세게 밟아도 판정(및 경고)이 안 걸려서 데이터 수집이 편하다.
  private fun startCalibration() {
    calibrationBuffer.clear()
    calibrationStartMs = System.currentTimeMillis()
    isCalibrating = true
    applyCalibratedThresholds(null)
    Log.i(tag, "캘리브레이션 시작 (${CALIBRATION_DURATION_MS / 1000}초) — 완료 전까지 판정 중단")
  }

  // 캘리브레이션 종료 — 모은 샘플을 calibration.calibrate_thresholds_json 에 넘겨
  // 개인화된 임계값을 계산하고, 즉시 적용 + prefs 에 저장(다음 주행에도 재사용).
  private fun finishCalibration() {
    isCalibrating = false
    val samples = ArrayList(calibrationBuffer)
    calibrationBuffer.clear()

    if (samples.isEmpty()) {
      Log.w(tag, "캘리브레이션 실패: 수집된 샘플 없음")
      return
    }

    try {
      val samplesJson = org.json.JSONArray()
      for (sample in samples) samplesJson.put(org.json.JSONArray(sample))

      val thresholdsJson = Python.getInstance()
        .getModule("calibration")
        .callAttr("calibrate_thresholds_json", samplesJson.toString())
        .toString()

      applyCalibratedThresholds(thresholdsJson)
      getSharedPreferences("AgPrefs", MODE_PRIVATE).edit()
        .putString(PREF_CALIBRATED_THRESHOLDS, thresholdsJson)
        .apply()

      Log.i(tag, "캘리브레이션 완료 (${samples.size}샘플): $thresholdsJson")

      val intent = Intent(ACTION_CALIBRATION_DONE)
      intent.setPackage(packageName)
      intent.putExtra(EXTRA_THRESHOLDS_JSON, thresholdsJson)
      sendBroadcast(intent)
    } catch (e: Exception) {
      Log.e(tag, "캘리브레이션 계산 실패: ${e.message}", e)
    }
  }

  private fun writeRawCsv(recvMs: Long, accel: Double, brake: Double, label: Int) {
    synchronized(csvLock) {
      val w = ensureCsvWriterLocked() ?: return
      try {
        val d = Date(recvMs)
        // 0/1 대신 문자열로 남겨서 CSV를 열어봤을 때 바로 무슨 뜻인지 알 수 있게 한다.
        val moduleErr = if (isErrorDialogShowing) "module_err" else "none"
        val pedalErr = if (lastLoggedMisop == true) "pedal_err" else "none"
        val accelHighNow = currentAccelHigh
        val exceed = if (accelHighNow != null && accel > accelHighNow) accel - accelHighNow else 0.0
        // accel/brake/exceed 는 소수 4자리로 반올림(Q31 원본보다 촘촘할 필요 없음 → 줄 크기 안정).
        w.write(
          "${csvDateFormat.format(d)},${csvTimeFormat.format(d)}," +
            "${String.format(Locale.US, "%.4f", brake)},${String.format(Locale.US, "%.4f", accel)}," +
            "$moduleErr,$pedalErr,${String.format(Locale.US, "%.4f", exceed)},$label\n"
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
      if (isCalibrating) calibrationBuffer.add(listOf(accel, brake))

      liveAccels?.set(i, accel.toFloat())
      liveBrakes?.set(i, brake.toFloat())
    }

    if (isCalibrating && nowMs - calibrationStartMs >= CALIBRATION_DURATION_MS) {
      finishCalibration()
    }

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
      judgeWindow(window)   // lastLoggedMisop 를 이 윈도우 결과로 갱신 — 아래 CSV 기록이 최신값을 씀

      // CSV는 200Hz 전부가 아니라 판정 주기(4Hz, 윈도우 하나당 1행)로만 남긴다.
      // misop/accel_exceed 는 애초에 4Hz 판정 단위라 200Hz로 찍어도 값이 반복될 뿐이고,
      // 증거자료 목적상 사람이 열어볼 수 있는 크기가 더 중요하다고 판단(2026-07-13 사용자 결정).
      val last = window.last()
      writeRawCsv(nowMs, accel = last[0], brake = last[1], label = currentLabel)
      flushRawCsv()
    }
  }

  // 윈도우 하나를 Python judge_calibrated_json 에 넘겨 오조작 여부를 판정한다.
  //
  // 패턴 파악(캘리브레이션) 중이거나, 아직 한 번도 캘리브레이션한 적이 없으면
  // 판정 자체를 건너뛴다 — 그렇지 않으면 캘리브레이션 도중 세게 밟는 순간에
  // 오조작 경고가 떠서 데이터 수집이 불편해진다.
  // "임계값 미설정 = 아직 판정 안 함"이 지금 채택한 모델이다.
  private fun judgeWindow(window: List<List<Double>>) {
    if (isCalibrating) return
    val calibrated = calibratedThresholdsJson ?: return

    try {
      // 윈도우를 JSON 문자열로 직렬화해 전달 (Chaquopy ArrayList 변환 이슈 회피)
      val samplesJson = org.json.JSONArray()
      for (sample in window) samplesJson.put(org.json.JSONArray(sample))

      val resultJson = Python.getInstance()
        .getModule("judge")
        .callAttr("judge_calibrated_json", samplesJson.toString(), calibrated)
        .toString()
      val result = JSONObject(resultJson)
      val misop = result.getBoolean("misop")
      val score = result.getDouble("score")
      val appliedLabel = result.getString("profile")   // 항상 "personalized" (judge_calibrated_json 고정값)

      // misop 결과는 lastLoggedMisop 에 남겨서 handleRawData 의 CSV 기록(같은 윈도우, 4Hz)이
      // 방금 계산된 값을 그대로 쓴다.

      // 판정은 4Hz 로 나온다. 상태가 바뀌는 순간(정상<->오조작)은 항상 남기고,
      // 변화가 없으면 살아있다는 표시로 주기적 하트비트만 남긴다.
      val nowMs = System.currentTimeMillis()
      if (misop != lastLoggedMisop) {
        Log.i(tag, "판정 변화: misop=$misop score=$score profile=$appliedLabel")
        lastLoggedMisop = misop
        lastJudgeLogMs = nowMs
      } else if (nowMs - lastJudgeLogMs >= JUDGE_LOG_INTERVAL_MS) {
        Log.d(tag, "판정: misop=$misop score=$score profile=$appliedLabel")
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

    // fullScreenIntent는 화면이 꺼져있거나 잠겨있을 때만 자동 실행되고,
    // 화면을 보고 있는 상태(포그라운드)에서는 배너 알림으로만 뜬다.
    // 오조작은 화면을 보고 있는 상태에서도 무조건 전환돼야 하므로 직접 실행도 병행한다.
    val forceIntent = Intent(this, CriticalActivity::class.java)
    forceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(forceIntent)
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