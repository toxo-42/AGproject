package com.example.agproject

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * 데이터 수집 화면 (Phase A).
 *
 * FSR 압력값을 실시간 그래프로 보여주고, 그 위에 judge.py 의 오조작 임계값을
 * 붉은 선으로 겹쳐 그린다. "지금 내가 밟는 세기가 임계값 대비 어디쯤인가"를
 * 눈으로 확인할 수 있다.
 *
 * BleService 가 감시 중이어야 데이터가 흐른다(설정 화면에서 진입하기 전에 감시 시작 필요).
 */
class DataCollectActivity : AppCompatActivity() {

  private val tag = "DataCollectActivity"

  private lateinit var graph: PedalGraphView
  private lateinit var tvLiveValues: TextView
  private lateinit var btnCalibrate: MaterialButton
  private lateinit var btnResetCalibration: ImageButton
  private lateinit var tvCollectTitle: TextView

  private var calibrationTimer: CountDownTimer? = null
  private var devMode = false

  // 화면 갱신은 BLE 배치(50Hz)마다 오지만, 숫자 텍스트까지 50Hz 로 바꾸면
  // 읽을 수가 없다. 그래프만 매번 갱신하고 텍스트는 100ms 마다.
  private var lastTextUpdateMs = 0L

  private val liveReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        BleService.ACTION_LIVE_SAMPLES -> {
          val accels = intent.getFloatArrayExtra(BleService.EXTRA_ACCELS) ?: return
          val brakes = intent.getFloatArrayExtra(BleService.EXTRA_BRAKES) ?: return
          if (accels.isEmpty() || accels.size != brakes.size) return

          graph.pushBatch(accels, brakes)

          val now = System.currentTimeMillis()
          if (now - lastTextUpdateMs >= 100) {
            lastTextUpdateMs = now
            val a = accels.last()
            val b = brakes.last()
            // 급발진 방지가 목적이라 accel 임계값만 의미 있음 — brake는 임계값 없이 값만 표시.
            tvLiveValues.text = String.format(
              "accel %.3f (임계 %.2f)    brake %.3f",
              a, graph.getAccelHigh(), b
            )
          }
        }
        BleService.ACTION_CALIBRATION_DONE -> {
          val json = intent.getStringExtra(BleService.EXTRA_THRESHOLDS_JSON) ?: return
          onCalibrationDone(json)
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_data_collect)

    graph = findViewById(R.id.graphPedal)
    tvLiveValues = findViewById(R.id.tvLiveValues)
    btnCalibrate = findViewById(R.id.btnCalibrate)
    btnResetCalibration = findViewById(R.id.btnResetCalibration)
    tvCollectTitle = findViewById(R.id.tvCollectTitle)

    btnCalibrate.setOnClickListener { startCalibrationFlow() }
    btnResetCalibration.setOnClickListener { confirmResetCalibration() }

    devMode = intent.getBooleanExtra(EXTRA_DEV_MODE, false)
    if (devMode) {
      // dev mode: 페르소나 선택 + 재현 실험 버튼을 보인다. 페르소나를 고르면 프리셋 임계값을 쓰므로
      // 패턴 파악 버튼은 숨긴다("없음"일 때만 보임 — applyPersonaUi 참고).
      setupPersonaToggle()
      // 오조작 라벨은 수동 토글 대신 안내형 재현 실험(TrialActivity)이 구간마다 자동으로 찍는다
      findViewById<MaterialButton>(R.id.btnOpenTrial).apply {
        visibility = android.view.View.VISIBLE
        setOnClickListener { startActivity(Intent(this@DataCollectActivity, TrialActivity::class.java)) }
      }
    }

