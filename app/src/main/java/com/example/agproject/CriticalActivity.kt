package com.example.agproject

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class CriticalActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    // 1. 잠금화면 위에서도 뜨게 만들기 (매우 중요!)
    setupScreenOnFlags()

    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_critical)

    // 2. UI 연결 (경고 애니메이션은 PedalGuideView 가 스스로 그린다)
    val btnDismiss = findViewById<MaterialButton>(R.id.btnDismiss)

    // 3. 확인 버튼 누르면 -> 화면 끄기
    btnDismiss.setOnClickListener {
      finish() // 액티비티 종료
    }
  }

  // 경고 화면이 보이는 동안엔 페달 테두리(다른 앱 위 오버레이)가 경고 위에 겹치지 않게 끈다.
  override fun onStart() {
    super.onStart()
    notifyCriticalShowing(true)
  }

  override fun onStop() {
    notifyCriticalShowing(false)
    super.onStop()
  }

  private fun notifyCriticalShowing(showing: Boolean) {
    if (!BleService.monitorState.isActive) return
    startService(Intent(this, BleService::class.java).apply {
      action = BleService.ACTION_SET_CRITICAL_SHOWING
      putExtra(BleService.EXTRA_CRITICAL_SHOWING, showing)
    })
  }

  // 운전자가 당황해서 실수로 뒤로가기를 눌러 경고창이 꺼지는 것을 방지합니다.
  @Suppress("DEPRECATION")
  override fun onBackPressed() {
    // 아무것도 안 함 (버튼을 눌러야만 꺼짐)
    // super.onBackPressed() // 이걸 지워야 뒤로가기가 안 먹힘
  }

  // 화면 깨우기 설정 (버전별 대응)
  private fun setupScreenOnFlags() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
      )
    }
  }
}