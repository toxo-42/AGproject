package com.example.agproject

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 오조작 경고 화면(activity_critical)의 절차적(procedural) 애니메이션.
 * "엑셀에서 발을 떼고 브레이크로 옮겨라"를 그림으로 지시한다.
 *
 * - 엑셀(앰버): value noise 로 미세하게 떨리고 붉게 발광 → 지금 눌려 있는 페달
 * - 쉐브론 화살표: 엑셀 → 브레이크로 흐름 → 발을 옮겨라
 * - 브레이크(흰색): 1Hz 로 숨쉬고 음파 링이 퍼짐 → 여기를 밟아라
 *
 * 구조: [PedalGuideMotion] = 시간 t(초) → 그릴 값(순수 함수), 이 클래스 = 값 → Canvas.
 * 모든 도형은 [LOGICAL_W]×[LOGICAL_H] 논리 좌표로 그리고, onDraw 에서 뷰 크기에 맞게 확대한다.
 * 시스템 "애니메이션 제거" 설정이 켜져 있으면 정지 화면 한 장만 그린다.
 */
class PedalGuideView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

  companion object {
    const val LOGICAL_W = 312f
    const val LOGICAL_H = 229f

    // 페달 배치 (논리 좌표). 실제 차량처럼 브레이크=왼쪽 가로형, 엑셀=오른쪽 세로형.
    private const val BRAKE_X = 78f
    private const val BRAKE_Y = 132f
    private const val BRAKE_W = 118f
    private const val BRAKE_H = 76f
    private const val ACCEL_X = 238f
    private const val ACCEL_Y = 118f
    private const val ACCEL_W = 70f
    private const val ACCEL_H = 150f

    private const val RING_COUNT = 3
    private const val RING_MAX_RADIUS = 90f
    private const val ACCEL_GLOW_RADIUS = 100f

    // 애니메이션을 끈 사용자에게 보여줄 정지 시점 — 화살표가 중간쯤, 링이 보이는 순간
    private const val STATIC_T = 0.45f
  }

  private val colorWhite = ContextCompat.getColor(context, R.color.critical_on_bg)
  private val colorAmber = ContextCompat.getColor(context, R.color.md_theme_tertiary)
  private val colorHot = ContextCompat.getColor(context, R.color.md_theme_error)

  private val labelTypeface: Typeface =
    Typeface.create(ResourcesCompat.getFont(context, R.font.pretendard), Typeface.BOLD)
  private val labelBrake = context.getString(R.string.critical_label_brake)
  private val labelAccel = context.getString(R.string.critical_label_accel)

  private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ContextCompat.getColor(context, R.color.critical_pedal_shadow)
  }
  private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ContextCompat.getColor(context, R.color.critical_pedal_groove)
  }
  private val pedalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = colorWhite
  }
  private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = colorWhite
    strokeWidth = 6f
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  // 엑셀 뒤 붉은 발광 — setShadowLayer 는 minSdk 26 하드웨어 가속에서 도형에 안 먹어서 방사형 그라데이션으로 대신한다
  private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    shader = RadialGradient(
      0f, 0f, ACCEL_GLOW_RADIUS,
      intArrayOf(colorHot, colorHot and 0x00FFFFFF),
      null, Shader.TileMode.CLAMP,
    )
  }
  private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = labelTypeface
    textSize = 15f
    textAlign = Paint.Align.CENTER
  }

  // onDraw 안에서 객체를 만들지 않도록 재사용
  private val rect = RectF()
  private val arrowPath = Path()

  private var startMs = 0L
  private val animate: Boolean get() = ValueAnimator.areAnimatorsEnabled()

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    startMs = SystemClock.uptimeMillis()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    // 화면이 다시 보이면 루프 재개 (안 보이는 동안은 onDraw 가 안 불려서 자연히 멈춘다)
    if (visibility == VISIBLE) invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val t = if (animate) (SystemClock.uptimeMillis() - startMs) / 1000f else STATIC_T

    // 논리 좌표계 → 뷰 크기 (비율 유지, 가운데 정렬)
    val scale = min(width / LOGICAL_W, height / LOGICAL_H)
    canvas.save()
    canvas.translate((width - LOGICAL_W * scale) / 2f, (height - LOGICAL_H * scale) / 2f)
    canvas.scale(scale, scale)

    drawBrakeRings(canvas, t)
    drawAccel(canvas, t)
    drawBrake(canvas, t)
    drawShiftArrow(canvas, t)
    drawLabel(canvas, labelBrake, BRAKE_X, BRAKE_Y + 70f, colorWhite)
    drawLabel(canvas, labelAccel, ACCEL_X, ACCEL_Y + 96f, colorAmber)

    canvas.restore()

    if (animate && isShown) postInvalidateOnAnimation()
  }

  /** 브레이크에서 퍼지는 음파 링 — "여기를 밟아라" */
  private fun drawBrakeRings(canvas: Canvas, t: Float) {
    for (k in 0 until RING_COUNT) {
      val phase = PedalGuideMotion.ringPhase(t, k, RING_COUNT)
      val radius = PedalGuideMotion.easeOutCubic(phase)
      ringPaint.alpha = (PedalGuideMotion.ringAlpha(phase) * 255).toInt()
      ringPaint.strokeWidth = 3f * (2f - radius) // 막 생긴 링은 두껍고, 퍼질수록 얇게
      canvas.drawCircle(BRAKE_X, BRAKE_Y, 10f + radius * RING_MAX_RADIUS, ringPaint)
    }
  }

  /** 엑셀: 떨림 + 붉은 발광 — "지금 이게 눌려 있다" */
  private fun drawAccel(canvas: Canvas, t: Float) {
    canvas.save()
    canvas.translate(ACCEL_X + PedalGuideMotion.jitterX(t), ACCEL_Y + PedalGuideMotion.jitterY(t))
    glowPaint.alpha = (PedalGuideMotion.accelGlow(t) * 255).toInt()
    canvas.drawCircle(0f, 0f, ACCEL_GLOW_RADIUS, glowPaint)
    drawPedal(canvas, ACCEL_W, ACCEL_H, colorAmber, grooves = 3, vertical = true)
    canvas.restore()
  }

  /** 브레이크: 1Hz 로 숨쉬듯 커졌다 작아짐 */
  private fun drawBrake(canvas: Canvas, t: Float) {
    val s = PedalGuideMotion.breathe(t, amplitude = 0.05f)
    canvas.save()
    canvas.translate(BRAKE_X, BRAKE_Y)
    canvas.scale(s, s)
    drawPedal(canvas, BRAKE_W, BRAKE_H, colorWhite, grooves = 3, vertical = false)
    canvas.restore()
  }

  /** 원점(0,0)을 중심으로 페달 하나를 그린다: 그림자 → 몸체 → 홈(groove) */
  private fun drawPedal(canvas: Canvas, w: Float, h: Float, color: Int, grooves: Int, vertical: Boolean) {
    rect.set(-w / 2 + 4f, -h / 2 + 6f, w / 2 + 4f, h / 2 + 6f)
    canvas.drawRoundRect(rect, 12f, 12f, shadowPaint)

    pedalPaint.color = color
    rect.set(-w / 2, -h / 2, w / 2, h / 2)
    canvas.drawRoundRect(rect, 12f, 12f, pedalPaint)

    val grooveHalfW = if (vertical) 4f else 6f
    val grooveInset = if (vertical) 16f else 14f
    for (i in 1..grooves) {
      val gx = -w / 2 + w * i / (grooves + 1)
      rect.set(gx - grooveHalfW, -h / 2 + grooveInset, gx + grooveHalfW, h / 2 - grooveInset)
      canvas.drawRoundRect(rect, 4f, 4f, groovePaint)
    }
  }

  /** 엑셀 → 브레이크로 흐르는 쉐브론 3개 — "발을 옮겨라" */
  private fun drawShiftArrow(canvas: Canvas, t: Float) {
    val fromX = ACCEL_X - 50f
    val toX = BRAKE_X + 72f
    val y = BRAKE_Y - 2f
    val move = PedalGuideMotion.footMove(t)
    val alpha = PedalGuideMotion.footAlpha(t)
    for (i in 0 until 3) {
      val p = (move - i * 0.12f).coerceIn(0f, 1f)
      val x = fromX + (toX - fromX) * p
      arrowPaint.alpha = (alpha * (1f - i * 0.28f) * 255).toInt()
      arrowPath.rewind()
      arrowPath.moveTo(x + 10f, y - 14f)
      arrowPath.lineTo(x - 4f, y)
      arrowPath.lineTo(x + 10f, y + 14f)
      canvas.drawPath(arrowPath, arrowPaint)
    }
  }

  private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, color: Int) {
    labelPaint.color = color
    canvas.drawText(text, x, y, labelPaint)
  }
}

