package com.example.agproject

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ScanActivity : AppCompatActivity() {

  // 1. 새로운 UI 컴포넌트 (RecyclerView)
  private lateinit var recyclerView: RecyclerView
  private lateinit var progressBar: ProgressBar
  private lateinit var btnBack: ImageView

  private lateinit var deviceAdapter: DeviceAdapter
  private val deviceList = ArrayList<BluetoothDevice>()

  private var bluetoothAdapter: BluetoothAdapter? = null
  private var scanning = false
  private val handler = Handler(Looper.getMainLooper())
  private val SCAN_PERIOD: Long = 10000 // 10초간 스캔

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_scan)

    // 2. ID 연결 (새로운 activity_scan.xml ID 사용)
    recyclerView = findViewById(R.id.recyclerView)
    progressBar = findViewById(R.id.progressBar)
    btnBack = findViewById(R.id.btnBack)

    // 3. 리스트 설정 (RecyclerView)
    deviceAdapter = DeviceAdapter(deviceList) { device ->
      connectToDevice(device)
    }
    recyclerView.layoutManager = LinearLayoutManager(this)
    recyclerView.adapter = deviceAdapter

    // 4. 뒤로가기 버튼
    btnBack.setOnClickListener { finish() }

    // 5. 블루투스 준비 및 스캔 시작
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter

    if (hasPermissions()) {
      startScan()
    } else {
      Toast.makeText(this, "권한이 필요합니다.", Toast.LENGTH_SHORT).show()
    }
  }

  @SuppressLint("MissingPermission")
  private fun startScan() {
    if (scanning) return

    // 10초 뒤 스캔 중지
    handler.postDelayed({
      scanning = false
      bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
      progressBar.visibility = View.GONE // 로딩바 숨김
      Toast.makeText(this, "스캔 완료", Toast.LENGTH_SHORT).show()
    }, SCAN_PERIOD)

    scanning = true
    progressBar.visibility = View.VISIBLE // 로딩바 표시
    deviceList.clear()
    deviceAdapter.notifyDataSetChanged()

    bluetoothAdapter?.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), leScanCallback)
  }

  private val leScanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      val device = result.device
      // 중복 제거 후 리스트 추가
      if (!deviceList.any { it.address == device.address }) {
        // 이름이 있는 기기만 보여주기 (선택사항)
        if (device.name != null) {
          deviceList.add(device)
          // UI 업데이트는 메인 스레드에서
          runOnUiThread { deviceAdapter.notifyDataSetChanged() }
        }
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun connectToDevice(device: BluetoothDevice) {
    // 선택한 기기 정보를 저장하고 메인으로 돌아감
    val prefs = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    prefs.edit()
      .putString("TARGET_ADDRESS", device.address)
      .putString("TARGET_NAME", device.name ?: "Unknown")
      .apply()

    Toast.makeText(this, "${device.name} 선택됨", Toast.LENGTH_SHORT).show()

    // 메인 화면 재시작 (UI 갱신을 위해)
    val intent = Intent(this, MainActivity::class.java)
    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    startActivity(intent)
    finish()
  }

  private fun hasPermissions(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }
    return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
  }
}