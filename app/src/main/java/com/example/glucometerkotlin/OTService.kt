package com.example.glucometerkotlin

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import kotlinx.coroutines.flow.MutableStateFlow

class OTService : Service() {

    companion object {
        val measurements = MutableStateFlow<List<OneTouchMeasurement>>(emptyList())
        lateinit var device: BluetoothDevice

        fun run(context: Context) = Intent(context, OneTouchService::class.java)
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }




    override fun onBind(p0: Intent?): IBinder? = null


}