/**
 * [PedalGuideView] 의 움직임 공식. 시간 t(초) → 값(0..1 등)만 계산하는 순수 함수라
 * Android 없이 단위 테스트할 수 있다. 박자는 [BEAT_SEC] = 1초(1Hz) —
 * 광과민성 기준(초당 3회 초과 깜빡임 금지)보다 충분히 느리게 잡았다.
 */
internal object PedalGuideMotion {
  const val BEAT_SEC = 1f
  private const val RING_PERIOD_SEC = BEAT_SEC * 1.5f
  private const val SHIFT_PERIOD_SEC = BEAT_SEC * 1.6f
  private const val SHIFT_MOVE_PORTION = 0.7f // 한 주기 중 70%는 이동, 나머지 30%는 제자리에서 사라짐
  private const val JITTER_SPEED = 9f
  private const val JITTER_PX = 3f

  /** k번째 링의 진행도(0..1). 링들이 주기 안에서 균등한 간격으로 어긋나 계속 이어져 보인다. */
  fun ringPhase(t: Float, k: Int, count: Int): Float = fract(t / RING_PERIOD_SEC + k.toFloat() / count)

  /** 링 투명도: 퍼질수록 빠르게 사라짐 */
  fun ringAlpha(phase: Float): Float = (1f - phase) * (1f - phase)

