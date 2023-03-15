package com.example.glucometerkotlin

import java.util.*

object Constants {
    const val BROADCAST_CONNECTION_STATE = "com.appia.bioland.BROADCAST_CONNECTION_STATE"
    const val BROADCAST_SERVICES_DISCOVERED = "com.appia.bioland.BROADCAST_SERVICES_DISCOVERED"
    const val BROADCAST_DEVICE_READY = "com.appia.bioland.DEVICE_READY"
    const val BROADCAST_BOND_STATE = "com.appia.bioland.BROADCAST_BOND_STATE"
    const val BROADCAST_BATTERY_LEVEL = "com.appia.bioland.BROADCAST_BATTERY_LEVEL"
    const val BROADCAST_ERROR = "com.appia.bioland.BROADCAST_ERROR"
    const val BROADCAST_MEASUREMENT = "com.appia.onetouch.BROADCAST_MEASUREMENT"
    const val BROADCAST_COUNTDOWN = "com.appia.onetouch.BROADCAST_COUNTDOWN"
    const val EXTRA_COUNTDOWN = "com.appia.onetouch.EXTRA_COUNTDOWN"
    const val BROADCAST_INFORMATION = "com.appia.onetouch.BROADCAST_INFORMATION"
    const val BROADCAST_COMM_FAILED = "com.appia.onetouch.BROADCAST_COMM_FAILED"


    const val EXTRA_DEVICE = "com.appia.bioland.EXTRA_DEVICE"
    const val EXTRA_CONNECTION_STATE = "com.appia.bioland.EXTRA_CONNECTION_STATE"
    const val EXTRA_DEVICE_NAME = "com.appia.bioland.EXTRA_DEVICE_NAME"
    const val EXTRA_SERVICE_PRIMARY = "com.appia.bioland.EXTRA_SERVICE_PRIMARY"
    const val EXTRA_SERVICE_SECONDARY = "com.appia.bioland.EXTRA_SERVICE_SECONDARY"
    const val EXTRA_BATTERY_LEVEL = "com.appia.bioland.EXTRA_BATTERY_LEVEL"
    const val EXTRA_ERROR_MESSAGE = "com.appia.bioland.EXTRA_ERROR_MESSAGE"
    const val EXTRA_ERROR_CODE = "com.appia.bioland.EXTRA_ERROR_CODE"
    const val EXTRA_BOND_STATE = "com.appia.bioland.EXTRA_BOND_STATE"
    const val EXTRA_LOG_URI = "com.appia.bioland.EXTRA_LOG_URI"
    const val EXTRA_DEVICE_ADDRESS = "com.appia.bioland.EXTRA_DEVICE_ADDRESS"
    const val EXTRA_BATTERY_CAPACITY = "com.appia.onetouch.EXTRA_BATTERY_CAPACITY"
    const val EXTRA_SERIAL_NUMBER = "com.appia.onetouch.EXTRA_SERIAL_NUMBER"


    const val STATE_LINK_LOSS = -1
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTED = 1
    const val STATE_CONNECTING = 2
    const val STATE_DISCONNECTING = 3


    val ONETOUCH_SERVICE_UUID: UUID by lazy { UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b") }
    val ONETOUCH_RX_CHARACTERISTIC_UUID: UUID by lazy { UUID.fromString("af9df7a2-e595-11e3-96b4-0002a5d5c51b") }
    val ONETOUCH_TX_CHARACTERISTIC_UUID: UUID by lazy { UUID.fromString("af9df7a3-e595-11e3-96b4-0002a5d5c51b") }


    const val PACKET_INITIAL_BYTE = 1 // Always 0x02

    const val PACKET_LENGTH_BYTES = 2 // 16 bit packet length (little endian)

    const val PACKET_PAYLOAD_BEGIN_BYTE_A = 1 // Always 0x04 before payload

    const val PACKET_PAYLOAD_BEGIN_BYTE_B = 1 // Always 0x06 before payload when receiving

    const val PACKET_PAYLOAD_END_BYTE = 1 // Always 0x03 after payload

    const val PACKET_CRC_BYTES = 2 // 16 bit checksum (little endian)

    val PACKET_PAYLOAD_BEGIN by lazy {
        PACKET_INITIAL_BYTE + PACKET_LENGTH_BYTES + PACKET_PAYLOAD_BEGIN_BYTE_A + PACKET_PAYLOAD_BEGIN_BYTE_B
    }

    val PROTOCOL_OVERHEAD by lazy {
        PACKET_INITIAL_BYTE + PACKET_LENGTH_BYTES + PACKET_PAYLOAD_BEGIN_BYTE_A + PACKET_PAYLOAD_BEGIN_BYTE_B
        +PACKET_PAYLOAD_END_BYTE + PACKET_CRC_BYTES
    }

    val PROTOCOL_SENDING_OVERHEAD by lazy {
        PACKET_INITIAL_BYTE + PACKET_LENGTH_BYTES + PACKET_PAYLOAD_BEGIN_BYTE_A + PACKET_PAYLOAD_END_BYTE
        +PACKET_CRC_BYTES
    }

    const val DEVICE_TIME_OFFSET = 946684799 // Year 2000 UNIX time

    const val BLEUART_HEADER_SIZE = 1

    const val HEADER_FIRST_PACKET = 0x00.toByte()
    const val HEADER_FRAG_PACKET = 0x40.toByte()
    const val HEADER_ACK_PACKET = 0x80.toByte()


}