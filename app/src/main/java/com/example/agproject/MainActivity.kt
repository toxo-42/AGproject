package com.example.agproject

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

  private var isRunning = false
  private var targetAddress: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    val btnToggle = findViewById<Button>(R.id.btnToggle)
    val btnSearch = findViewById<ImageButton>(R.id.btnSearch)
    val tvStatus = findViewById<TextView>(R.id.tvStatus)

    // 1. [중요] 앱 켜자마자 권한 확인 실행! (이 줄이 있어야 팝업이 뜹니다)
    checkPermissions()

    // 2. START / STOP 버튼 클릭
    btnToggle.setOnClickListener {
      if (isRunning) {
        // STOP 기능
        stopSystem()
        btnToggle.text = "START"
        btnToggle.background.setTint(Color.parseColor("#2196F3")) // 파란색
        tvStatus.text = "시스템 대기 중"
        tvStatus.setTextColor(Color.parseColor("#999999"))
      } else {
        // START 기능
        // 저장된 주소가 있는지 확인
        loadSavedAddress()
        if (targetAddress == null) {
          Toast.makeText(this, "먼저 돋보기 버튼을 눌러 기기를 등록해주세요!", Toast.LENGTH_LONG).show()
          return@setOnClickListener
        }

        startSystem()
        btnToggle.text = "STOP"
        btnToggle.background.setTint(Color.parseColor("#F44336")) // 빨간색
        tvStatus.text = "페달 오인 감지 중..."
        tvStatus.setTextColor(Color.parseColor("#F44336")) // 빨간 글씨
      }
      isRunning = !isRunning
    }

    // 3. 돋보기 버튼 클릭 로직
    btnSearch.setOnClickListener {
      val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
      val savedAddress = prefs.getString("TARGET_ADDRESS", null)

      if (savedAddress != null) {
        // 저장된 기기가 있으면 -> [관리 페이지]로 이동
        val intent = Intent(this, DeviceManagerActivity::class.java)
        startActivity(intent)
      } else {
        // 저장된 기기가 없으면 -> [검색 페이지]로 이동
        val intent = Intent(this, ScanActivity::class.java)
        startActivity(intent)
      }
    }
  }

  // --- 권한 관련 함수들 (onCreate 밖으로 잘 뺐습니다!) ---

  // 1. 권한이 있는지 확인하는 함수
  private fun hasPermissions(): Boolean {
    // 안드로이드 13 (Tiramisu) 이상일 경우: 알림 권한 체크 추가
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val hasNotification = ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
      val hasScan = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
      return hasNotification && hasScan
    }

    // 안드로이드 12 (S) 이상일 경우
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    return true
  }

  // 2. 권한을 요청하는 함수 (중복 제거됨)
  private fun checkPermissions() {
    val permissions = when {
      // 안드로이드 13 이상: 알림 + 블루투스 + 위치 모두 요청
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
        arrayOf(
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.POST_NOTIFICATIONS
        )
      }
      // 안드로이드 12 이상: 블루투스 + 위치
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        arrayOf(
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.ACCESS_FINE_LOCATION
        )
      }
      // 안드로이드 11 이하: 위치만
      else -> {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    }

    ActivityCompat.requestPermissions(this, permissions, 1)
  }

  private fun loadSavedAddress() {
    val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    targetAddress = prefs.getString("TARGET_ADDRESS", null)
  }

  private fun startSystem() {
    val serviceIntent = Intent(this, BleService::class.java)
    serviceIntent.putExtra("TARGET_ADDRESS", targetAddress) // 저장된 주소 전달

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
}