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
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DeviceManagerActivity : AppCompatActivity() {

  // UI 컴포넌트 변수 선언
  private lateinit var etDeviceName: EditText
  private lateinit var etMacAddress: EditText
  private lateinit var tvConnectionStatus: TextView
  private lateinit var btnDisconnect: MaterialButton
  private lateinit var btnDataCollect: MaterialButton

  // TTS 변수 선언
  private var tts: TextToSpeech? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_device_manager)
    

    // 2. ID 연결
    etDeviceName = findViewById(R.id.etDeviceName)
    etMacAddress = findViewById(R.id.etMacAddress)
    tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
    btnDisconnect = findViewById(R.id.btnDisconnect)
    btnDataCollect = findViewById(R.id.btnDataCollect)

    // 데이터 수집 화면 진입 (실시간 그래프 + 프로필/라벨링)
    btnDataCollect.setOnClickListener {
      startActivity(Intent(this, DataCollectActivity::class.java))
    }
    // 길게 누르면 개발자 전용 오조작 라벨링 모드로 진입 (AI 학습 데이터 수집용, 일반 사용자에겐 숨김)
    btnDataCollect.setOnLongClickListener {
      startActivity(Intent(this, DataCollectActivity::class.java).apply {
        putExtra(DataCollectActivity.EXTRA_DEV_MODE, true)
      })
      Toast.makeText(this, R.string.msg_dev_mode_entered, Toast.LENGTH_SHORT).show()
      true
    }

    // 3. 설정 버튼 연결
    val btnOpenSettings = findViewById<ImageButton>(R.id.btnOpenSettings)
    btnOpenSettings.setOnClickListener {
      showTTSSettingsDialog()
    }

    // 로깅 데이터 추출(Phase E) — 세션 선택 후 CSV를 공유 시트로 내보낸다.
    val btnExportLogs = findViewById<ImageButton>(R.id.btnExportLogs)
    btnExportLogs.setOnClickListener {
      showSessionPickerAndExport()
    }

    // 주행 기록 삭제 — 계속 쌓이기만 하는 로그를 앱 안에서 정리(재설치 없이).
    val btnDeleteLogs = findViewById<ImageButton>(R.id.btnDeleteLogs)
    btnDeleteLogs.setOnClickListener {
      showSessionPickerAndDelete()
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

  // --- 로깅 데이터 추출 (Phase E) ---

  // 세션 하나 = pedal_<타임스탬프>.csv 파일 하나(원시+판정 결과가 이제 한 파일에 다 담김).
  // BleService 가 항상 이 이름 패턴으로 만들기 때문에 이걸로 세션을 구분한다.
  private val sessionTimestampRegex = Regex("""^pedal_(\d{8}_\d{6})\.csv$""")

  private fun listSessionTimestamps(): List<String> {
    val logsDir = File(filesDir, "logs")
    val files = logsDir.listFiles() ?: return emptyList()
    return files.mapNotNull { sessionTimestampRegex.matchEntire(it.name)?.groupValues?.get(1) }
      .sortedDescending()   // 최신 세션이 위로
  }

  // "20260713_032847"(BleService 파일명 형식) -> "26/07/13-03:28:47" 로 사람이 보기 좋게.
  // 파싱 실패하면(예상 못한 형식) 원본 문자열을 그대로 보여준다.
  private fun formatSessionLabel(timestamp: String): String {
    return try {
      val parsed = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(timestamp)
      java.text.SimpleDateFormat("yy/MM/dd-HH:mm:ss", Locale.US).format(parsed!!)
    } catch (e: Exception) {
      timestamp
    }
  }

  private fun showSessionPickerAndExport() {
    val timestamps = listSessionTimestamps()
    if (timestamps.isEmpty()) {
      Toast.makeText(this, R.string.msg_no_sessions, Toast.LENGTH_SHORT).show()
      return
    }

    val labels = timestamps.map { formatSessionLabel(it) }.toTypedArray()

    AlertDialog.Builder(this)
      .setTitle(R.string.title_select_session)
      .setItems(labels) { _, which -> exportSession(timestamps[which]) }
      .show()
  }

  // CSV + 서명(.sig) + 공개키(pubkey.pem) + 검증 스크립트를 묶어 내보낸다.
  // 검증 스크립트를 zip 안에 같이 넣는 이유: 받는 쪽(경찰/보험사)이 이 저장소나 uv,
  // 심지어 pip install 도 없이 `python3 verify_signature.py <csv>` 만으로 바로 검증할 수
  // 있게 하기 위함(스크립트가 표준 라이브러리만으로 ECDSA를 직접 구현함, 2026-07-14 사용자
  // 피드백 — "검증은 다른 컴퓨터에서 하는 건데 내 alias/pip install 전제는 의미 없다").
  private fun exportSession(timestamp: String) {
    val logsDir = File(filesDir, "logs")
    val pedalFile = File(logsDir, "pedal_${timestamp}.csv")
    if (!pedalFile.exists()) {
      Toast.makeText(this, R.string.msg_no_sessions, Toast.LENGTH_SHORT).show()
      return
    }

    val signatureBase64 = try {
      EvidenceSigner.signFile(pedalFile)
    } catch (e: Exception) {
      Toast.makeText(this, R.string.msg_signing_failed, Toast.LENGTH_LONG).show()
      null
    }

    val zipFile = File(cacheDir, "session_${timestamp}_signed.zip")
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
      zos.putNextEntry(ZipEntry(pedalFile.name))
      pedalFile.inputStream().use { it.copyTo(zos) }
      zos.closeEntry()

      if (signatureBase64 != null) {
        zos.putNextEntry(ZipEntry("${pedalFile.name}.sig"))
        zos.write(signatureBase64.toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("pubkey.pem"))
        zos.write(EvidenceSigner.publicKeyPem().toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("verify_signature.py"))
        assets.open("verify_signature.py").use { it.copyTo(zos) }
        zos.closeEntry()

        // 컴퓨터를 잘 모르는 사람도 검증할 수 있게 사용법을 평문으로 동봉(2026-07-14 사용자 요청).
        zos.putNextEntry(ZipEntry("README_검증방법.txt"))
        assets.open("README_검증방법.txt").use { it.copyTo(zos) }
        zos.closeEntry()
      }
    }

    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "application/zip"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(shareIntent, getString(R.string.desc_export_logs)))
  }

  // --- 주행 기록 삭제 ---
  // filesDir/logs 는 세션마다 파일이 계속 쌓이기만 하고 자동으로 안 지워진다.
  // 재설치 없이 앱 안에서 정리할 수 있게 개별/전체 삭제를 제공한다.

  private fun showSessionPickerAndDelete() {
    val timestamps = listSessionTimestamps()
    if (timestamps.isEmpty()) {
      Toast.makeText(this, R.string.msg_no_sessions, Toast.LENGTH_SHORT).show()
      return
    }

    // 0번째 항목은 "전체 삭제", 나머지는 세션별 삭제.
    val labels = arrayOf(getString(R.string.option_delete_all, timestamps.size)) +
      timestamps.map { formatSessionLabel(it) }.toTypedArray()

    AlertDialog.Builder(this)
      .setTitle(R.string.title_select_session_delete)
      .setItems(labels) { _, which ->
        if (which == 0) confirmDeleteAllSessions(timestamps.size) else confirmDeleteSession(timestamps[which - 1])
      }
      .show()
  }

  private fun confirmDeleteSession(timestamp: String) {
    AlertDialog.Builder(this)
      .setTitle(R.string.title_delete_confirm)
      .setMessage(R.string.msg_delete_one_confirm)
      .setPositiveButton(R.string.btn_delete_confirm) { _, _ -> deleteSession(timestamp) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun confirmDeleteAllSessions(count: Int) {
    AlertDialog.Builder(this)
      .setTitle(R.string.title_delete_confirm)
      .setMessage(getString(R.string.msg_delete_all_confirm, count))
      .setPositiveButton(R.string.btn_delete_confirm) { _, _ -> deleteAllSessions() }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun deleteSession(timestamp: String) {
    File(File(filesDir, "logs"), "pedal_${timestamp}.csv").delete()
    Toast.makeText(this, R.string.msg_delete_done, Toast.LENGTH_SHORT).show()
  }

  private fun deleteAllSessions() {
    val logsDir = File(filesDir, "logs")
    for (ts in listSessionTimestamps()) {
      File(logsDir, "pedal_${ts}.csv").delete()
    }
    Toast.makeText(this, R.string.msg_delete_done, Toast.LENGTH_SHORT).show()
  }

  override fun onDestroy() {
    if (tts != null) {
      tts?.stop()
      tts?.shutdown()
    }
    super.onDestroy()
  }
}