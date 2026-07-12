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
  private lateinit var btnLabelMisop: MaterialButton
  private lateinit var rgStyle: android.widget.RadioGroup

  private var calibrationTimer: CountDownTimer? = null
  private var devMode = false
  private var labelingMisop = false

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
    btnLabelMisop = findViewById(R.id.btnLabelMisop)
    rgStyle = findViewById(R.id.rgStyle)

    btnCalibrate.setOnClickListener { startCalibrationFlow() }
    btnResetCalibration.setOnClickListener { confirmResetCalibration() }

    devMode = intent.getBooleanExtra(EXTRA_DEV_MODE, false)
    if (devMode) {
      // dev mode 에서는 "패턴 파악"(연속형 캘리브레이션) 대신 강/보통/약 스타일 라벨링을 쓴다.
      // INVISIBLE 로 숨겨 tvLegend 등 나머지 레이아웃 제약이 흔들리지 않게 한다(공간은 유지).
      btnCalibrate.visibility = android.view.View.INVISIBLE
      btnCalibrate.isEnabled = false
      btnResetCalibration.visibility = android.view.View.INVISIBLE
      btnResetCalibration.isEnabled = false

      btnLabelMisop.visibility = android.view.View.VISIBLE
      btnLabelMisop.setOnClickListener { toggleMisopLabel() }

      rgStyle.visibility = android.view.View.VISIBLE
      rgStyle.setOnCheckedChangeListener { _, checkedId ->
        val style = when (checkedId) {
          R.id.rbStyleStrong -> BleService.STYLE_STRONG
          R.id.rbStyleNormal -> BleService.STYLE_NORMAL
          R.id.rbStyleWeak -> BleService.STYLE_WEAK
          else -> BleService.STYLE_UNSET
        }
        setStyleLabel(style)
      }
    }

    showExistingCalibration()   // 이미 캘리브레이션돼 있으면 그 값을 그래프에 바로 반영
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
  }

  override fun onPause() {
    super.onPause()
    // 화면을 벗어나면 50Hz 브로드캐스트를 끈다.
    setLiveStream(false)
    // 라벨링 중이었다면 다음 세션에 새지 않도록 정상/미설정으로 되돌린다.
    if (devMode) {
      if (labelingMisop) setMisopLabel(false)
      setStyleLabel(BleService.STYLE_UNSET)
    }
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

  private fun toggleMisopLabel() {
    setMisopLabel(!labelingMisop)
  }

  private fun setMisopLabel(misop: Boolean) {
    labelingMisop = misop
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_SET_LABEL
      putExtra(BleService.EXTRA_LABEL, if (misop) BleService.LABEL_MISOP else BleService.LABEL_NORMAL)
    })
    if (misop) {
      btnLabelMisop.setText(R.string.btn_label_misop)
      btnLabelMisop.backgroundTintList = android.content.res.ColorStateList.valueOf(
        androidx.core.content.ContextCompat.getColor(this, R.color.red_error)
      )
    } else {
      btnLabelMisop.setText(R.string.btn_label_normal)
      btnLabelMisop.backgroundTintList = android.content.res.ColorStateList.valueOf(
        androidx.core.content.ContextCompat.getColor(this, R.color.text_hint)
      )
    }
  }

  private fun setStyleLabel(style: String) {
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_SET_STYLE
      putExtra(BleService.EXTRA_STYLE, style)
    })
  }

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
    val json = getSharedPreferences("AgPrefs", MODE_PRIVATE)
      .getString(BleService.PREF_CALIBRATED_THRESHOLDS, null) ?: return
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
