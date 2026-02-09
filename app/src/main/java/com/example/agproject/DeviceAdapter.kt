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

    init {
      view.setOnClickListener { onClick(devices[adapterPosition]) }
    }
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
    //item_device.xml 디자인 import
    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
    return DeviceViewHolder(view)
  }

  @SuppressLint("MissingPermission")
  override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
    val device = devices[position]
    holder.tvName.text = device.name ?: "Unknown Device"
    holder.tvAddress.text = device.address
  }

  override fun getItemCount(): Int = devices.size
}