    // 그래프에 기존 캘리브레이션 값 반영은 onResume()에서 항상 수행한다(재진입 시 재동기화 포함).
  }

  override fun onResume() {
    super.onResume()
    val filter = IntentFilter().apply {
      addAction(BleService.ACTION_LIVE_SAMPLES)
      addAction(BleService.ACTION_CALIBRATION_DONE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(liveReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
      ContextCompat.registerReceiver(this, liveReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    setLiveStream(true)

    // 다른 앱으로 전환됐다 돌아온 경우를 포함해 매 resume마다 최신 상태로 재동기화한다.
    // 캘리브레이션은 화면과 무관하게 BleService 안에서 계속 진행되지만, 그동안 끝난
    // ACTION_CALIBRATION_DONE 브로드캐스트는 리시버가 꺼져 있어 유실됐을 수 있다 —
    // 그래서 그래프는 항상 prefs의 최신값으로 다시 반영하고, 카운트다운 UI도 진행 중이면
    // 남은 시간을 역산해 복구한다(2026-07-14, "cal 하다가 다른 앱 가면 멈춘 것처럼 보인다" 수정).
    showExistingCalibration()
    restoreCalibrationProgressIfRunning()
    applyPersonaUi()
  }

  override fun onPause() {
    super.onPause()
    // 화면을 벗어나면 50Hz 브로드캐스트를 끈다.
    setLiveStream(false)
    // 캘리브레이션 자체는 BleService 안에서 화면과 무관하게 계속 진행된다 —
    // 여기서 취소하는 건 화면에 남은 카운트다운 UI뿐.
    calibrationTimer?.cancel()
    try {
      unregisterReceiver(liveReceiver)
    } catch (_: IllegalArgumentException) {
      // 이미 해제됨
    }
  }

  // --- 서비스 통신 ---

  private fun setLiveStream(enabled: Boolean) {
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_SET_LIVE_STREAM
      putExtra(BleService.EXTRA_LIVE_STREAM, enabled)
    })
  }

  // --- 개발자 전용 오조작 라벨링 (AI 학습 데이터 수집) ---

  // --- 개발자 페르소나 ---

  // 페르소나 토글 — "없음" + Persona 목록으로 버튼을 만든다(페르소나 추가 시 여기 수정 불필요).
  // 고른 값은 화면을 나가도 유지된다(그래야 수집 화면 없이 주행해도 그 페르소나로 기록됨).
  // 감시 중이면 서비스가 그 페르소나의 캘리브레이션으로 즉시 바꾸게 알린다.
  private fun setupPersonaToggle() {
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val group = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.togglePersona)
    group.visibility = android.view.View.VISIBLE
    val current = CalibrationPrefs.currentPersona(prefs)
    val options: List<Persona?> = listOf(null) + Persona.entries
    options.forEach { persona ->
      val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        id = android.view.View.generateViewId()
        tag = persona?.id ?: Persona.NONE_ID
        setText(persona?.labelRes ?: R.string.persona_none)
      }
      group.addView(button, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
      if (persona == current) group.check(button.id)
    }
    group.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) return@addOnButtonCheckedListener
      val persona = Persona.fromId(group.findViewById<MaterialButton>(checkedId).tag as String)
      if (persona == CalibrationPrefs.currentPersona(prefs)) return@addOnButtonCheckedListener
      CalibrationPrefs.setCurrentPersona(prefs, persona)
      if (BleService.monitorState.isActive) {
        startService(Intent(this, BleService::class.java).apply { action = BleService.ACTION_SET_PERSONA })
      }
      // 페르소나마다 임계값이 다르므로 그래프 임계선·제목·버튼도 새로 반영
      graph.setThresholds(PedalGraphView.DEFAULT_ACCEL_HIGH, PedalGraphView.DEFAULT_BRAKE_LOW)
      showExistingCalibration()
      applyPersonaUi()
    }
  }

  // dev mode 에서 페르소나를 골라 뒀으면: 제목에 표시(지금 누구로 기록되는지) + 패턴 파악 버튼 숨김
  // (프리셋을 쓰므로 캘리브레이션이 필요 없고, 누르면 사용자 본인 캘리브레이션을 덮어쓰게 되므로).
  // INVISIBLE 로 숨겨 tvLegend 등 나머지 레이아웃 제약이 흔들리지 않게 한다.
  private fun applyPersonaUi() {
    val persona = currentDevPersona()
    tvCollectTitle.text = if (persona == null) {
      getString(R.string.title_data_collect)
    } else {
      getString(R.string.title_data_collect_persona, getString(R.string.title_data_collect), getString(persona.labelRes))
    }
    val visibility = if (persona == null) android.view.View.VISIBLE else android.view.View.INVISIBLE
    btnCalibrate.visibility = visibility
    btnResetCalibration.visibility = visibility
  }

  private fun currentDevPersona(): Persona? =
    if (devMode) CalibrationPrefs.currentPersona(getSharedPreferences("AgPrefs", MODE_PRIVATE)) else null

  // --- 연속형 개인화 캘리브레이션("패턴 파악") ---

  private fun startCalibrationFlow() {
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_START_CALIBRATION
    })

    btnCalibrate.isEnabled = false
    calibrationTimer?.cancel()
    calibrationTimer = object : CountDownTimer(BleService.CALIBRATION_DURATION_MS, 1_000) {
      override fun onTick(millisUntilFinished: Long) {
        val secondsLeft = (millisUntilFinished / 1000).toInt() + 1
        btnCalibrate.text = getString(R.string.btn_calibrate_running, secondsLeft)
      }

      override fun onFinish() {
        // 실제 적용 확인은 ACTION_CALIBRATION_DONE 브로드캐스트로 받지만,
        // 못 받는 경우(예: 계산 실패)에도 버튼은 복구되게 여기서도 되돌린다.
        btnCalibrate.isEnabled = true
        btnCalibrate.setText(R.string.btn_calibrate)
      }
    }.start()
  }

  // 캘리브레이션이 여전히 진행 중이면(다른 앱에 가 있던 동안 시작됐거나 계속되고 있으면)
  // 남은 시간을 역산해 카운트다운 UI를 복구한다. 이미 끝났으면(remaining <= 0) 곧
  // ACTION_CALIBRATION_DONE 이 오거나 이미 처리됐을 것이므로, showExistingCalibration()이
  // 반영한 최신 그래프 값으로 충분하고 여기선 아무것도 안 한다.
  private fun restoreCalibrationProgressIfRunning() {
    val startMs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
      .getLong(BleService.PREF_CALIBRATION_START_MS, -1L)
    if (startMs <= 0L) return

    val remaining = BleService.CALIBRATION_DURATION_MS - (System.currentTimeMillis() - startMs)
    if (remaining <= 0L) return

    btnCalibrate.isEnabled = false
    calibrationTimer?.cancel()
    calibrationTimer = object : CountDownTimer(remaining, 1_000) {
      override fun onTick(millisUntilFinished: Long) {
        val secondsLeft = (millisUntilFinished / 1000).toInt() + 1
        btnCalibrate.text = getString(R.string.btn_calibrate_running, secondsLeft)
      }

      override fun onFinish() {
        btnCalibrate.isEnabled = true
        btnCalibrate.setText(R.string.btn_calibrate)
      }
    }.start()
  }

  private fun onCalibrationDone(thresholdsJson: String) {
    calibrationTimer?.cancel()
    btnCalibrate.isEnabled = true
    btnCalibrate.setText(R.string.btn_calibrate)
    applyThresholdsToGraph(thresholdsJson)
    try {
      val accelHigh = JSONObject(thresholdsJson).getDouble("accel_high")
      Toast.makeText(this, getString(R.string.msg_calibration_done, accelHigh), Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
      Log.e(tag, "캘리브레이션 결과 파싱 실패: ${e.message}", e)
    }
  }

  // 이미 캘리브레이션된 값이 있으면(다음 주행 재사용 케이스) 화면 진입 시 바로 그래프에 반영한다.
  // 없으면 PedalGraphView 기본값(0.85/0.10)이 임시 참고선으로 남는다.
  private fun showExistingCalibration() {
    // 개발자 페르소나를 골라 뒀으면 그 프리셋 임계선을 그린다(brake_low 는 기본값)
    currentDevPersona()?.let {
      graph.setThresholds(it.accelHigh, PedalGraphView.DEFAULT_BRAKE_LOW)
      return
    }
    val json = CalibrationPrefs.userCalibration(getSharedPreferences("AgPrefs", MODE_PRIVATE)) ?: return
    applyThresholdsToGraph(json)
  }

  private fun applyThresholdsToGraph(thresholdsJson: String) {
    try {
      val th = JSONObject(thresholdsJson)
      graph.setThresholds(th.getDouble("accel_high"), th.getDouble("brake_low"))
      Log.i(tag, "임계값 그래프 반영: $thresholdsJson")
    } catch (e: Exception) {
      Log.e(tag, "임계값 파싱 실패: ${e.message}", e)
    }
  }

  // 초기화하면 오조작 감지가 다시 꺼지므로(캘리브레이션 전 = 판정 안 함) 확인을 받는다.
  private fun confirmResetCalibration() {
    androidx.appcompat.app.AlertDialog.Builder(this)
      .setTitle(R.string.title_reset_calibration_confirm)
      .setMessage(R.string.msg_reset_calibration_confirm)
      .setPositiveButton(R.string.btn_reset_calibration_confirm) { _, _ -> resetCalibration() }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun resetCalibration() {
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_CLEAR_CALIBRATION
    })
    graph.setThresholds(PedalGraphView.DEFAULT_ACCEL_HIGH, PedalGraphView.DEFAULT_BRAKE_LOW)
    Toast.makeText(this, R.string.msg_reset_calibration_done, Toast.LENGTH_SHORT).show()
  }

  companion object {
    // 데이터 수집 버튼을 길게 눌렀을 때만 true — 오조작 라벨링 UI 노출 여부를 결정한다.
    const val EXTRA_DEV_MODE = "EXTRA_DEV_MODE"
  }
}
