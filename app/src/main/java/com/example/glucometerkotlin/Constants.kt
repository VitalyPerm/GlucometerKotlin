package com.example.glucometerkotlin

import java.util.*

object Constants {


    val ONETOUCH_SERVICE_UUID: UUID by lazy { UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b") }
    val ONETOUCH_RX_CHARACTERISTIC_UUID: UUID by lazy { UUID.fromString("af9df7a2-e595-11e3-96b4-0002a5d5c51b") }
    val ONETOUCH_TX_CHARACTERISTIC_UUID: UUID by lazy { UUID.fromString("af9df7a3-e595-11e3-96b4-0002a5d5c51b") }

}