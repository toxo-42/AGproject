package com.example.agproject

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class DeviceManagerActivity : AppCompatActivity() {

  // 새 UI 컴포넌트 변수 선언
  private lateinit var etDeviceName: EditText
  private lateinit var etMacAddress: EditText
  private lateinit var tvConnectionStatus: TextView
  private lateinit var btnDisconnect: MaterialButton

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_device_manager)

    // 1. ID 연결 (새로운 XML 이름과 매칭)
    etDeviceName = findViewById(R.id.etDeviceName)
    etMacAddress = findViewById(R.id.etMacAddress)
    tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
    btnDisconnect = findViewById(R.id.btnDisconnect)

    // 2. 저장된 정보(SharedPreference) 불러오기
    val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val name = prefs.getString("TARGET_NAME", "등록된 기기 없음")
    val address = prefs.getString("TARGET_ADDRESS", null)

    // 3. 화면에 정보 표시
    etDeviceName.setText(name)

    if (address != null) {
      etMacAddress.setText(address)
      tvConnectionStatus.text = "✅ 현재 등록되어 감시 대기 중입니다."
      tvConnectionStatus.setTextColor(getColor(R.color.accent_blue)) // 파란색 (colors.xml에 정의된 색)
      btnDisconnect.isEnabled = true
    } else {
      etMacAddress.setText("주소 정보 없음")
      tvConnectionStatus.text = "⚠️ 연결된 기기가 없습니다."
      tvConnectionStatus.setTextColor(getColor(R.color.text_gray))
      btnDisconnect.isEnabled = false // 기기가 없으면 해제 버튼 비활성화
    }

    // 4. [연결 해제 버튼] 클릭 이벤트
    btnDisconnect.setOnClickListener {
      // (1) 백그라운드 서비스 종료
      stopService(Intent(this, BleService::class.java))

      // (2) 저장된 데이터(기기 정보) 삭제
      prefs.edit().clear().apply()

      // (3) 알림 및 종료
      Toast.makeText(this, "기기 등록이 해제되었습니다.", Toast.LENGTH_SHORT).show()

      // 메인 화면으로 돌아가면서 현재 화면 닫기
      finish()
    }
  }
}