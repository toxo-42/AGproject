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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

  private lateinit var deviceAdapter: DeviceAdapter
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var isScanning = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // 블루투스 매니저 가져오기
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter

    // 리스트(RecyclerView) 설정
    val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
    recyclerView.layoutManager = LinearLayoutManager(this)

    // 어댑터 연결 (아이템 클릭 시 동작 정의)
    deviceAdapter = DeviceAdapter { device ->
      connectToDevice(device)
    }
    recyclerView.adapter = deviceAdapter

    // 버튼 이벤트
    findViewById<Button>(R.id.btnScan).setOnClickListener {
      if (isScanning) {
        stopScan()
      } else {
        startScan()
      }
    }

    // 권한 요청 (앱 켜자마자)
    checkPermissions()
  }

  @SuppressLint("MissingPermission")
  private fun startScan() {
    if (!hasPermissions()) return

    deviceAdapter.clear() // 목록 초기화
    isScanning = true
    updateStatus("주변 기기 검색 중...")
    findViewById<Button>(R.id.btnScan).text = "검색 중단"

    // 스캔 시작 (빈 필터 = 모든 기기 검색)
    bluetoothAdapter?.bluetoothLeScanner?.startScan(null, scanSettings(), scanCallback)
  }

  @SuppressLint("MissingPermission")
  private fun stopScan() {
    if (!hasPermissions()) return

    isScanning = false
    updateStatus("검색 완료. 기기를 선택하세요.")
    findViewById<Button>(R.id.btnScan).text = "🔍 주변 기기 검색"

    bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
  }

  // 기기를 터치했을 때 실행되는 함수
  @SuppressLint("MissingPermission")
  private fun connectToDevice(device: BluetoothDevice) {
    stopScan() // 연결하려면 스캔 멈춰야 함

    Toast.makeText(this, "${device.name}에 연결을 시도합니다.", Toast.LENGTH_SHORT).show()

    // 서비스 시작 (선택한 기기의 주소를 담아서 보냄!)
    val serviceIntent = Intent(this, BleService::class.java)
    serviceIntent.putExtra("TARGET_ADDRESS", device.address)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(serviceIntent)
    } else {
      startService(serviceIntent)
    }
  }

  // 스캔 결과 받는 콜백
  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result?.device?.let { device ->
        // 리스트에 추가 (어댑터가 알아서 화면 갱신)
        deviceAdapter.addDevice(device)
      }
    }
  }

  private fun scanSettings(): ScanSettings {
    return ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .build()
  }

  private fun updateStatus(text: String) {
    findViewById<TextView>(R.id.tvStatus).text = text
  }

  // --- 권한 관련 (기존과 동일) ---
  private fun hasPermissions(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }
    return true
  }

  private fun checkPermissions() {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
      )
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    ActivityCompat.requestPermissions(this, permissions, 1)
  }
}