package com.example.agproject

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 페달 압력(accel/brake) 실시간 그래프.
 *
 * 외부 차트 라이브러리 없이 Canvas 로 직접 그린다. 필요한 게 라인 2개와
 * 임계선 2개뿐이라 의존성을 늘릴 이유가 없다.
 *
 * - Y축: 0.0 ~ 1.0 고정 (Q31 정규화 압력이라 범위가 이미 확정)
 * - X축: 최근 [CAPACITY] 샘플. 새 샘플이 오른쪽에서 들어와 왼쪽으로 흐른다.
 * - 붉은 실선  = accel_high (이 위로 올라가면 '엑셀 깊게 밟음')
 * - 붉은 점선  = brake_low  (이 아래면 '브레이크 거의 안 밟음')
 *
 * 오조작 판정은 '엑셀이 붉은 실선 위 && 브레이크가 붉은 점선 아래'가
 * 윈도우에서 일정 비율 이상일 때다. 그래서 두 선을 같은 붉은색으로 묶었다.
 */
class PedalGraphView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

  companion object {
    // 200Hz × 5초 = 1000샘플. 페달을 한 번 밟고 떼는 동작이 화면에 딱 들어온다.
    const val CAPACITY = 1000

    // 캘리브레이션 전/초기화 직후에 쓰는 임시 참고선(실제 판정 임계값이 아니라 눈대중 표시용).
    const val DEFAULT_ACCEL_HIGH = 0.85
    const val DEFAULT_BRAKE_LOW = 0.10
  }

  // 링 버퍼: head 가 다음에 덮어쓸 위치. size 가 CAPACITY 에 도달하면 계속 순환한다.
  private val accelBuf = FloatArray(CAPACITY)
  private val brakeBuf = FloatArray(CAPACITY)
  private var head = 0
  private var size = 0

  private var accelHigh = DEFAULT_ACCEL_HIGH.toFloat()
  private var brakeLow = DEFAULT_BRAKE_LOW.toFloat()

  private val accelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#00E676")   // accent_blue (실제로는 민트)
    style = Paint.Style.STROKE
    strokeWidth = 3f
  }
  private val brakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#40C4FF")
    style = Paint.Style.STROKE
    strokeWidth = 3f
  }
  private val accelHighPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#FF5252")
    style = Paint.Style.STROKE
    strokeWidth = 2f
  }
  private val brakeLowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#FF5252")
    style = Paint.Style.STROKE
    strokeWidth = 2f
    pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
  }
  private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#2A3540")
    style = Paint.Style.STROKE
    strokeWidth = 1f
  }
  private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#546E7A")
    textSize = 24f
  }

  private val accelPath = Path()
  private val brakePath = Path()

  /** judge.py 의 PROFILES 에서 읽어온 임계값으로 임계선 위치를 갱신한다. */
  fun setThresholds(accelHigh: Double, brakeLow: Double) {
    this.accelHigh = accelHigh.toFloat()
    this.brakeLow = brakeLow.toFloat()
    invalidate()
  }

  /** BLE 배치 하나(보통 4샘플)를 밀어 넣는다. 메인 스레드에서 호출할 것. */
  fun pushBatch(accels: FloatArray, brakes: FloatArray) {
    for (i in accels.indices) {
      accelBuf[head] = accels[i]
      brakeBuf[head] = brakes[i]
      head = (head + 1) % CAPACITY
      if (size < CAPACITY) size++
    }
    invalidate()
  }

  fun clear() {
    head = 0
    size = 0
    invalidate()
  }

  /** 링 버퍼의 i번째(0=가장 오래된 것) 실제 인덱스. */
  private fun idx(i: Int): Int =
    if (size < CAPACITY) i else (head + i) % CAPACITY

  /** 정규화 압력(0..1) -> 화면 Y 좌표. 위로 갈수록 큰 값. */
  private fun toY(v: Float, h: Float): Float = h - v.coerceIn(0f, 1f) * h

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val w = width.toFloat()
    val h = height.toFloat()

    // 가로 격자 (0.1 간격). 정수 반복으로 실수 누적 오차를 피한다.
    for (i in 0..10) {
      val g = i * 0.1f
      val y = toY(g, h)
      canvas.drawLine(0f, y, w, y, gridPaint)
      canvas.drawText(String.format("%.2f", g), 6f, y - 6f, labelPaint)
    }

    // 임계선 — 그래프 위에 얹혀야 하므로 파형보다 먼저 그린다.
    canvas.drawLine(0f, toY(accelHigh, h), w, toY(accelHigh, h), accelHighPaint)
    canvas.drawLine(0f, toY(brakeLow, h), w, toY(brakeLow, h), brakeLowPaint)

    if (size < 2) return

    // 가장 오래된 샘플이 왼쪽 끝, 최신 샘플이 오른쪽 끝.
    // 버퍼가 다 차기 전에는 실제 개수만큼만 오른쪽으로 채운다.
    val dx = w / (CAPACITY - 1)
    val startX = w - (size - 1) * dx

    accelPath.reset()
    brakePath.reset()
    for (i in 0 until size) {
      val x = startX + i * dx
      val ai = idx(i)
      if (i == 0) {
        accelPath.moveTo(x, toY(accelBuf[ai], h))
        brakePath.moveTo(x, toY(brakeBuf[ai], h))
      } else {
        accelPath.lineTo(x, toY(accelBuf[ai], h))
        brakePath.lineTo(x, toY(brakeBuf[ai], h))
      }
    }
    canvas.drawPath(brakePath, brakePaint)
    canvas.drawPath(accelPath, accelPaint)   // accel 을 위에 그려 겹칠 때 잘 보이게
  }
}
