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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ScanActivity : AppCompatActivity() {

  private lateinit var deviceAdapter: DeviceAdapter
  private var bluetoothAdapter: BluetoothAdapter? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 화면 레이아웃 생성 (XML 없이 코드로 간단히 리스트만 띄움)
    val recyclerView = RecyclerView(this)
    recyclerView.layoutManager = LinearLayoutManager(this)
    setContentView(recyclerView)

    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    bluetoothAdapter = bluetoothManager.adapter

    deviceAdapter = DeviceAdapter { device ->
      saveDeviceAndFinish(device)
    }
    recyclerView.adapter = deviceAdapter

    startScan()
    Toast.makeText(this, "주변 기기를 검색합니다...", Toast.LENGTH_SHORT).show()
  }

  @SuppressLint("MissingPermission")
  private fun startScan() {
    if (!hasPermissions()) return
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    bluetoothAdapter?.bluetoothLeScanner?.startScan(null, settings, scanCallback)
  }

  @SuppressLint("MissingPermission")
  private fun saveDeviceAndFinish(device: BluetoothDevice) {
    // 1. 선택한 기기 주소를 '영구 저장' (앱 껐다 켜도 기억함)
    val prefs: SharedPreferences = getSharedPreferences("AgPrefs", MODE_PRIVATE)
    prefs.edit().putString("TARGET_ADDRESS", device.address).apply()

    Toast.makeText(this, "${device.name} 기기가 등록되었습니다.", Toast.LENGTH_SHORT).show()

    // 2. 스캔 중단 및 창 닫기
    bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    finish()
  }

  private val scanCallback = object : ScanCallback() {
    @SuppressLint("MissingPermission")
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
      result?.device?.let { deviceAdapter.addDevice(it) }
    }
  }

  private fun hasPermissions(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }
    return true
  }
}