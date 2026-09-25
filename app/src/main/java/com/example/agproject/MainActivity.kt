package com.example.agproject

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import jp.wasabeef.glide.transformations.BlurTransformation


class MainActivity : AppCompatActivity() {

  // 1. 새로운 UI 부품들 선언 (새 디자인 ID에 맞춤)
  private lateinit var cardCurrentTarget: MaterialCardView
  private lateinit var tvTargetName: TextView
  private lateinit var tvTargetAddress: TextView
  private lateinit var btnGoScan: MaterialButton
  private lateinit var btnGoManager: MaterialButton
  private lateinit var btnStartMonitor: MaterialButton
  private lateinit var btnStopMonitor: MaterialButton
  private lateinit var layoutStatusChip: View
  private lateinit var viewStatusDot: View
  private lateinit var tvStatus: TextView
  private lateinit var dividerCalibration: View
  private lateinit var layoutCalibration: View
  private lateinit var ivCalibration: ImageView
  private lateinit var tvCalibration: TextView

  private var targetAddress: String? = null
  private var targetName: String? = null
  private var isCalibrated = false

  // 캘리브레이션 온보딩 팝업이 이미 떠 있는 동안 중복으로 안 뜨게 막는 플래그.
  private var isCalibrationPopupShowing = false

  // Broadcast을 수신할 '라디오'생성
  private val statusReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        // 감시 상태가 바뀔 때마다 BleService가 보냄 — 실제 값은 BleService.monitorState 에서 읽는다
        BleService.ACTION_MONITOR_STATE -> updateUI()
        "ACTION_UUID_MATCHED" -> maybeShowCalibrationOnboarding()
        "ACTION_MODULE_ERROR" ->{
         showErrorPopup()// 팝업 띄우는 함수 실행
        }
        "ACTION_SD_CARD_WARNING" -> {
          showCapacityWarningPopup()
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // 배경 블러처리
    val ivBackground = findViewById<ImageView>(R.id.ivBackground)

    Glide.with(this)
      .load(R.drawable.bg_main_capture)// 캡처해서 넣은 이미지 파일명
      .apply(RequestOptions.bitmapTransform(BlurTransformation(25, 3)))
      .into(ivBackground)

    // UI 연결 (activity_main.xml의 ID와 연결)
    cardCurrentTarget = findViewById(R.id.cardCurrentTarget)
    tvTargetName = findViewById(R.id.tvTargetName)
    tvTargetAddress = findViewById(R.id.tvTargetAddress)
    btnGoScan = findViewById(R.id.btnGoScan)
    btnGoManager = findViewById(R.id.btnGoManager)
    btnStartMonitor = findViewById(R.id.btnStartMonitor)
    btnStopMonitor = findViewById(R.id.btnStopMonitor)
    layoutStatusChip = findViewById(R.id.layoutStatusChip)
    viewStatusDot = findViewById(R.id.viewStatusDot)
    tvStatus = findViewById(R.id.tvStatus)
    dividerCalibration = findViewById(R.id.dividerCalibration)
    layoutCalibration = findViewById(R.id.layoutCalibration)
    ivCalibration = findViewById(R.id.ivCalibration)
    tvCalibration = findViewById(R.id.tvCalibration)

    // 앱 켜자마자 권한 확인
    checkPermissions()
    checkOverlayPermission()
    checkFullScreenIntentPermission()

    // '기기 검색' 버튼 클릭 -> ScanActivity 이동
    btnGoScan.setOnClickListener {
      val intent = Intent(this, ScanActivity::class.java)
      startActivity(intent)
    }

    // '설정' 버튼 클릭 -> DeviceManagerActivity 이동
    btnGoManager.setOnClickListener {
      val intent = Intent(this, DeviceManagerActivity::class.java)
      // 현재 정보 넘겨주기 (선택 사항)
      intent.putExtra("device_name", targetName)
      intent.putExtra("device_address", targetAddress)
      startActivity(intent)
    }

    btnStartMonitor.setOnClickListener { startSystem() }
    btnStopMonitor.setOnClickListener {
      stopSystem()
      Toast.makeText(this, "감시 시스템을 종료합니다.", Toast.LENGTH_SHORT).show()
    }

    // 패턴 파악 전이면 눌러서 바로 데이터 수집(캘리브레이션) 화면으로
    layoutCalibration.setOnClickListener {
      if (!isCalibrated) startActivity(Intent(this, DataCollectActivity::class.java))
    }

  }

