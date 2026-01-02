package com.example.agproject

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.agproject.databinding.ActivityMainBinding



class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding

  // 권한 요청 결과를 처리하는 런처 (사용자가 '허용'을 눌렀는지 확인)
  private val requestPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
      // 모든 권한이 허용되었는지 확인
      val allGranted = permissions.entries.all { it.value }
      if (allGranted) {
        // 권한을 다 얻었으면 서비스 시작
        startBleService()
      } else {
        Toast.makeText(this, "모든 권한을 허용해야 앱을 사용할 수 있습니다.", Toast.LENGTH_SHORT).show()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    binding.btnToggleService.setOnClickListener {
      if (binding.btnToggleService.text == "START") {
        // START 버튼을 누르면 -> 권한부터 체크!
        checkPermissionsAndStart()
      } else {
        // STOP 버튼을 누르면 -> 서비스 종료
        stopBleService()
      }
    }
  }

  // 1. 권한 확인 및 요청 함수
  private fun checkPermissionsAndStart() {
    // 필요한 권한 목록 작성 (안드로이드 버전에 따라 다름)
    val requiredPermissions = mutableListOf<String>()

    // 안드로이드 12 (S) 이상: 블루투스 스캔/연결 권한 필수
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
      requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
      // 안드로이드 11 이하: 위치 권한 필수 (블루투스 사용을 위해)
      requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // 안드로이드 13 (T) 이상: 알림 권한 필수
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 권한이 없는게 하나라도 있는지 확인
    val missingPermissions = requiredPermissions.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    if (missingPermissions.isEmpty()) {
      // 모든 권한이 이미 있다면 바로 시작
      startBleService()
    } else {
      // 없는 권한이 있다면 팝업 띄워서 요청
      requestPermissionLauncher.launch(missingPermissions.toTypedArray())
    }
  }

  // 2. 서비스 시작 (UI 변경 + 서비스 호출)
  private fun startBleService() {
    binding.btnToggleService.text = "STOP"
    binding.tvStatus.text = "감시 시스템 가동 중..."

    val intent = Intent(this, BleService::class.java)
    // 안드로이드 8.0 이상은 startForegroundService 사용
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(intent)
    } else {
      startService(intent)
    }
  }

  // 3. 서비스 종료
  private fun stopBleService() {
    binding.btnToggleService.text = "START"
    binding.tvStatus.text = "Accu Guard 준비 완료"

    val intent = Intent(this, BleService::class.java)
    stopService(intent)
  }
}