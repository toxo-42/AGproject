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

    // 연결 해제 버튼 이벤트
    btnDisconnect.setOnClickListener {
      stopService(Intent(this, BleService::class.java))
      prefs.edit().clear().apply()
      Toast.makeText(this, "기기 등록이 해제되었습니다.", Toast.LENGTH_SHORT).show()
      finish()
    }

    // 롱클릭 이벤트
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

    // UI 연결
    val rgGender = dialogView.findViewById<RadioGroup>(R.id.rgGender)
    val rbFemale = dialogView.findViewById<RadioButton>(R.id.rbFemale)
    val rbMale = dialogView.findViewById<RadioButton>(R.id.rbMale)

    val btnTest = dialogView.findViewById<Button>(R.id.btnTestTTS)
    val btnClose = dialogView.findViewById<Button>(R.id.btnClose)

    // 저장된 성별 불러오기
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    val savedGender = prefs.getString("TTS_GENDER", "female")

    // UI 초기화
    if (savedGender == "male") rbMale.isChecked = true
    else rbFemale.isChecked = true

    // 들어보기 버튼
    btnTest.setOnClickListener {
      val selectedGender = if (rgGender.checkedRadioButtonId == R.id.rbMale) "male" else "female"

      // 저장
      prefs.edit().putString("TTS_GENDER", selectedGender).apply()

      // 테스트용 파일: voice_pedal_male.mp3 또는 voice_pedal_female.mp3 재생
      val testSoundId = if (selectedGender == "male") R.raw.voice_pedal_male else R.raw.voice_pedal_female
      playMp3(testSoundId)
    }

    // 저장 & 닫기 버튼
    btnClose.setOnClickListener {
      val selectedGender = if (rgGender.checkedRadioButtonId == R.id.rbMale) "male" else "female"
      prefs.edit().putString("TTS_GENDER", selectedGender).apply()

      dialog.dismiss()
      Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    dialog.show()
  }

  //  MP3 재생 도우미 함수
  private fun playMp3(resId: Int) {
    try {
      val mediaPlayer = android.media.MediaPlayer.create(this, resId)
      mediaPlayer.setOnCompletionListener { it.release() } // 재생 끝나면 메모리 해제
      mediaPlayer.start()
    } catch (e: Exception) { e.printStackTrace() }
  }


  override fun onDestroy() {
    if (tts != null) {
      tts?.stop()
      tts?.shutdown()
    }
    super.onDestroy()
  }
}