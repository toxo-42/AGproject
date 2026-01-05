package com.example.agproject

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.Context
import androidx.core.content.ContextCompat

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

  // 0: 대기, 1: 정상, 2: 에러(UUID 다름)
  private var connectionStatus= 0

  // 방송(Broadcast)을 수신할 '라디오'를 만듭니다.
  private val statusReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        "ACTION_UUID_MATCHED" -> {
          connectionStatus = 1 // 정상
          updateUI()
        }
        "ACTION_UUID_MISMATCH" -> {
          connectionStatus = 2 // 에러 (빨간불)
          updateUI()
        }
      }
    }
  }

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
    loadSavedData()

    // 라디오 켜기 (방송 수신 등록)
    val filter = android.content.IntentFilter().apply {
      addAction("ACTION_UUID_MATCHED")
      addAction("ACTION_UUID_MISMATCH")
    }

    // 빨간 줄, 노란 줄 모두 해결한 완벽한 코드
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
      ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    updateUI()
  }

  override fun onPause() {
    super.onPause()
    // 앱이 백그라운드로 가면 라디오 끄기 (배터리 절약)
    try {
      unregisterReceiver(statusReceiver)
    } catch (_: IllegalArgumentException) {
      // 이미 꺼져있으면 패스
    }
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
    }
    updateUI() // 화면 색상 변경
  }

  @SuppressLint("SetTextI18n")
  private fun updateUI() {
    // 1. 기기가 아예 등록 안 된 상태
    if (targetAddress == null) {
      tvTargetName.text = "등록된 기기 없음"
      tvTargetAddress.text = "기기 검색 버튼을 눌러주세요"
      tvTargetName.setTextColor(getColor(R.color.text_gray))
      tvTargetAddress.setTextColor(getColor(R.color.text_hint))

      cardCurrentTarget.setCardBackgroundColor(getColor(R.color.bg_card))
      cardCurrentTarget.setStrokeWidth(0) // 테두리 없음
    }
    // 2. 기기는 등록된 상태
    else {
      tvTargetName.text = targetName ?: "Unknown Device"
      tvTargetAddress.text = targetAddress

      if (isRunning) {
        // [수정] connectionStatus에 따라 색깔놀이
        when (connectionStatus) {
          1 -> { // 정상 연결 (파란색)
            tvTargetAddress.text = "⚡ 실시간 감시 중..."
            tvTargetName.setTextColor(getColor(R.color.accent_blue))
            cardCurrentTarget.setStrokeColor(getColor(R.color.accent_blue))
            cardCurrentTarget.setStrokeWidth(4) // 굵게
          }
          2 -> { // UUID 불일치 (빨간색)
            tvTargetAddress.text = "잘못된 기기입니다 (UUID 불일치)"
            tvTargetAddress.setTextColor(getColor(R.color.red_error)) // 글자도 빨갛게
            tvTargetName.setTextColor(getColor(R.color.red_error))
            cardCurrentTarget.setStrokeColor(getColor(R.color.red_error)) // 테두리 빨갛게!
            cardCurrentTarget.setStrokeWidth(8) // 더 굵게 경고!
          }
          else -> { // 연결 시도 중... (0번 상태)
            tvTargetAddress.text = "연결 시도 중..."
            tvTargetName.setTextColor(getColor(R.color.text_white)) // 연결 중엔 흰색 유지
            cardCurrentTarget.setStrokeColor(getColor(R.color.text_gray))
            cardCurrentTarget.setStrokeWidth(2)
          }
        }
      } else {
        // 정지 상태 (원상복구)
        connectionStatus = 0 // 상태 초기화
        tvTargetName.setTextColor(getColor(R.color.text_white))
        tvTargetAddress.setTextColor(getColor(R.color.text_hint))
        cardCurrentTarget.setStrokeWidth(0)
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
    startForegroundService(serviceIntent)
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