  override fun onResume() {
    super.onResume()
    loadSavedData()

    // 라디오 켜기 (방송 수신 등록)
    val filter = android.content.IntentFilter().apply {
      addAction(BleService.ACTION_MONITOR_STATE)
      addAction("ACTION_UUID_MATCHED")
      addAction("ACTION_MODULE_ERROR")
      addAction("ACTION_SD_CARD_WARNING")
    }

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

  // --- 화면 갱신 ---
  // 감시 상태는 BleService.monitorState(단일 출처)에서, 기기·캘리브레이션 정보는 prefs에서 읽어 그린다.
  private fun updateUI() {
    val state = BleService.monitorState
    val hasDevice = targetAddress != null

    // 1. 상태 칩 (점 색 + 글자)
    val (statusText, dotAttr) = when {
      !hasDevice -> R.string.monitor_no_device to MaterialR.attr.colorOutline
      else -> when (state) {
        MonitorState.STOPPED -> R.string.monitor_stopped to MaterialR.attr.colorOutline
        MonitorState.CONNECTING -> R.string.monitor_connecting to MaterialR.attr.colorTertiary
        MonitorState.MONITORING -> R.string.monitor_monitoring to MaterialR.attr.colorPrimary
        MonitorState.RECONNECTING -> R.string.monitor_reconnecting to MaterialR.attr.colorTertiary
        MonitorState.WRONG_DEVICE -> R.string.monitor_wrong_device to MaterialR.attr.colorError
      }
    }
    val dotColor = MaterialColors.getColor(viewStatusDot, dotAttr)
    tvStatus.setText(statusText)
    viewStatusDot.backgroundTintList = ColorStateList.valueOf(dotColor)

    // 2. 기기 이름/주소 + 카드 테두리 (켜져 있을 때만 상태 색 테두리)
    val onSurfaceVariant = MaterialColors.getColor(tvTargetName, MaterialR.attr.colorOnSurfaceVariant)
    val outline = MaterialColors.getColor(tvTargetAddress, MaterialR.attr.colorOutline)
    val error = MaterialColors.getColor(tvTargetAddress, MaterialR.attr.colorError)
    if (!hasDevice) {
      tvTargetName.setText(R.string.no_device_registered)
      tvTargetName.setTextColor(onSurfaceVariant)
      tvTargetAddress.setText(R.string.register_prompt)
      tvTargetAddress.setTextColor(outline)
    } else {
      tvTargetName.text = targetName ?: "Unknown Device"
      tvTargetName.setTextColor(MaterialColors.getColor(tvTargetName, MaterialR.attr.colorOnSurface))
      if (state == MonitorState.WRONG_DEVICE) {
        tvTargetAddress.setText(R.string.msg_wrong_device)
        tvTargetAddress.setTextColor(error)
      } else {
        tvTargetAddress.text = targetAddress
        tvTargetAddress.setTextColor(outline)
      }
    }
    val showStroke = hasDevice && (state.isActive || state == MonitorState.WRONG_DEVICE)
    cardCurrentTarget.strokeColor = dotColor
    cardCurrentTarget.strokeWidth = if (showStroke) (2 * resources.displayMetrics.density).toInt() else 0

    // 3. 캘리브레이션 줄 — 패턴 파악 전엔 오조작 감지가 꺼져 있다는 걸 항상 보이게
    dividerCalibration.isVisible = hasDevice
    layoutCalibration.isVisible = hasDevice
    layoutCalibration.isClickable = !isCalibrated
    if (isCalibrated) {
      ivCalibration.setImageResource(R.drawable.ic_check_circle)
      ivCalibration.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(ivCalibration, MaterialR.attr.colorPrimary))
      tvCalibration.setText(R.string.calibration_done)
      tvCalibration.setTextColor(onSurfaceVariant)
    } else {
      val tertiary = MaterialColors.getColor(ivCalibration, MaterialR.attr.colorTertiary)
      ivCalibration.setImageResource(R.drawable.ic_warning)
      ivCalibration.imageTintList = ColorStateList.valueOf(tertiary)
      tvCalibration.setText(R.string.calibration_needed)
      tvCalibration.setTextColor(tertiary)
    }

    // 4. 시작/중지 버튼 — 켜져 있으면 중지만, 꺼져 있으면 시작만 보인다. 기기 없으면 시작 불가
    btnStartMonitor.isVisible = !state.isActive
    btnStopMonitor.isVisible = state.isActive
    btnStartMonitor.isEnabled = hasDevice
  }

