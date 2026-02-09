package com.example.agproject

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_device_manager)

    // 1. TTS 초기화
    tts = TextToSpeech(this) { status ->
      if (status != TextToSpeech.ERROR) {
        tts?.language = Locale.KOREAN
      }
    }

    // 2. ID 연결
    etDeviceName = findViewById(R.id.etDeviceName)
    etMacAddress = findViewById(R.id.etMacAddress)
    tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
    btnDisconnect = findViewById(R.id.btnDisconnect)

    // 3. 설정 버튼 연결
    val btnOpenSettings = findViewById<ImageButton>(R.id.btnOpenSettings)
    btnOpenSettings.setOnClickListener {
      showTTSSettingsDialog()
    }

    // 4. 저장된 기기 정보 불러오기
    val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val name = prefs.getString("TARGET_NAME", "등록된 기기 없음")
    val address = prefs.getString("TARGET_ADDRESS", null)
    val lastStatus = prefs.getString("CONNECTION_STATUS", "연결 상태 확인중...")

    // 5. 화면에 정보 표시
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

    // [연결 해제 버튼] 클릭 이벤트
    btnDisconnect.setOnClickListener {
      stopService(Intent(this, BleService::class.java))
      prefs.edit().clear().apply()
      Toast.makeText(this, "기기 등록이 해제되었습니다.", Toast.LENGTH_SHORT).show()
      finish()
    }

    // 롱클릭 이벤트 (디버깅용)
    etMacAddress.setOnLongClickListener {
      Toast.makeText(this, "UUID 확인용 토스트", Toast.LENGTH_SHORT).show()
      true
    }
  }

  // 성별 선택 기능 추가된 팝업 함수
  private fun showTTSSettingsDialog() {
    val builder = AlertDialog.Builder(this)
    val dialogView = layoutInflater.inflate(R.layout.dialog_tts_settings, null)
    builder.setView(dialogView)

    val dialog = builder.create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    // UI 연결 (라디오 버튼 삭제됨 -> 시스템 설정 버튼 추가됨)
    val btnOpenSystemTTS = dialogView.findViewById<Button>(R.id.btnOpenSystemTTS)

    val seekPitch = dialogView.findViewById<SeekBar>(R.id.seekPitch)
    val seekSpeed = dialogView.findViewById<SeekBar>(R.id.seekSpeed)
    val btnTest = dialogView.findViewById<Button>(R.id.btnTestTTS)
    val btnClose = dialogView.findViewById<Button>(R.id.btnClose)

    // 1. 저장된 값 불러오기 (성별 관련 변수 삭제)
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val savedPitch = prefs.getFloat("TTS_PITCH", 1.0f)
    val savedSpeed = prefs.getFloat("TTS_SPEED", 1.0f)

    // UI 초기화
    seekPitch.progress = (savedPitch * 10).toInt()
    seekSpeed.progress = (savedSpeed * 10).toInt()

    // 시스템 TTS 설정 화면으로 이동하는 버튼
    btnOpenSystemTTS.setOnClickListener {
      try {
        // 안드로이드 TTS 설정 화면을 여는 마법의 인텐트
        val intent = Intent()
        intent.action = "com.android.settings.TTS_SETTINGS"
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
      } catch (e: Exception) {
        Toast.makeText(this, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
      }
    }

    // 2. [들어보기] 버튼
    btnTest.setOnClickListener {
      val pitchValue = seekPitch.progress / 10f
      val speedValue = seekSpeed.progress / 10f

      // 값 저장
      prefs.edit()
        .putFloat("TTS_PITCH", pitchValue)
        .putFloat("TTS_SPEED", speedValue)
        .apply()

      // 즉시 말하기 (성별 로직 삭제됨)
      tts?.setPitch(pitchValue)
      tts?.setSpeechRate(speedValue)
      tts?.speak("목소리 테스트 중입니다.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // 3. [저장 & 닫기] 버튼
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

    dialog.show()
  }

  // 성별에 맞는 목소리(Voice) 객체를 찾아 설정하는 함수
  private fun updateTTSVoice(gender: String) {
    try {
      val voices = tts?.voices
      // "ko-KR" 이면서 이름에 "male" 또는 "female"이 들어가는 목소리를 찾음
      val targetVoice = voices?.find {
        it.locale == Locale.KOREAN && it.name.contains(gender, ignoreCase = true)
      }

      if (targetVoice != null) {
        tts?.voice = targetVoice
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  override fun onDestroy() {
    if (tts != null) {
      tts?.stop()
      tts?.shutdown()
    }
    super.onDestroy()
  }
}