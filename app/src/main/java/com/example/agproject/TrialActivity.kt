package com.example.agproject

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * 안내형 재현 실험 화면(개발자 전용, 정차 중) — 개발자 수집 화면의 "재현 실험" 버튼으로 진입.
 * buildTrialSchedule() 이 만든 준비 → 조작 → 휴식 순서를 화면 안내로 재생하면서, 구간이 바뀔 때마다
 * BleService 에 ACTION_SET_TRIAL 을 보내 CSV label/trial_* 컬럼을 자동으로 찍게 한다.
 * 실험 중 경고가 발동하면 서비스가 ACTION_TRIAL_FIRED 로 알려주고, 끝나면 감지/오탐 회차를 요약한다.
 * 화면을 벗어나면(onPause) 실험은 중단된다 — 반쯤 찍힌 라벨이 다음 주행에 새지 않게.
 */
class TrialActivity : AppCompatActivity() {

  private lateinit var layoutSetup: android.view.View
  private lateinit var layoutRun: android.view.View
  private lateinit var layoutSummary: android.view.View
  private lateinit var tvTypeLabel: TextView
  private lateinit var tvTypeDesc: TextView
  private lateinit var tvReps: TextView
  private lateinit var tvTotalTime: TextView
  private lateinit var tvProgress: TextView
  private lateinit var tvPhase: TextView
  private lateinit var tvCountdown: TextView
  private lateinit var tvInstruction: TextView
  private lateinit var tvDetected: TextView
  private lateinit var tvSummaryType: TextView
  private lateinit var tvSummary: TextView
  private lateinit var tvSummaryDetail: TextView

  private var selectedType = TrialType.PANIC_SLAM
  private var reps = selectedType.defaultReps

  // 실행 중 상태 — runId 가 null 이면 실행 중 아님
  private var runId: String? = null
  private var steps: List<TrialStep> = emptyList()
  private var stepIndex = 0
  private var lastActionRep = 0
  private var timer: CountDownTimer? = null
  private val detectedReps = mutableSetOf<Int>()
  private var detectionReady = false

