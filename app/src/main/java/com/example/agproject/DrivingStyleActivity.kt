package com.example.agproject

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.util.Locale

/**
 * 운전 성향 진단 화면 (Phase F-4, 설정 → 운전 성향 진단).
 *
 * 감시 중이면 BleService 가 엑셀 이벤트마다 공개하는 최신 보고서(liveStyleReportJson)를 실시간으로 그리고
 * (ACTION_LEARNING_PROGRESS 수신), 꺼져 있으면 prefs 에 저장된 성향 상태로 Python 보고서(report_json)를 만든다.
 * 지표 계산·진단은 전부 Python, 여기선 표시만.
 * 안전 판정과 분리된 기능이다(진단 결과는 경고에 쓰이지 않는다).
 */
class DrivingStyleActivity : AppCompatActivity() {

  private val tag = "DrivingStyleActivity"

  private lateinit var tvLabel: TextView
  private lateinit var tvDesc: TextView
  private lateinit var progress: LinearProgressIndicator
  private lateinit var tvMetricsTitle: TextView
  private lateinit var layoutMetrics: LinearLayout
  private lateinit var tvNote: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_driving_style)
    tvLabel = findViewById(R.id.tvStyleLabel)
    tvDesc = findViewById(R.id.tvStyleDesc)
    progress = findViewById(R.id.progressStyle)
    tvMetricsTitle = findViewById(R.id.tvMetricsTitle)
    layoutMetrics = findViewById(R.id.layoutMetrics)
    tvNote = findViewById(R.id.tvStyleNote)
    findViewById<View>(R.id.btnResetStyle).setOnClickListener { confirmReset() }
  }

  private val progressReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == BleService.ACTION_LEARNING_PROGRESS) refresh()
    }
  }

  override fun onResume() {
    super.onResume()
    val filter = IntentFilter(BleService.ACTION_LEARNING_PROGRESS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(progressReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
      ContextCompat.registerReceiver(this, progressReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    refresh()
  }

  override fun onPause() {
    super.onPause()
    try {
      unregisterReceiver(progressReceiver)
    } catch (_: IllegalArgumentException) {
      // 이미 해제됨
    }
  }

  private fun refresh() {
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val demo = CalibrationPrefs.isStyleDemo(prefs)
    val report = BleService.state.liveStyleReportJson?.let { runCatching { JSONObject(it) }.getOrNull() }
      ?: loadReport(CalibrationPrefs.styleState(prefs) ?: "", demo)
    if (report == null) {
      tvLabel.setText(R.string.style_no_reference)
      return
    }
    showResult(report)
    showMetrics(report)
    showNote(report, demo)
  }

  private fun confirmReset() {
    androidx.appcompat.app.AlertDialog.Builder(this)
      .setTitle(R.string.title_reset_style_confirm)
      .setMessage(R.string.msg_reset_style_confirm)
      .setPositiveButton(R.string.btn_reset_style) { _, _ -> resetStyle() }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  // prefs 는 여기서 바로 지워 화면을 즉시 갱신하고, 감시 중이면 서비스 메모리의 누적기도 비우게 알린다
  // (안 그러면 1분 뒤 서비스가 이전 누적을 다시 저장해 되살아난다).
  private fun resetStyle() {
    CalibrationPrefs.clearStyleState(getSharedPreferences("AgPrefs", MODE_PRIVATE))
    if (BleService.state.monitor.isActive) {
      startService(android.content.Intent(this, BleService::class.java).apply { action = BleService.ACTION_RESET_STYLE })
    }
    android.widget.Toast.makeText(this, R.string.msg_reset_style_done, android.widget.Toast.LENGTH_SHORT).show()
    refresh()
  }

  private fun loadReport(stateJson: String, demo: Boolean): JSONObject? = try {
    if (!Python.isStarted()) Python.start(AndroidPlatform(this))
    val tracker = Python.getInstance().getModule("peob.style").callAttr("load_tracker", stateJson)
    JSONObject(tracker.callAttr("report_json", demo).toString())
  } catch (e: Exception) {
    Log.e(tag, "성향 보고서 생성 실패: ${e.message}", e)
    null
  }

  private fun showResult(report: JSONObject) {
    val label = report.optString("label").takeIf { report.getBoolean("ready") && it.isNotEmpty() && it != "null" }
    if (label == null) {
      tvLabel.setText(R.string.style_collecting)
      val events = report.getInt("events")
      val gate = report.getInt("gate")
      tvDesc.text = getString(R.string.style_collecting_desc, minOf(events, gate), gate)
      progress.visibility = View.VISIBLE
      progress.max = gate
      progress.progress = minOf(events, gate)
      return
    }
    progress.visibility = View.GONE
    tvLabel.setText(styleNameRes(label))
    tvDesc.setText(styleDescRes(label))
  }

  // 지표마다 "나 0.42 · 초보 0.36 / 능숙 0.36 / 난폭 0.41". 내 값이 없는 지표(브레이크·GPS 미기록)는 숨긴다.
  private fun showMetrics(report: JSONObject) {
    layoutMetrics.removeAllViews()
    val metrics = report.optJSONObject("metrics")
    val centroids = report.optJSONObject("reference")?.optJSONObject("centroids")
    if (metrics == null) {
      tvMetricsTitle.visibility = View.GONE
      return
    }
    tvMetricsTitle.visibility = View.VISIBLE
    for ((key, nameRes) in METRIC_NAMES) {
      if (metrics.isNull(key)) continue
      val mine = metrics.getDouble(key)
      val refs = STYLE_IDS.mapNotNull { id ->
        centroids?.optJSONObject(id)?.takeIf { it.has(key) }?.let { "${getString(styleNameRes(id))} ${format(key, it.getDouble(key))}" }
      }
      addMetricRow(getString(nameRes), getString(R.string.style_metric_mine, format(key, mine)) +
        if (refs.isEmpty()) "" else " · " + refs.joinToString(" / "))
    }
  }

  private fun addMetricRow(name: String, values: String) {
    layoutMetrics.addView(TextView(this).apply {
      text = name
      setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
      setPadding(0, resources.getDimensionPixelSize(R.dimen.space_sm), 0, 0)
    })
    layoutMetrics.addView(TextView(this).apply {
      text = values
      setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
      alpha = 0.7f
    })
  }

  // 기준점이 개발자 연기 데이터로 만든 임시값이라는 사실은 개발 맥락 — 데모 모드에서만 학습 구간 수를 보인다.
  private fun showNote(report: JSONObject, demo: Boolean) {
    val ref = report.optJSONObject("reference") ?: return
    val chunks = ref.optJSONObject("chunks")
    val total = chunks?.keys()?.asSequence()?.sumOf { chunks.getInt(it) } ?: 0
    val date = ref.optString("trained_at").take(10)
    tvNote.text = if (demo) {
      val detail = STYLE_IDS.joinToString(" / ") { "${getString(styleNameRes(it))} ${chunks?.optInt(it) ?: 0}" }
      getString(R.string.style_demo_note, report.getInt("gate"), detail)
    } else {
      getString(R.string.style_reference_note, total, date)
    }
  }

  private fun format(key: String, value: Double): String = when (key) {
    "full_press_ratio" -> String.format(Locale.US, "%.0f%%", value * 100)
    "hold_sec", "switch_sec" -> String.format(Locale.US, "%.1f초", value)
    "harsh_accel_per_h", "harsh_decel_per_h" -> String.format(Locale.US, "%.1f회", value)
    else -> String.format(Locale.US, "%.2f", value)
  }

  private fun styleNameRes(id: String): Int = when (id) {
    "beginner" -> R.string.style_beginner
    "aggressive" -> R.string.style_aggressive
    else -> R.string.style_normal
  }

  private fun styleDescRes(id: String): Int = when (id) {
    "beginner" -> R.string.style_desc_beginner
    "aggressive" -> R.string.style_desc_aggressive
    else -> R.string.style_desc_normal
  }

  companion object {
    // 성향 id(peob.style 기준점 키 = CSV persona) — 화면 순서
    private val STYLE_IDS = listOf("beginner", "normal", "aggressive")

    // peob.style.StyleMetrics 필드 이름 → 표시 이름
    private val METRIC_NAMES = listOf(
      "rel_rise" to R.string.style_metric_rel_rise,
      "full_press_ratio" to R.string.style_metric_full_press_ratio,
      "hold_sec" to R.string.style_metric_hold_sec,
      "start_rel_rise" to R.string.style_metric_start_rel_rise,
      "switch_sec" to R.string.style_metric_switch_sec,
      "harsh_accel_per_h" to R.string.style_metric_harsh_accel_per_h,
      "harsh_decel_per_h" to R.string.style_metric_harsh_decel_per_h,
    )
  }
}
