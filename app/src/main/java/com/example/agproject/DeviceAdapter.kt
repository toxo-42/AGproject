package com.example.agproject

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
  private val devices: ArrayList<BluetoothDevice>,
  private val onClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

  inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val tvName: TextView = view.findViewById(R.id.tvDeviceName)
    val tvAddress: TextView = view.findViewById(R.id.tvMacAddress)
//    val btnConnect: TextView = view.findViewById(R.id.tvConnectBtn) // 연결 버튼 텍스트

    init {
      view.setOnClickListener { onClick(devices[adapterPosition]) }
    }
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
    // 우리가 만든 item_device.xml 디자인을 가져옵니다
    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
    return DeviceViewHolder(view)
  }

  @SuppressLint("MissingPermission")
  override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
    val device = devices[position]
    holder.tvName.text = device.name ?: "Unknown Device"
    holder.tvAddress.text = device.address

    // item_device.xml에 tvConnectBtn(또는 그냥 텍스트) ID가 없으면 에러날 수 있으니
    // item_device.xml에 android:id="@+id/tvConnectBtn" 를 추가해주시거나
    // 위 ViewHolder에서 btnConnect 줄을 지우셔도 됩니다.
  }

  override fun getItemCount(): Int = devices.size
}