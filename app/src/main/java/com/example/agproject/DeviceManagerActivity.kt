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
    val lastStatus = prefs.getString("CONNECTION_STATUS", "연결 상태 확인중...")

    // 3. 화면에 정보 표시
    etDeviceName.setText(name)

    if (address != null) {
      etMacAddress.setText(address)
      // 실제 상태를 보여 줌 (UUID 일치, UUID 불일치)
      tvConnectionStatus.text = lastStatus

      // 만약 "불일치"라는 단어가 있으면 빨간색으로 표시
      if (lastStatus!!.contains("불일치") || lastStatus.contains("경고")) {
        tvConnectionStatus.setTextColor(getColor(R.color.red_error))
      } else {
        tvConnectionStatus.setTextColor(getColor(R.color.accent_blue))
      }

      btnDisconnect.isEnabled = true
    } else {
      etMacAddress.setText("주소 정보 없음")
      tvConnectionStatus.text = "연결된 기기가 없습니다."
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

    etMacAddress.setOnLongClickListener {
      // 길게 누르면 토스트 메시지로 UUID를 살짝 보여줌
      Toast.makeText(this, "Service UUID: d74d5c87-3d2b-46b3-b8a8-d64ca4917301", Toast.LENGTH_LONG).show()
      Toast.makeText(this, "Char UUID: d74d5c87-3d2b-46b3-b8a8-d64ca491735e", Toast.LENGTH_LONG).show()

      true
    }

  }
}