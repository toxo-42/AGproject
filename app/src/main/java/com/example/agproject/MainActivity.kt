package com.example.agproject

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

  // 1. 새로운 UI 부품들 선언 (새 디자인 ID에 맞춤)
  private lateinit var cardCurrentTarget: MaterialCardView
  private lateinit var tvTargetName: TextView
  private lateinit var tvTargetAddress: TextView
  private lateinit var btnGoScan: MaterialButton
  private lateinit var btnGoManager: MaterialButton

  private var isRunning = false
  private var targetAddress: String? = null
  private var targetName: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // 2. UI 연결 (activity_main.xml의 ID와 연결)
    cardCurrentTarget = findViewById(R.id.cardCurrentTarget)
    tvTargetName = findViewById(R.id.tvTargetName)
    tvTargetAddress = findViewById(R.id.tvTargetAddress)
    btnGoScan = findViewById(R.id.btnGoScan)
    btnGoManager = findViewById(R.id.btnGoManager)

    // 3. 앱 켜자마자 권한 확인
    checkPermissions()

    // 4. [기능 1] '기기 검색' 버튼 클릭 -> ScanActivity 이동
    btnGoScan.setOnClickListener {
      val intent = Intent(this, ScanActivity::class.java)
      startActivity(intent)
    }

    // 5. [기능 2] '설정' 버튼 클릭 -> DeviceManagerActivity 이동
    btnGoManager.setOnClickListener {
      val intent = Intent(this, DeviceManagerActivity::class.java)
      // 현재 정보 넘겨주기 (선택 사항)
      intent.putExtra("device_name", targetName)
      intent.putExtra("device_address", targetAddress)
      startActivity(intent)
    }

    // 6. [기능 3] 핵심! '중앙 카드'를 누르면 시스템 시작/정지 (구 btnToggle 기능)
    cardCurrentTarget.setOnClickListener {
      toggleSystem()
    }
  }

  override fun onResume() {
    super.onResume()
    // 화면에 돌아올 때마다 저장된 주소 새로고침
    loadSavedData()
    updateUI()
  }

  // --- 시스템 제어 로직 ---

  private fun toggleSystem() {
    if (isRunning) {
      // STOP 기능
      stopSystem()
      isRunning = false
      Toast.makeText(this, "감시 시스템을 종료합니다.", Toast.LENGTH_SHORT).show()
    } else {
      // START 기능
      if (targetAddress == null) {
        Toast.makeText(this, "먼저 [기기 검색]을 눌러 기기를 등록해주세요!", Toast.LENGTH_LONG).show()
        return
      }
      startSystem()
      isRunning = true
      Toast.makeText(this, "🚨 감시 시스템 시작! (백그라운드)", Toast.LENGTH_SHORT).show()
    }
    updateUI() // 화면 색상 변경
  }

  private fun updateUI() {
    if (targetAddress == null) {
      tvTargetName.text = "등록된 기기 없음"
      tvTargetAddress.text = "기기 검색 버튼을 눌러주세요"
      tvTargetName.setTextColor(getColor(R.color.text_gray)) // 회색
      cardCurrentTarget.setCardBackgroundColor(getColor(R.color.bg_card)) // 기본 배경
    } else {
      tvTargetName.text = targetName ?: "Unknown Device"
      tvTargetAddress.text = targetAddress

      if (isRunning) {
        // 실행 중일 때: 카드가 파란색/활성 색으로 변함
        tvTargetAddress.text = "⚡ 실시간 감시 중..."
        tvTargetName.setTextColor(getColor(R.color.accent_blue))
        cardCurrentTarget.setStrokeColor(getColor(R.color.accent_blue))
        cardCurrentTarget.setStrokeWidth(4) // 테두리 강조
      } else {
        // 대기 중일 때
        tvTargetName.setTextColor(getColor(R.color.text_white))
        cardCurrentTarget.setStrokeWidth(0) // 테두리 없음
      }
    }
  }

  // --- 데이터 및 서비스 관리 ---

  private fun loadSavedData() {
    val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    targetAddress = prefs.getString("TARGET_ADDRESS", null)
    targetName = prefs.getString("TARGET_NAME", "AG_Test_Module")
  }

  private fun startSystem() {
    val serviceIntent = Intent(this, BleService::class.java)
    serviceIntent.putExtra("TARGET_ADDRESS", targetAddress)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(serviceIntent)
    } else {
      startService(serviceIntent)
    }
  }

  private fun stopSystem() {
    val serviceIntent = Intent(this, BleService::class.java)
    stopService(serviceIntent)
  }

  // --- 권한 관련 (기존 코드 유지) ---

  private fun checkPermissions() {
    val permissions = when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
        arrayOf(
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.POST_NOTIFICATIONS
        )
      }
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        arrayOf(
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.ACCESS_FINE_LOCATION
        )
      }
      else -> {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    }
    ActivityCompat.requestPermissions(this, permissions, 1)
  }
}