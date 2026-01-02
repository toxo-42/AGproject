package com.example.agproject

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DeviceManagerActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_device_manager)

    // 저장된 정보 불러오기
    val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val name = prefs.getString("TARGET_NAME", "알 수 없음")
    val address = prefs.getString("TARGET_ADDRESS", null)

    // 화면에 표시
    findViewById<TextView>(R.id.tvDeviceName).text = name
    findViewById<TextView>(R.id.tvDeviceAddress).text = address

    // [재연결 버튼] -> 서비스를 껐다 켜서 재접속 유도
    findViewById<Button>(R.id.btnReconnect).setOnClickListener {
      restartService(address)
      Toast.makeText(this, "연결을 다시 시도합니다.", Toast.LENGTH_SHORT).show()
      finish() // 메인 화면으로 돌아감
    }

    // [연결 해제 버튼] -> 저장된 정보 삭제
    findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
      // 1. 서비스 완전 종료
      stopService(Intent(this, BleService::class.java))

      // 2. 저장된 데이터 삭제
      prefs.edit().clear().apply()

      Toast.makeText(this, "연결이 해제되었습니다.", Toast.LENGTH_SHORT).show()
      finish() // 메인 화면으로 돌아감
    }
  }

  private fun restartService(address: String?) {
    val serviceIntent = Intent(this, BleService::class.java)
    stopService(serviceIntent) // 기존 서비스 중단

    serviceIntent.putExtra("TARGET_ADDRESS", address)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(serviceIntent)
    } else {
      startService(serviceIntent)
    }
  }
}