package com.example.glucometerkotlin

import java.util.*

object Constants {
    const val DEVICE_NAME = "OneTouch"


    val ONETOUCH_SERVICE_UUID: UUID by lazy { UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b") }
    val ONETOUCH_RX_CHARACTERISTIC_UUID: UUID by lazy { UUID.fromString("af9df7a2-e595-11e3-96b4-0002a5d5c51b") }
    val ONETOUCH_TX_CHARACTERISTIC_UUID: UUID by lazy { UUID.fromString("af9df7a3-e595-11e3-96b4-0002a5d5c51b") }

    const val PACKET_PAYLOAD_BEGIN = 5

    const val PROTOCOL_OVERHEAD = 8

    const val PROTOCOL_SENDING_OVERHEAD = 7

    const val DEVICE_TIME_OFFSET = 946684799 // Year 2000 UNIX time

    const val BLEUART_HEADER_SIZE = 1

}