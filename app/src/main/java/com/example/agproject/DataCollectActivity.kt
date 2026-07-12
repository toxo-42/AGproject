package com.example.agproject

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 데이터 수집 화면 (Phase A).
 *
 * FSR 압력값을 실시간 그래프로 보여주고, 그 위에 judge.py 의 오조작 임계값을
 * 붉은 선으로 겹쳐 그린다. "지금 내가 밟는 세기가 임계값 대비 어디쯤인가"를
 * 눈으로 확인하면서 라벨링할 수 있다.
 *
 * BleService 가 감시 중이어야 데이터가 흐른다(설정 화면에서 진입하기 전에 감시 시작 필요).
 */
class DataCollectActivity : AppCompatActivity() {

  private val tag = "DataCollectActivity"

  private lateinit var graph: PedalGraphView
  private lateinit var tvLiveValues: TextView
  private lateinit var tvLabelIndicator: TextView
  private lateinit var toggleProfile: MaterialButtonToggleGroup
  private lateinit var btnLabelMisop: MaterialButton
  private lateinit var btnExportCsv: ImageButton
  private lateinit var btnCalibrate: MaterialButton

  private var isLabelingMisop = false
  private var calibrationTimer: CountDownTimer? = null

  // 이미 적용된 프로필이면 Python 호출을 건너뛴다.
  // (onCreate 에서 toggleProfile.check() 가 리스너를 먼저 깨워 중복 호출되는 것을 막음)
  private var appliedProfile: String? = null

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
            tvLiveValues.text = String.format("accel %.3f    brake %.3f", a, b)
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
    tvLabelIndicator = findViewById(R.id.tvLabelIndicator)
    toggleProfile = findViewById(R.id.toggleProfile)
    btnLabelMisop = findViewById(R.id.btnLabelMisop)
    btnExportCsv = findViewById(R.id.btnExportCsv)
    btnCalibrate = findViewById(R.id.btnCalibrate)

    btnExportCsv.setOnClickListener { exportCsvLogs() }
    btnCalibrate.setOnClickListener { startCalibrationFlow() }

