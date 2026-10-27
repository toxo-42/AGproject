package com.example.agproject

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.util.Locale

/**
 * 운전 성향 진단 화면 (Phase F-4, 설정 → 운전 성향 진단).
 *
 * BleService 가 주행 중 누적해 prefs 에 저장한 성향 상태(peob.style.StyleTracker)를 읽어
 * Python 보고서(report_json)를 그대로 그린다 — 지표 계산·진단은 전부 Python, 여기선 표시만.
 * 상태 저장은 1분 주기라 주행 중이면 최대 1분 늦은 값이 보인다.
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
  }

  override fun onResume() {
    super.onResume()
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val demo = CalibrationPrefs.isStyleDemo(prefs)
    val report = loadReport(CalibrationPrefs.styleState(prefs) ?: "", demo)
    if (report == null) {
      tvLabel.setText(R.string.style_no_reference)
      return
    }
    showResult(report)
    showMetrics(report)
    showNote(report, demo)
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
