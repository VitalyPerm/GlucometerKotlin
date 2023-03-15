package com.example.glucometerkotlin.interfaces

import com.example.glucometerkotlin.entity.OneTouchMeasurement

interface ProtocolCallBacks {

    fun sendData(bytes: ByteArray?)

    fun onMeasurementsReceived(measurements: List<OneTouchMeasurement>)

    fun onProtocolError(message: String)

}