    toggleProfile.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) return@addOnButtonCheckedListener
      val profile = profileOf(checkedId)
      saveProfile(profile)
      applyThresholds(profile)   // 프로필을 바꾸면 붉은 임계선도 즉시 따라 움직인다
    }

    btnLabelMisop.setOnClickListener {
      isLabelingMisop = !isLabelingMisop
      sendLabelToService(isLabelingMisop)
      updateLabelUI()
    }

    val profile = loadProfile()
    toggleProfile.check(buttonIdOf(profile))
    applyThresholds(profile)
    updateLabelUI()
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
    // 화면을 벗어나면 50Hz 브로드캐스트를 끈다. 라벨링도 켜둔 채 나가지 않게 정리.
    setLiveStream(false)
    // 캘리브레이션 자체는 BleService 안에서 화면과 무관하게 계속 진행된다 —
    // 여기서 취소하는 건 화면에 남은 카운트다운 UI뿐.
    calibrationTimer?.cancel()
    if (isLabelingMisop) {
      isLabelingMisop = false
      sendLabelToService(false)
      updateLabelUI()
    }
    try {
      unregisterReceiver(liveReceiver)
    } catch (_: IllegalArgumentException) {
      // 이미 해제됨
    }
  }

  // --- 임계값: judge.py 를 단일 소스로 읽어온다 ---

  private fun applyThresholds(profile: String) {
    if (profile == appliedProfile) return
    try {
      if (!Python.isStarted()) Python.start(AndroidPlatform(this))
      val json = Python.getInstance()
        .getModule("judge")
        .callAttr("thresholds_json", profile)
        .toString()
      val th = JSONObject(json)
      graph.setThresholds(th.getDouble("accel_high"), th.getDouble("brake_low"))
      appliedProfile = profile
      Log.i(tag, "임계값 적용: profile=$profile $json")
    } catch (e: Exception) {
      // 실패해도 그래프 기본값(0.85/0.10)으로 계속 그린다.
      Log.e(tag, "임계값 조회 실패: ${e.message}", e)
    }
  }

  // --- 프로필 ---

  private fun profileOf(checkedId: Int): String = when (checkedId) {
    R.id.btnProfileStrong -> "strong"
    R.id.btnProfileWeak -> "weak"
    else -> "normal"
  }

  private fun buttonIdOf(profile: String): Int = when (profile) {
    "strong" -> R.id.btnProfileStrong
    "weak" -> R.id.btnProfileWeak
    else -> R.id.btnProfileNormal
  }

  private fun saveProfile(profile: String) {
    getSharedPreferences("AgPrefs", MODE_PRIVATE).edit()
      .putString(BleService.PREF_PROFILE, profile).apply()
    Log.i(tag, "프로필 저장: $profile")
  }

  private fun loadProfile(): String {
    val saved = getSharedPreferences("AgPrefs", MODE_PRIVATE)
      .getString(BleService.PREF_PROFILE, null)
    return if (saved in BleService.VALID_PROFILES) saved!! else BleService.DEFAULT_PROFILE
  }

  // --- 서비스 통신 ---

  private fun sendLabelToService(misop: Boolean) {
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_SET_LABEL
      putExtra(BleService.EXTRA_LABEL, if (misop) BleService.LABEL_MISOP else BleService.LABEL_NORMAL)
    })
  }

  private fun setLiveStream(enabled: Boolean) {
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_SET_LIVE_STREAM
      putExtra(BleService.EXTRA_LIVE_STREAM, enabled)
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

    try {
      val th = JSONObject(thresholdsJson)
      val accelHigh = th.getDouble("accel_high")
      val brakeLow = th.getDouble("brake_low")
      graph.setThresholds(accelHigh, brakeLow)
      Toast.makeText(this, getString(R.string.msg_calibration_done, accelHigh), Toast.LENGTH_LONG).show()
      Log.i(tag, "캘리브레이션 적용: $thresholdsJson")
    } catch (e: Exception) {
      Log.e(tag, "캘리브레이션 결과 파싱 실패: ${e.message}", e)
    }
  }

  // --- CSV 내보내기 (개발자용: 반복 학습 시 adb 없이 세션 CSV 전부를 한 번에 회수) ---

  private fun exportCsvLogs() {
    val logsDir = File(filesDir, "logs")
    val csvFiles = logsDir.listFiles { f -> f.extension == "csv" }
    if (csvFiles.isNullOrEmpty()) {
      Toast.makeText(this, getString(R.string.msg_export_empty), Toast.LENGTH_SHORT).show()
      return
    }

    val zipFile = File(cacheDir, "pedal_logs_${System.currentTimeMillis()}.zip")
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
      for (f in csvFiles) {
        zos.putNextEntry(ZipEntry(f.name))
        f.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
      }
    }

    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "application/zip"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(shareIntent, getString(R.string.title_export_chooser)))
  }

  // --- UI ---

  @SuppressLint("SetTextI18n")
  private fun updateLabelUI() {
    if (isLabelingMisop) {
      btnLabelMisop.setText(R.string.btn_label_misop_on)
      btnLabelMisop.setTextColor(getColor(R.color.red_error))
      btnLabelMisop.strokeColor = ColorStateList.valueOf(getColor(R.color.red_error))
      tvLabelIndicator.setText(R.string.label_indicator_on)
      tvLabelIndicator.setTextColor(getColor(R.color.red_error))
    } else {
      btnLabelMisop.setText(R.string.btn_label_misop)
      btnLabelMisop.setTextColor(getColor(R.color.text_gray))
      btnLabelMisop.strokeColor = ColorStateList.valueOf(getColor(R.color.text_hint))
      tvLabelIndicator.setText(R.string.label_indicator_off)
      tvLabelIndicator.setTextColor(getColor(R.color.text_hint))
    }
  }
}