  /** 화살표 이동 진행도(0=엑셀 쪽, 1=브레이크 쪽) */
  fun footMove(t: Float): Float {
    val p = fract(t / SHIFT_PERIOD_SEC)
    return easeInOutSine((p / SHIFT_MOVE_PORTION).coerceIn(0f, 1f))
  }

  /** 화살표 투명도: 이동 중엔 1, 도착 후 사라짐 */
  fun footAlpha(t: Float): Float {
    val p = fract(t / SHIFT_PERIOD_SEC)
    return if (p < SHIFT_MOVE_PORTION) 1f else 1f - (p - SHIFT_MOVE_PORTION) / (1f - SHIFT_MOVE_PORTION)
  }

  /** 엑셀 떨림 오프셋(논리 px). 난수 대신 value noise 라 부드럽고 결정적이다. */
  fun jitterX(t: Float): Float = (noise1(t * JITTER_SPEED) - 0.5f) * JITTER_PX
  fun jitterY(t: Float): Float = (noise1(t * JITTER_SPEED + 100f) - 0.5f) * JITTER_PX

  /** 엑셀 발광 세기(0.55..1), 박자에 맞춰 맥동 */
  fun accelGlow(t: Float): Float {
    val s = sin(t * 2f * PI.toFloat() / BEAT_SEC)
    return 0.55f + 0.45f * s * s
  }

  /** 박자에 맞춘 숨쉬기 배율 (1 ± amplitude) */
  fun breathe(t: Float, amplitude: Float): Float = 1f + amplitude * sin(t * 2f * PI.toFloat() / BEAT_SEC)

  fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

  private fun easeInOutSine(x: Float): Float = -(cos(PI.toFloat() * x) - 1f) / 2f

  private fun fract(x: Float): Float = x - floor(x)

  /** 정수 격자점마다 0..1 의사 난수 (sin 해시). 큰 입력에서도 정밀도를 지키려고 Double 로 계산. */
  private fun hash(n: Double): Double {
    val s = sin(n * 127.1) * 43758.5453
    return s - floor(s)
  }

  /** 1D value noise: 격자점 해시값 사이를 smoothstep 으로 보간 */
  private fun noise1(x: Float): Float {
    val i = floor(x.toDouble())
    val f = x - i
    val u = f * f * (3 - 2 * f)
    return (hash(i) * (1 - u) + hash(i + 1) * u).toFloat()
  }
}
