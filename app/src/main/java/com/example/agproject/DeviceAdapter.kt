package com.example.agproject

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// 기기 목록을 관리하고 화면에 보여주는 어댑터
class DeviceAdapter(
  private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

  private val devices = ArrayList<BluetoothDevice>()

  // 목록에 기기 추가 (중복 방지)
  fun addDevice(device: BluetoothDevice) {
    if (!devices.contains(device)) {
      devices.add(device)
      notifyItemInserted(devices.size - 1)
    }
  }

  // 목록 초기화
  fun clear() {
    devices.clear()
    notifyDataSetChanged()
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
    // 간단한 리스트 아이템 레이아웃을 사용 (안드로이드 기본 제공)
    val view = LayoutInflater.from(parent.context)
      .inflate(android.R.layout.simple_list_item_2, parent, false)
    return DeviceViewHolder(view)
  }

  override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
    holder.bind(devices[position])
  }

  override fun getItemCount() = devices.size

  inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val text1: TextView = itemView.findViewById(android.R.id.text1)
    private val text2: TextView = itemView.findViewById(android.R.id.text2)

    @SuppressLint("MissingPermission")
    fun bind(device: BluetoothDevice) {
      // 이름과 주소 표시
      text1.text = device.name ?: "이름 없음"
      text2.text = device.address

      // 터치하면 MainActivity로 알려줌
      itemView.setOnClickListener { onClick(device) }
    }
  }
}