  private val firedReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      val trialId = intent?.getStringExtra(BleService.EXTRA_TRIAL_ID) ?: return
      val rep = repOf(trialId) ?: return
      detectedReps += rep
      if (steps.getOrNull(stepIndex)?.rep == rep) tvDetected.isVisible = true
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_trial)

    layoutSetup = findViewById(R.id.layoutSetup)
    layoutRun = findViewById(R.id.layoutRun)
    layoutSummary = findViewById(R.id.layoutSummary)
    tvTypeLabel = findViewById(R.id.tvTypeLabel)
    tvTypeDesc = findViewById(R.id.tvTypeDesc)
    tvReps = findViewById(R.id.tvReps)
    tvTotalTime = findViewById(R.id.tvTotalTime)
    tvProgress = findViewById(R.id.tvProgress)
    tvPhase = findViewById(R.id.tvPhase)
    tvCountdown = findViewById(R.id.tvCountdown)
    tvInstruction = findViewById(R.id.tvInstruction)
    tvDetected = findViewById(R.id.tvDetected)
    tvSummaryType = findViewById(R.id.tvSummaryType)
    tvSummary = findViewById(R.id.tvSummary)
    tvSummaryDetail = findViewById(R.id.tvSummaryDetail)

    val persona = CalibrationPrefs.currentPersona(getSharedPreferences("AgPrefs", MODE_PRIVATE))
    findViewById<TextView>(R.id.tvPersona).text =
      getString(R.string.trial_persona_line, getString(persona?.labelRes ?: R.string.persona_none))

    setupTypeChips()
    findViewById<MaterialButton>(R.id.btnRepsMinus).setOnClickListener { changeReps(-1) }
    findViewById<MaterialButton>(R.id.btnRepsPlus).setOnClickListener { changeReps(+1) }
    findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { startRun() }
    findViewById<MaterialButton>(R.id.btnStop).setOnClickListener { stopRun(completed = false) }
    findViewById<MaterialButton>(R.id.btnAgain).setOnClickListener { showSection(layoutSetup) }
    updateSetupUi()
  }

  override fun onResume() {
    super.onResume()
    val filter = IntentFilter(BleService.ACTION_TRIAL_FIRED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(firedReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
      ContextCompat.registerReceiver(this, firedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
  }

  override fun onPause() {
    super.onPause()
    if (runId != null) stopRun(completed = false)
    try {
      unregisterReceiver(firedReceiver)
    } catch (_: IllegalArgumentException) {
      // 이미 해제됨
    }
  }

  // --- ① 설정 ---

  // 실험 종류 칩 — TrialType 목록으로 만든다(종류 추가 시 여기 수정 불필요)
  private fun setupTypeChips() {
    val group = findViewById<ChipGroup>(R.id.chipGroupType)
    TrialType.entries.forEach { type ->
      val chip = Chip(this).apply {
        setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, MaterialR.style.Widget_Material3_Chip_Filter))
        id = android.view.View.generateViewId()
        tag = type
        setText(type.nameRes)
      }
      group.addView(chip)
      if (type == selectedType) chip.isChecked = true
    }
    group.setOnCheckedStateChangeListener { g, ids ->
      val type = ids.firstOrNull()?.let { g.findViewById<Chip>(it).tag as TrialType } ?: return@setOnCheckedStateChangeListener
      selectedType = type
      reps = type.defaultReps
      updateSetupUi()
    }
  }

  private fun changeReps(delta: Int) {
    reps = (reps + delta).coerceIn(TrialTiming.MIN_REPS, TrialTiming.MAX_REPS)
    updateSetupUi()
  }

  private fun updateSetupUi() {
    tvTypeLabel.setText(if (selectedType.isMisop) R.string.trial_label_misop else R.string.trial_label_normal)
    tvTypeDesc.setText(selectedType.instructionRes)
    tvReps.text = getString(R.string.trial_reps, reps)
    tvTotalTime.text = getString(R.string.trial_total_time, formatDuration(trialTotalDurationMs(selectedType, reps)))
  }

  private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return if (totalSec >= 60) getString(R.string.duration_min_sec, totalSec / 60, totalSec % 60)
    else getString(R.string.duration_sec, totalSec)
  }

  // --- ② 진행 ---

  private fun startRun() {
    // 연결돼 데이터가 들어오는 중이어야 CSV 에 기록된다
    if (BleService.monitorState != MonitorState.MONITORING) {
      Toast.makeText(this, R.string.trial_need_monitoring, Toast.LENGTH_LONG).show()
      return
    }
    detectionReady = CalibrationPrefs.isDetectionReady(getSharedPreferences("AgPrefs", MODE_PRIVATE))
    runId = SimpleDateFormat("HHmmss", Locale.US).format(Date())
    steps = buildTrialSchedule(selectedType, reps)
    stepIndex = 0
    lastActionRep = 0
    detectedReps.clear()
    showSection(layoutRun)
    runStep()
  }

  private fun runStep() {
    val step = steps.getOrNull(stepIndex) ?: return stopRun(completed = true)
    if (step.phase == TrialPhase.ACTION) lastActionRep = step.rep
    sendTrialState(step)

    tvProgress.text = getString(R.string.trial_progress, step.rep, reps)
    tvDetected.isVisible = step.rep in detectedReps
    val (phaseText, colorAttr) = when (step.phase) {
      TrialPhase.READY -> R.string.trial_phase_ready to MaterialR.attr.colorOnSurfaceVariant
      TrialPhase.ACTION -> R.string.trial_phase_action to
        if (selectedType.isMisop) MaterialR.attr.colorError else MaterialR.attr.colorPrimary
      TrialPhase.REST -> R.string.trial_phase_rest to MaterialR.attr.colorOutline
    }
    tvPhase.setText(phaseText)
    tvPhase.setTextColor(MaterialColors.getColor(tvPhase, colorAttr))
    tvInstruction.text = if (step.phase == TrialPhase.ACTION) getString(selectedType.instructionRes) else ""

    timer = object : CountDownTimer(step.durationMs, 100L) {
      override fun onTick(msLeft: Long) {
        tvCountdown.text = ceil(msLeft / 1000.0).toInt().toString()
      }

      override fun onFinish() {
        stepIndex++
        runStep()
      }
    }.start()
  }

  // 서비스에 지금 구간을 알린다 — 조작 구간만 실험 종류의 label, 준비·휴식은 0
  private fun sendTrialState(step: TrialStep) {
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_SET_TRIAL
      putExtra(BleService.EXTRA_TRIAL_ID, trialIdOf(step.rep))
      putExtra(BleService.EXTRA_TRIAL_TYPE, selectedType.id)
      putExtra(BleService.EXTRA_TRIAL_PHASE, step.phase.id)
      putExtra(BleService.EXTRA_LABEL, if (step.phase == TrialPhase.ACTION) selectedType.label else BleService.LABEL_NORMAL)
    })
  }

  private fun stopRun(completed: Boolean) {
    timer?.cancel()
    timer = null
    if (runId == null) return
    // 실험 종료 — trial_id 없이 보내면 서비스가 label/trial_* 를 none/정상으로 되돌린다
    startService(Intent(this, BleService::class.java).apply { action = BleService.ACTION_SET_TRIAL })
    runId = null
    if (!completed) Toast.makeText(this, R.string.trial_aborted, Toast.LENGTH_SHORT).show()
    showSummary(TrialSummary(selectedType, lastActionRep, detectedReps.toSet()))
  }

  // CSV trial_id — "시작시각-회차" (한 파일 안에서 실험마다 구분되게)
  private fun trialIdOf(rep: Int): String = "$runId-${String.format(Locale.US, "%02d", rep)}"

  private fun repOf(trialId: String): Int? {
    val id = runId ?: return null
    if (!trialId.startsWith("$id-")) return null
    return trialId.substringAfterLast('-').toIntOrNull()
  }

  // --- ③ 요약 ---

  private fun showSummary(summary: TrialSummary) {
    showSection(layoutSummary)
    tvSummaryType.text = getString(summary.type.nameRes)
    if (!detectionReady) {
      tvSummary.text = getString(R.string.trial_reps, summary.reps)
      tvSummaryDetail.setText(R.string.trial_summary_detection_off)
      return
    }
    if (summary.type.isMisop) {
      tvSummary.text = getString(R.string.trial_summary_misop, summary.reps, summary.detectedCount)
      tvSummaryDetail.text = summary.missedReps.takeIf { it.isNotEmpty() }
        ?.let { getString(R.string.trial_summary_missed, it.joinToString(", ")) } ?: ""
    } else {
      tvSummary.text = getString(R.string.trial_summary_normal, summary.reps, summary.detectedCount)
      tvSummaryDetail.text = ""
    }
  }

  private fun showSection(section: android.view.View) {
    layoutSetup.isVisible = section == layoutSetup
    layoutRun.isVisible = section == layoutRun
    layoutSummary.isVisible = section == layoutSummary
  }
}
