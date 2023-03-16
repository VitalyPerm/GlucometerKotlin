package com.example.glucometerkotlin

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.ui.log
import kotlinx.coroutines.flow.MutableStateFlow

class OneTouchService : Service() {


    companion object {
        val measurements = MutableStateFlow<List<OneTouchMeasurement>>(emptyList())
        lateinit var device: BluetoothDevice

        fun run(context: Context) = Intent(context, OneTouchService::class.java)
    }

    lateinit var mManager: OneTouchManager

    override fun onCreate() {
        super.onCreate()
        log("service onCreate")
        mManager = OneTouchManager(this, callBack = ::onMeasurementsReceived)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("service onStartCommand")

        mManager.connect(device)
            .useAutoConnect(false)
            .retry(3, 100)
            .enqueue()
        return START_REDELIVER_INTENT
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(p0: Intent?): IBinder? = null

    private fun onMeasurementsReceived(measurements: List<OneTouchMeasurement>) {
        val currList = OneTouchService.measurements.value.toMutableList()
        currList.addAll(measurements)
        OneTouchService.measurements.value = currList
    }
}