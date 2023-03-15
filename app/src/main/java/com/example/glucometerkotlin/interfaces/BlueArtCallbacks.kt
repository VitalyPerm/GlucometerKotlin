package com.example.glucometerkotlin.interfaces

interface BlueArtCallbacks {

    fun sendData(aBytes: ByteArray?)

    fun onPacketReceived(aBytes: ByteArray?)

}