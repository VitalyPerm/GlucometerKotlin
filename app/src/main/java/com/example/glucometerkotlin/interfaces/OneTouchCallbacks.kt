package com.example.glucometerkotlin.interfaces

import com.example.glucometerkotlin.entity.OneTouchMeasurement
import no.nordicsemi.android.ble.BleManagerCallbacks

interface OneTouchCallbacks: BleManagerCallbacks {

    fun onMeasurementsReceived(measurements: List<OneTouchMeasurement>)

    fun onProtocolError(message: String)

}