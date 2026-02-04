package com.example.agproject

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.Locale

class DeviceManagerActivity : AppCompatActivity() {

  // UI 컴포넌트 변수 선언
  private lateinit var etDeviceName: EditText
  private lateinit var etMacAddress: EditText
  private lateinit var tvConnectionStatus: TextView
  private lateinit var btnDisconnect: MaterialButton

  // TTS 변수 선언
  private var tts: TextToSpeech? = null

  // [수정 1] onCreate는 하나만 있어야 합니다! (내용 합침)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_device_manager)

    // 1. TTS 초기화
    tts = TextToSpeech(this) { status ->
      // [수정 2] ERROR가 '아닐 때' 언어를 설정해야 합니다 (!=)
      if (status != TextToSpeech.ERROR) {
        tts?.language = Locale.KOREAN
      }
    }

    // 2. ID 연결
    etDeviceName = findViewById(R.id.etDeviceName)
    etMacAddress = findViewById(R.id.etMacAddress)
    tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
    btnDisconnect = findViewById(R.id.btnDisconnect)

    // [수정 3] 설정 버튼 연결 (반드시 onCreate 안에서!)
    val btnOpenSettings = findViewById<ImageButton>(R.id.btnOpenSettings)
    btnOpenSettings.setOnClickListener {
      showTTSSettingsDialog()
    }

    // 3. 저장된 정보 불러오기
    val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val name = prefs.getString("TARGET_NAME", "등록된 기기 없음")
    val address = prefs.getString("TARGET_ADDRESS", null)
    val lastStatus = prefs.getString("CONNECTION_STATUS", "연결 상태 확인중...")

    // 4. 화면에 정보 표시
    etDeviceName.setText(name)

    if (address != null) {
      etMacAddress.setText(address)
      tvConnectionStatus.text = lastStatus

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
      btnDisconnect.isEnabled = false
    }

    // 5. [연결 해제 버튼] 클릭 이벤트
    btnDisconnect.setOnClickListener {
      stopService(Intent(this, BleService::class.java))
      prefs.edit().clear().apply()
      Toast.makeText(this, "기기 등록이 해제되었습니다.", Toast.LENGTH_SHORT).show()
      finish()
    }

    // 6. 롱클릭 이벤트 (디버깅용)
    etMacAddress.setOnLongClickListener {
      Toast.makeText(this, "Service UUID: d74d5c87-3d2b-46b3-b8a8-d64ca4917301", Toast.LENGTH_LONG).show()
      // Toast는 하나씩 뜨는 게 좋아서 뒤에 건 뺐습니다. 필요하면 넣으셔도 됩니다.
      true
    }
  }

  // [수정 4] 팝업 함수 (onCreate 밖으로 잘 나왔습니다)
  private fun showTTSSettingsDialog() {
    val builder = AlertDialog.Builder(this)
    // [주의] 레이아웃 파일명이 dialog_tts_settings (복수형)인지 확인하세요!
    val dialogView = layoutInflater.inflate(R.layout.dialog_tts_settings, null)
    builder.setView(dialogView)

    val dialog = builder.create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    // UI 연결
    val seekPitch = dialogView.findViewById<SeekBar>(R.id.seekPitch)
    val seekSpeed = dialogView.findViewById<SeekBar>(R.id.seekSpeed)
    val btnTest = dialogView.findViewById<Button>(R.id.btnTestTTS) // 버튼 ID 확인 (btnTestTTS vs btnTest)
    val btnClose = dialogView.findViewById<Button>(R.id.btnClose)

    // 저장된 값 불러오기
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val savedPitch = prefs.getFloat("TTS_PITCH", 1.0f)
    val savedSpeed = prefs.getFloat("TTS_SPEED", 1.0f)

    seekPitch.progress = (savedPitch * 10).toInt()
    seekSpeed.progress = (savedSpeed * 10).toInt()

    // 팝업 띄우기 (여기서 띄워야 버튼 동작함)
    dialog.show()

    // [수정 5] '들어보기' 버튼 (중첩 제거, 로직 보완)
    btnTest.setOnClickListener {
      val pitchValue = seekPitch.progress / 10f
      val speedValue = seekSpeed.progress / 10f

      // 값 저장
      prefs.edit()
        .putFloat("TTS_PITCH", pitchValue)
        .putFloat("TTS_SPEED", speedValue)
        .apply()

      // 즉시 말하기 (빠졌던 부분 추가!)
      tts?.setPitch(pitchValue)
      tts?.setSpeechRate(speedValue)
      tts?.speak("목소리 테스트 중입니다.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // [수정 6] '닫기' 버튼 (들어보기 버튼 밖으로 꺼냄)
    btnClose.setOnClickListener {
      val pitchValue = seekPitch.progress / 10f
      val speedValue = seekSpeed.progress / 10f

      prefs.edit()
        .putFloat("TTS_PITCH", pitchValue)
        .putFloat("TTS_SPEED", speedValue)
        .apply()

      dialog.dismiss()
      Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }
  }

  // [수정 7] onDestroy는 클래스의 멤버 함수여야 합니다 (함수 밖으로 꺼냄)
  override fun onDestroy() {
    if (tts != null) {
      tts?.stop()
      tts?.shutdown()
    }
    super.onDestroy()
  }
}