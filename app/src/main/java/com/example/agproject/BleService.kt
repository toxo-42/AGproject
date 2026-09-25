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
import com.chaquo.python.PyObject
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
  private var lastCnt = -1             // 직전 샘플 cnt (누락 검출용, -1 = 아직 없음)

  // 오조작 경고 상태기계(trigger→무장→지속시간→여유) — Python judge.MisopDetector 인스턴스.
  // 2026-09-24 Kotlin 필드(armed/consecutive/gap/shown)에서 Python 으로 이관(Kotlin=UI,
  // Python=판정 backend 방향). 임계값이 바뀔 때마다 applyCalibratedThresholds() 가 새로 만든다.
  // null = 캘리브레이션 전 → 판정 안 함.
  @Volatile private var detector: PyObject? = null

  // judgeWindow() 한 번의 결과. writeRawCsv() 가 같은 윈도우 행을 쓸 때 인자로 받는다.
  // fired 는 onPedalMisoperation() 이 실제로 호출된 그 윈도우에서만 true(2026-07-17 사용자 결정
  // — CSV의 pedal_err 는 "경고가 실제로 발동한 그 순간"만 남겨야 증거자료로서 의미가 있다).
  private data class JudgeResult(
    val score: Double,      // 레벨 조건 hits 비율 (high_ratio 와 비교)
    val rate: Double,       // 50ms 간격 엑셀 델타 최댓값 (accel_rate_high 와 비교)
    val armed: Boolean,
    val consecutive: Int,
    val fired: Boolean,
  )

  // ── RAW 원시 샘플 CSV 로깅 (Phase A/E: 학습 데이터 + 증거자료) ──────────
  // 첫 윈도우가 찰 때 lazy 로 세션 파일을 연다(연결 실패 시 빈 파일 안 남김).
  // 저장 위치: filesDir/logs/pedal_yyyyMMdd_HHmmss.csv (권한 불필요, adb pull 로 회수)
  // 컬럼: date,time,brake,accel,module_err,pedal_err,accel_high,accel_rate,accel_score,armed,
  //        consecutive,label,persona,trial_id,trial_type,trial_phase,accel_median
  //   (2026-07-17 accel_exceed 제거 + label→accel_high 교체 / 2026-07-18 label 재도입,
  //   accel_rate 추가, accel_score/armed/consecutive 추가(긴 홀드 오조작 미탐지 원인 진단용,
  //   §진행상황_및_로드맵.md 참고) / 2026-09-25 style(강/보통/약) → persona(Persona.id, 미선택 "none") 교체
  //   — 옛 CSV의 strong/normal/weak 는 각각 aggressive/normal/beginner 에 대응)
  //   / 2026-09-25 trial_id,trial_type,trial_phase 추가(안내형 재현 실험 — Trial.kt, 실험 밖이면 "none")
  //   / 2026-09-26 accel_median 추가(윈도우 엑셀 중앙값 — accel 은 마지막 샘플 하나라 판정의 "윈도우 50% 이상
  //   초과"와 어긋난다. 중앙값 >= 임계값이면 정확히 그 조건과 같아서 임계값 학습에 쓴다, prototype/train_thresholds.py)
  //   - 기록 주기는 200Hz 원시 샘플 전부가 아니라 **판정 윈도우 하나당 1행(4Hz)**이다.
  //     pedal_err/accel_high 가 애초에 4Hz 단위라 200Hz로 찍어도 값이 반복될 뿐이고,
  //     증거자료로서 사람이 열어볼 수 있는 크기가 더 중요하다고 판단(2026-07-13 사용자 결정,
  //     처음엔 200Hz 전부 남겼다가 "초당 데이터가 너무 많다"는 피드백으로 축소).
  //     brake/accel 은 그 윈도우의 마지막 샘플 값을 대표값으로 쓴다.
  //   - date/time: 사람이 읽을 수 있는 형식(원시 epoch 대신).
  //   - module_err: 그 순간 미해결 장치 오류가 있었는지("module_err"/"none",
  //     isErrorDialogShowing 그대로). 0/1 대신 문자열로 남겨 바로 알아볼 수 있게 함(2026-07-13).
  //   - pedal_err: onPedalMisoperation() 이 실제로 발동한 그 윈도우 한 행만 "pedal_err",
  //     나머지는 "none"(2026-07-17 사용자 결정). 예전엔 misop 레벨 조건이 유지되는 매 윈도우마다
  //     찍었는데, judge.py 에 변화율(미분) 트리거가 추가되면서 "완만하게 계속 깊게 밟는 상태"와
  //     "실제로 경고가 뜬 사건"이 달라졌다 — 후자만 남겨야 증거자료로서 의미가 있다.
  //     JudgeResult.fired 로 판정한다. ⚠️ 알고리즘이 자동으로 트리거한 것이라
  //     캘리브레이션 전(accel_high 미설정)이면 judgeWindow 가 판정 자체를 건너뛰어 항상 "none"이다
  //     — 아래 label(수동)과는 독립적인, 실시간 감지 결과일 뿐이라는 점에 주의.
  //   - accel_high: 그 순간 적용 중인 개인화 임계값(캘리브레이션 전이면 "none").
  //   - accel_rate: judge_calibrated_json 이 계산한 그 윈도우의 "rate"(RATE_LAG_SAMPLES=50ms
  //     떨어진 두 샘플 간 엑셀 델타의 최댓값) — accel_rate_high(급조작 트리거 임계값)와 직접
  //     비교되는 값 그 자체. 판정 자체가 스킵된 윈도우(캘리브레이션 중/전)는 "none"
  //     (2026-07-18 사용자 요청 — accel_rate_high 실측 튜닝에 쓸 실제 값 분포를 보기 위함).
  //   - accel_score: 그 윈도우의 "score"(레벨 조건 hits 비율) — high_ratio와 직접 비교되는
  //     값. 스킵된 윈도우는 "none".
  //   - armed: judgeWindow() 처리 후 그 시점의 무장 상태(judge.MisopDetector). 스킵된 윈도우는
  //     "none"(2026-09-24 상태기계 Python 이관 후 — 캘리브레이션 전/중엔 detector 자체가 없다).
  //   - consecutive: 그 시점의 연속 misop 윈도우 카운트. 스킵된 윈도우는 "none".
  //     accel_score/armed/consecutive 셋 다 "긴 홀드형 오조작 재현이 왜 반복적으로 안 잡히는지"
  //     진단용으로 추가(2026-07-18) — 로그캣 없이 CSV만으로 무장이 언제 풀렸는지 재구성 가능.
  //   - label: 수동 오조작 라벨링(개발자용 지도학습 정답, Phase A, 0=정상/1=오조작 재현).
  //     2026-07-17에 "실주행에서 항상 0으로만 찍힌다"는 이유로 accel_high로 교체하며 없앴는데,
  //     의도적으로 오조작을 재현하며 라벨을 남기는 세션(바로 이 currentLabel 자체가 그 정답)에서는
  //     그 판단이 틀렸다 — 라벨 누른 시점이 CSV 어디에도 안 남아서 지도학습 데이터가 통째로
  //     유실됐다(2026-07-18 사용자 피드백). accel_high 는 그대로 두고 label 을 별도 컬럼으로 복원.
  //   - style: 개발자 전용 강/보통/약 스타일 라벨(그대로 유지).
  //
  // BLE notify 콜백은 여러 바인더 스레드에서 올라온다 → BufferedWriter 접근은 전부 csvLock 아래에서.
  // csvClosed 는 종료 후 뒤늦게 도착한 콜백이 새 세션 파일을 되살리는 것을 막는다.
  private val csvLock = Any()
  private var csvWriter: java.io.BufferedWriter? = null
  private var csvClosed = false
  private val csvDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private val csvTimeFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  // ── 라벨링 (Phase A: 지도학습용 정답 수집) ────────────────────────
  // 개발자 재현 실험 화면(TrialActivity)이 ACTION_SET_TRIAL 로 구간마다 갱신한다(수동 토글은 2026-09-25 대체).
  // BLE 콜백 스레드가 읽고 UI 스레드가 쓰므로 @Volatile 필요.
  @Volatile private var currentLabel = LABEL_NORMAL

  // 진행 중인 재현 실험 회차/종류/구간 — 실험 밖이면 null. CSV 에 그대로 찍히고,
  // 실험 중 경고가 발동하면 전체화면 경고 대신 ACTION_TRIAL_FIRED 로 실험 화면에만 알린다.
  @Volatile private var currentTrialId: String? = null
  @Volatile private var currentTrialType: String? = null
  @Volatile private var currentTrialPhase: String? = null

  // 개발자 데이터 수집 페르소나(null = 사용자 본인) — 고르면 그 프리셋으로 판정하고, CSV persona 컬럼에 찍는다.
  // 감시 시작 시 prefs 에서 읽고, 개발자 수집 화면에서 바꾸면 ACTION_SET_PERSONA 로 다시 읽는다.
  @Volatile private var currentPersona: Persona? = null

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
    /**
     * 감시 상태 — 화면이 "지금 보호받고 있나"를 표시하는 단일 출처(MonitorState 참고).
     * 전역 상태지만 쓰기는 이 서비스(setMonitorState)만 하고 밖에서는 읽기만 한다.
     * prefs 가 아니라 메모리에 두는 이유: 프로세스가 죽으면 STOPPED 로 자동 초기화돼
     * "꺼졌는데 감시 중으로 보이는" 낡은 값이 남지 않는다.
     */
    @Volatile var monitorState: MonitorState = MonitorState.STOPPED
      private set
    const val ACTION_MONITOR_STATE = "ACTION_MONITOR_STATE"

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

    // ── 라벨링/재현 실험 (Phase A) ──────────────────────────────────
    // 실험 화면이 구간(준비/조작/휴식)이 바뀔 때마다 보낸다. EXTRA_TRIAL_ID 가 없으면 실험 종료.
    const val ACTION_SET_TRIAL = "ACTION_SET_TRIAL"
    const val EXTRA_TRIAL_ID = "TRIAL_ID"
    const val EXTRA_TRIAL_TYPE = "TRIAL_TYPE"
    const val EXTRA_TRIAL_PHASE = "TRIAL_PHASE"
    const val EXTRA_LABEL = "LABEL"
    // 실험 중 경고가 발동하면 보낸다(EXTRA_TRIAL_ID = 그때의 회차) — 실험 화면이 감지 회차를 센다.
    const val ACTION_TRIAL_FIRED = "ACTION_TRIAL_FIRED"

    const val LABEL_NORMAL = 0   // 정상 주행 구간
    const val LABEL_MISOP = 1    // 오조작 재현 구간

    // 개발자 페르소나 변경 알림 — 선택값은 CalibrationPrefs 에 이미 저장된 상태로 보낸다(서비스는 다시 읽기만).
    const val ACTION_SET_PERSONA = "ACTION_SET_PERSONA"

    // ── 연속형 개인화 캘리브레이션 (Phase B, calibration.py 대응) ──────────
    // 3단계 프로필(discrete) 방식은 실측에서 경계가 불안정해 폐기했다(§진행상황_및_로드맵.md
    // Phase B). 캘리브레이션 세션에서 뽑은 accel_active_p90 + 오프셋으로 개인별 accel_high 를
    // 직접 계산하는 이 방식만 쓴다. 캘리브레이션 전에는 판정 자체를 하지 않는다(judgeWindow 참고).
    const val ACTION_START_CALIBRATION = "ACTION_START_CALIBRATION"
    const val ACTION_CALIBRATION_DONE = "ACTION_CALIBRATION_DONE"
    const val ACTION_CLEAR_CALIBRATION = "ACTION_CLEAR_CALIBRATION"
    const val EXTRA_THRESHOLDS_JSON = "THRESHOLDS_JSON"
    // 캘리브레이션 값 자체는 사용자/페르소나별로 CalibrationPrefs 가 저장한다.
    const val CALIBRATION_DURATION_MS = 15_000L

    // 캘리브레이션 시작 시각(epoch ms) — 화면(DataCollectActivity)이 다른 앱으로 전환됐다가
    // 돌아왔을 때 "지금 캘리브레이션이 진행 중인지, 얼마나 남았는지"를 되살리는 용도(2026-07-14).
    // 캘리브레이션 자체는 이 값과 무관하게 BleService 안에서 계속 진행되지만, 진행 상황을
    // 보여주는 카운트다운 UI는 Activity 로컬 상태라 화면이 꺼졌다 돌아오면 복구할 방법이
    // 없었다 — 그래서 시작 시각만 prefs에 남겨 Activity가 onResume에서 역산하게 한다.
    const val PREF_CALIBRATION_START_MS = "CALIBRATION_START_MS"

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

    // 재현 실험 구간 변경. 이후 기록되는 CSV 행의 label/trial_* 컬럼이 이 값으로 찍힌다.
    if (intent?.action == ACTION_SET_TRIAL) {
      currentTrialId = intent.getStringExtra(EXTRA_TRIAL_ID)
      val active = currentTrialId != null
      currentTrialType = if (active) intent.getStringExtra(EXTRA_TRIAL_TYPE) else null
      currentTrialPhase = if (active) intent.getStringExtra(EXTRA_TRIAL_PHASE) else null
      currentLabel = if (active && intent.getIntExtra(EXTRA_LABEL, LABEL_NORMAL) == LABEL_MISOP) LABEL_MISOP else LABEL_NORMAL
      Log.i(tag, "실험 구간: id=$currentTrialId type=$currentTrialType phase=$currentTrialPhase label=$currentLabel")
      return START_NOT_STICKY
    }

    // 페르소나 변경 — 그 프리셋(또는 "없음"이면 사용자 캘리브레이션)으로 즉시 교체(패턴 파악 중이면 끝난 뒤 반영).
    if (intent?.action == ACTION_SET_PERSONA) {
      currentPersona = CalibrationPrefs.currentPersona(agPrefs())
      if (!isCalibrating) applyCalibratedThresholds(readCalibratedThresholds())
      Log.i(tag, "페르소나 변경: ${currentPersona?.id ?: Persona.NONE_ID}")
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
      CalibrationPrefs.clearUserCalibration(agPrefs())
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

    // 캘리브레이션 임계값이 있으면 복원 — 매 주행마다 다시 캘리브레이션할 필요 없다.
    currentPersona = CalibrationPrefs.currentPersona(agPrefs())
    applyCalibratedThresholds(readCalibratedThresholds())
    if (calibratedThresholdsJson != null) {
      Log.i(tag, "캘리브레이션된 임계값 복원: $calibratedThresholdsJson")
    }

    startForegroundServiceNotification("타겟 감시 중: $targetAddress")
    setMonitorState(MonitorState.CONNECTING)
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
    // UUID 불일치로 스스로 종료한 경우엔 그 사실을 화면에 남겨 두려고 WRONG_DEVICE 유지
    if (monitorState != MonitorState.WRONG_DEVICE) setMonitorState(MonitorState.STOPPED)
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
        // stopScan 전에 결과가 여러 번 올 수 있다 — 두 번째부터는 무시해 GATT 가 중복 생성되지 않게
        if (bluetoothGatt != null) return
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
        // 이미 정리한 GATT(서비스 종료 등)에서 뒤늦게 온 콜백이면 무시 — 종료된 서비스가 다시 스캔하지 않게
        if (gatt == null || gatt != bluetoothGatt) return
        Log.w(tag, "연결 끊김(status=$status). 재연결 시도...")
        setMonitorState(MonitorState.RECONNECTING)
        // 끊긴 GATT 를 닫고 비워야 startTargetScan() 의 "이미 연결 중" 가드를 통과한다.
        // (이전엔 안 비워서 한 번 끊기면 재연결이 영영 시작되지 않았음)
        gatt.close()
        bluetoothGatt = null
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
      setMonitorState(MonitorState.MONITORING)
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
        it.write(
          "date,time,brake,accel,module_err,pedal_err,accel_high,accel_rate,accel_score,armed,consecutive,label,persona,trial_id,trial_type,trial_phase,accel_median\n"
        )
        csvWriter = it
        Log.i(tag, "RAW 로깅 시작: ${file.absolutePath}")
      }
    } catch (e: Exception) {
      Log.e(tag, "CSV 로깅 파일 생성 실패: ${e.message}", e)
      null
    }
  }

  private fun agPrefs() = getSharedPreferences("AgPrefs", MODE_PRIVATE)

  // 판정에 쓸 임계값 JSON — 개발자 페르소나를 골랐으면 그 프리셋, 아니면 사용자 캘리브레이션(없으면 null).
  private fun readCalibratedThresholds(): String? {
    val persona = currentPersona ?: return CalibrationPrefs.userCalibration(agPrefs())
    return try {
      Python.getInstance().getModule("peob.calibration")
        .callAttr("preset_thresholds_json", persona.accelHigh).toString()
    } catch (e: Exception) {
      Log.e(tag, "페르소나 프리셋 생성 실패: ${e.message}", e)
      null
    }
  }

  // calibratedThresholdsJson, currentAccelHigh(캐시), detector 를 항상 같이 갱신 — 따로 손대면
  // CSV의 accel_high 나 판정 상태기계가 실제 임계값과 어긋난다.
  // 임계값이 바뀌면 상태기계도 새로 만든다(이전 임계값 기준의 무장/지속 상태는 이어가지 않는다).
  private fun applyCalibratedThresholds(json: String?) {
    calibratedThresholdsJson = json
    currentAccelHigh = try {
      json?.let { JSONObject(it).getDouble("accel_high") }
    } catch (e: Exception) {
      null
    }
    detector = try {
      json?.let { Python.getInstance().getModule("peob.judge").callAttr("new_detector", it) }
    } catch (e: Exception) {
      Log.e(tag, "판정 상태기계 생성 실패: ${e.message}", e)
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
    // DataCollectActivity가 다른 앱으로 전환됐다 돌아왔을 때 진행 상황을 되살릴 수 있게
    // 시작 시각을 prefs에 남긴다(위 PREF_CALIBRATION_START_MS 주석 참고).
    getSharedPreferences("AgPrefs", MODE_PRIVATE).edit()
      .putLong(PREF_CALIBRATION_START_MS, calibrationStartMs)
      .apply()
    Log.i(tag, "캘리브레이션 시작 (${CALIBRATION_DURATION_MS / 1000}초) — 완료 전까지 판정 중단")
  }

  // 캘리브레이션 종료 — 모은 샘플을 calibration.calibrate_thresholds_json 에 넘겨
  // 개인화된 임계값을 계산하고, 즉시 적용 + prefs 에 저장(다음 주행에도 재사용).
  private fun finishCalibration() {
    isCalibrating = false
    getSharedPreferences("AgPrefs", MODE_PRIVATE).edit()
      .remove(PREF_CALIBRATION_START_MS)
      .apply()
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
        .getModule("peob.calibration")
        .callAttr("calibrate_thresholds_json", samplesJson.toString())
        .toString()

      CalibrationPrefs.saveUserCalibration(agPrefs(), thresholdsJson)
      // 도중에 페르소나를 골랐으면 그 프리셋으로 판정을 이어간다
      applyCalibratedThresholds(readCalibratedThresholds())

      Log.i(tag, "캘리브레이션 완료(${samples.size}샘플): $thresholdsJson")

      // 다른 앱으로 전환된 상태라 ACTION_CALIBRATION_DONE 브로드캐스트를 놓쳐도(§DataCollectActivity
      // onResume 재동기화로 화면은 커버됨) 완료 사실 자체는 시스템 알림으로 바로 알려준다(2026-07-14).
      showHeadsUpNotification("PeOb", "패턴 파악이 완료되었습니다")

      val intent = Intent(ACTION_CALIBRATION_DONE)
      intent.setPackage(packageName)
      intent.putExtra(EXTRA_THRESHOLDS_JSON, thresholdsJson)
      sendBroadcast(intent)
    } catch (e: Exception) {
      Log.e(tag, "캘리브레이션 계산 실패: ${e.message}", e)
    }
  }

  // judged == null: 판정을 건너뛴 윈도우(캘리브레이션 중/전) — 판정 관련 컬럼은 "none".
  private fun writeRawCsv(
    recvMs: Long, accel: Double, brake: Double, accelMedian: Double, label: Int, judged: JudgeResult?,
  ) {
    synchronized(csvLock) {
      val w = ensureCsvWriterLocked() ?: return
      try {
        val d = Date(recvMs)
        // 0/1 대신 문자열로 남겨서 CSV를 열어봤을 때 바로 무슨 뜻인지 알 수 있게 한다.
        val moduleErr = if (isErrorDialogShowing) "module_err" else "none"
        // 경고가 실제로 발동한 그 윈도우 한 행에서만 "pedal_err".
        val pedalErr = if (judged?.fired == true) "pedal_err" else "none"
        val accelHighNow = currentAccelHigh
        val accelHighStr = if (accelHighNow != null) String.format(Locale.US, "%.4f", accelHighNow) else "none"
        val accelRateStr = judged?.let { String.format(Locale.US, "%.4f", it.rate) } ?: "none"
        val accelScoreStr = judged?.let { String.format(Locale.US, "%.4f", it.score) } ?: "none"
        val armedStr = judged?.armed?.toString() ?: "none"
        val consecutiveStr = judged?.consecutive?.toString() ?: "none"
        // accel/brake 는 소수 4자리로 반올림(Q31 원본보다 촘촘할 필요 없음 → 줄 크기 안정).
        w.write(
          "${csvDateFormat.format(d)},${csvTimeFormat.format(d)}," +
            "${String.format(Locale.US, "%.4f", brake)},${String.format(Locale.US, "%.4f", accel)}," +
            "$moduleErr,$pedalErr,$accelHighStr,$accelRateStr,$accelScoreStr,$armedStr,$consecutiveStr," +
            "$label,${currentPersona?.id ?: Persona.NONE_ID}," +
            "${currentTrialId ?: "none"},${currentTrialType ?: "none"},${currentTrialPhase ?: "none"}," +
            "${String.format(Locale.US, "%.4f", accelMedian)}\n"
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
      val judged = judgeWindow(window)

      // CSV는 200Hz 전부가 아니라 판정 주기(4Hz, 윈도우 하나당 1행)로만 남긴다.
      // misop/accel_exceed 는 애초에 4Hz 판정 단위라 200Hz로 찍어도 값이 반복될 뿐이고,
      // 증거자료 목적상 사람이 열어볼 수 있는 크기가 더 중요하다고 판단(2026-07-13 사용자 결정).
      val last = window.last()
      val sortedAccels = window.map { it[0] }.sorted()
      val accelMedian = sortedAccels[sortedAccels.size / 2]
      writeRawCsv(nowMs, accel = last[0], brake = last[1], accelMedian = accelMedian, label = currentLabel, judged = judged)
      flushRawCsv()
    }
  }

  // 윈도우 하나를 Python judge.MisopDetector 에 넘겨 판정하고, 경고 발동이면 알린다.
  // 판정 규칙과 상태(무장/지속/여유)는 전부 Python 쪽에 있다 — 여기는 호출·로그·경고 표시만 한다.
  //
  // 패턴 파악(캘리브레이션) 중이거나, 아직 한 번도 캘리브레이션한 적이 없으면
  // 판정 자체를 건너뛴다(null 반환) — 그렇지 않으면 캘리브레이션 도중 세게 밟는 순간에
  // 오조작 경고가 떠서 데이터 수집이 불편해진다.
  // "임계값 미설정 = 아직 판정 안 함"이 지금 채택한 모델이다.
  private fun judgeWindow(window: List<List<Double>>): JudgeResult? {
    if (isCalibrating) return null
    val det = detector ?: return null

    try {
      // 윈도우를 JSON 문자열로 직렬화해 전달 (Chaquopy ArrayList 변환 이슈 회피)
      val samplesJson = org.json.JSONArray()
      for (sample in window) samplesJson.put(org.json.JSONArray(sample))

      val result = JSONObject(det.callAttr("step_json", samplesJson.toString()).toString())
      val misop = result.getBoolean("misop")
      val judged = JudgeResult(
        score = result.getDouble("score"),
        rate = result.getDouble("rate"),
        armed = result.getBoolean("armed"),
        consecutive = result.getInt("consecutive"),
        fired = result.getBoolean("fired"),
      )

      // 판정은 4Hz 로 나온다. 상태가 바뀌는 순간(정상<->오조작)은 항상 남기고,
      // 변화가 없으면 살아있다는 표시로 주기적 하트비트만 남긴다.
      val nowMs = System.currentTimeMillis()
      if (misop != lastLoggedMisop) {
        Log.i(tag, "판정 변화: misop=$misop score=${judged.score} armed=${judged.armed}")
        lastLoggedMisop = misop
        lastJudgeLogMs = nowMs
      } else if (nowMs - lastJudgeLogMs >= JUDGE_LOG_INTERVAL_MS) {
        Log.d(tag, "판정: misop=$misop score=${judged.score} armed=${judged.armed}")
        lastJudgeLogMs = nowMs
      }

      if (judged.fired) {
        // 재현 실험 중엔 매 회차 전체화면 경고가 뜨면 실험이 끊긴다 — 실험 화면에만 알리고 CSV 에 남긴다.
        val trialId = currentTrialId
        if (trialId != null) {
          Log.i(tag, "실험 중 경고 발동(표시 생략): trial=$trialId")
          sendBroadcast(Intent(ACTION_TRIAL_FIRED).apply {
            setPackage(packageName)
            putExtra(EXTRA_TRIAL_ID, trialId)
          })
        } else {
          onPedalMisoperation()
        }
      }
      return judged
    } catch (e: Exception) {
      Log.e(tag, "judge 호출 실패: ${e.message}", e)
      return null
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

  // 상태가 실제로 바뀔 때만 방송한다. BLE 콜백 스레드에서도 불린다(@Volatile).
  private fun setMonitorState(state: MonitorState) {
    if (monitorState == state) return
    monitorState = state
    Log.i(tag, "감시 상태: $state")
    sendBroadcastToActivity(ACTION_MONITOR_STATE)
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
    setMonitorState(MonitorState.WRONG_DEVICE)
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