  // --- 데이터 및 서비스 관리 ---

  private fun loadSavedData() {
    val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    targetAddress = prefs.getString("TARGET_ADDRESS", null)
    targetName = prefs.getString("TARGET_NAME", "AG_Test_Module")
    isCalibrated = CalibrationPrefs.isDetectionReady(prefs)
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

  // 팝업 로직
  private fun showErrorPopup() {
    val builder = androidx.appcompat.app.AlertDialog.Builder(this)

    val dialogView = layoutInflater.inflate(R.layout.dialog_warning, null)
    builder.setView(dialogView)

    val dialog = builder.create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.setCancelable(false) // 바깥 눌러도 안 꺼지게

    val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)

    // 버튼을 누르면 -> 서비스에 "해제 명령" 보내고 -> 창 닫기
    btnConfirm.setOnClickListener {
      // 1. 서비스에 "ACTION_CLEAR_ERROR" 명령 발송
      val serviceIntent = Intent(this, BleService::class.java)
      serviceIntent.action = "ACTION_CLEAR_ERROR"
      startService(serviceIntent) // BleService의 onStartCommand 호출

      // 2. 팝업 닫기
      dialog.dismiss()
    }
    dialog.show()
  }

  // 연결 성공 시, 아직 캘리브레이션한 적이 없으면 "패턴 파악" 화면으로 유도하는 팝업.
  // 캘리브레이션 전엔 오조작 감지가 아예 동작하지 않으므로(BleService.judgeWindow 참고)
  // 사용자가 이걸 모르고 지나치지 않게 강제로 안내한다.
  private fun maybeShowCalibrationOnboarding() {
    if (isCalibrationPopupShowing || isFinishing || isDestroyed) return
    if (CalibrationPrefs.isDetectionReady(getSharedPreferences("AgPrefs", MODE_PRIVATE))) return

    isCalibrationPopupShowing = true
    val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
      .setTitle(R.string.title_calibration_onboarding)
      .setMessage(R.string.msg_calibration_onboarding)
      .setPositiveButton(R.string.btn_calibration_onboarding_start) { _, _ ->
        startActivity(Intent(this, DataCollectActivity::class.java))
      }
      .setOnDismissListener { isCalibrationPopupShowing = false }
      .create()
    dialog.show()
  }

  private fun showCapacityWarningPopup() {
    if (isFinishing || isDestroyed) return

    val builder = androidx.appcompat.app.AlertDialog.Builder(this)

    val dialogView = layoutInflater.inflate(R.layout.capacity_warning, null)
    builder.setView(dialogView)

    val dialog = builder.create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.setCancelable(false)

    val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)

    btnConfirm.setOnClickListener {
      val serviceIntent = Intent(this, BleService::class.java)
      serviceIntent.action = "ACTION_CLEAR_CAPACITY"
      startService(serviceIntent)

      dialog.dismiss()
    }
    dialog.show()
  }

  // 👇 [추가] 다른 앱 위에 그리기 권한 요청 (백그라운드 실행 필수)
  private fun checkOverlayPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (!android.provider.Settings.canDrawOverlays(this)) {
        Toast.makeText(this, "비상 시 화면을 띄우기 위해 '다른 앱 위에 표시' 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        val intent = Intent(
          android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          android.net.Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, 1234)
      }
    }
  }

  // 👇 [추가] 풀스크린 인텐트 권한 요청 (Android 14+ 부터는 매니페스트 선언만으로 자동 허용 안 됨.
  //           허용 안 돼 있으면 CriticalActivity가 실행되지 않고 일반 알림으로만 뜬다.)
  private fun checkFullScreenIntentPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val manager = getSystemService(android.app.NotificationManager::class.java)
      if (!manager.canUseFullScreenIntent()) {
        Toast.makeText(this, "비상 시 경고 화면을 강제로 띄우려면 '전체 화면 알림' 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        val intent = Intent(
          android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
          android.net.Uri.parse("package:$packageName")
        )
        startActivity(intent)
      }
    }
  }
}