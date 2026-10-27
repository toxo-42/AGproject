package com.example.agproject

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * 다른 앱(네비 등) 위에 페달 전환을 알리는 은은한 테두리를 그린다. 그리기만 하고 터치는 받지 않는다.
 *
 * - 화면 전체를 덮는 창 하나가 아니라 상·하·좌·우 얇은 띠 창 4개를 쓴다. Android 12+ 는 다른 앱의
 *   오버레이 창을 "통과"하는 터치를 창 alpha 가 0.8 을 넘으면 막는데(untrusted touch 차단),
 *   띠 창은 화면 가운데를 아예 덮지 않아 네비 조작에 영향이 없다. 창 alpha 도 0.8 로 맞춘다.
 * - 색은 실시간 그래프(PedalGraphView)와 같다: 엑셀 = primary(민트), 브레이크 = secondary(하늘색).
 *   빨강 계열은 오조작 경고 전용이라 쓰지 않는다.
 * - BOTH 는 실제 페달 위치대로 왼쪽 = 브레이크 색, 오른쪽 = 엑셀 색, 위·아래는 끈다.
 * - 쉬는 동안엔 띠 View 를 GONE 으로 둬 창 표면 자체가 숨겨지게 한다(합성 비용 0).
 *
 * 어느 스레드에서 불러도 된다 — 창 조작은 전부 메인 스레드로 넘긴다.
 */
class PedalEdgeOverlay(private val context: Context) {

  private val windowManager = context.getSystemService(WindowManager::class.java)
  private val mainHandler = Handler(Looper.getMainLooper())
  private val accelColor = ContextCompat.getColor(context, R.color.md_theme_primary)
  private val brakeColor = ContextCompat.getColor(context, R.color.md_theme_secondary)
  private val thicknessPx = (EDGE_THICKNESS_DP * context.resources.displayMetrics.density).toInt()

  private var strips: Map<Edge, View>? = null

  private enum class Edge(val gravity: Int, val gradient: GradientDrawable.Orientation) {
    LEFT(Gravity.START or Gravity.TOP, GradientDrawable.Orientation.LEFT_RIGHT),
    RIGHT(Gravity.END or Gravity.TOP, GradientDrawable.Orientation.RIGHT_LEFT),
    TOP(Gravity.START or Gravity.TOP, GradientDrawable.Orientation.TOP_BOTTOM),
    BOTTOM(Gravity.START or Gravity.BOTTOM, GradientDrawable.Orientation.BOTTOM_TOP);

    val isVertical get() = this == LEFT || this == RIGHT
  }

  /** 띠 창 4개를 붙인다. 오버레이 권한이 없으면 아무것도 하지 않는다(다음 attach 에서 다시 시도). */
  fun attach() {
    mainHandler.post {
      if (strips != null || !Settings.canDrawOverlays(context)) return@post
      strips = Edge.entries.associateWith { edge ->
        View(context).apply { visibility = View.GONE }.also { windowManager.addView(it, layoutParamsFor(edge)) }
      }
    }
  }

  fun detach() {
    mainHandler.post {
      strips?.values?.forEach { view ->
        view.animate().cancel()
        runCatching { windowManager.removeViewImmediate(view) }
      }
      strips = null
    }
  }

  /** 페달 상태가 바뀐 순간 한 번 부른다. 페이드인 → 잠깐 유지 → 페이드아웃. NONE(발 뗌)은 표시하지 않는다. */
  fun flash(state: PedalState) {
    if (state == PedalState.NONE) return
    mainHandler.post {
      val strips = strips ?: return@post
      for ((edge, view) in strips) {
        // 이전 전환의 애니메이션이 진행 중이면 끊고 새 색으로 다시 시작한다
        view.animate().cancel()
        val color = colorFor(state, edge)
        if (color == null) {
          view.alpha = 0f
          view.visibility = View.GONE
          continue
        }
        view.background = GradientDrawable(edge.gradient, intArrayOf(withAlpha(color, EDGE_PEAK_ALPHA), 0))
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setStartDelay(0).setDuration(FADE_IN_MS).withEndAction {
          view.animate().alpha(0f).setStartDelay(HOLD_MS).setDuration(FADE_OUT_MS).withEndAction {
            view.visibility = View.GONE
          }
        }
      }
    }
  }

  private fun colorFor(state: PedalState, edge: Edge): Int? = when (state) {
    PedalState.ACCEL -> accelColor
    PedalState.BRAKE -> brakeColor
    PedalState.BOTH -> when (edge) {
      Edge.LEFT -> brakeColor
      Edge.RIGHT -> accelColor
      else -> null
    }
    PedalState.NONE -> null
  }

  private fun layoutParamsFor(edge: Edge) = WindowManager.LayoutParams(
    if (edge.isVertical) thicknessPx else WindowManager.LayoutParams.MATCH_PARENT,
    if (edge.isVertical) WindowManager.LayoutParams.MATCH_PARENT else thicknessPx,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
  ).apply {
    gravity = edge.gravity
    alpha = MAX_WINDOW_ALPHA
    // 상태바·노치 영역까지 띠가 닿게 한다
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
  }

  private fun withAlpha(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

  companion object {
    // 튜닝 대상 — "은은하게"의 정도는 실차에서 보고 조정한다
    private const val EDGE_THICKNESS_DP = 14f
    private const val EDGE_PEAK_ALPHA = 0.7f    // 가장자리 색 불투명도(안쪽으로 갈수록 0)
    private const val FADE_IN_MS = 150L
    private const val HOLD_MS = 800L
    private const val FADE_OUT_MS = 400L
    // Android 12+ 가 다른 앱 오버레이를 통과하는 터치를 허용하는 최대 창 불투명도
    private const val MAX_WINDOW_ALPHA = 0.8f
  }
}
