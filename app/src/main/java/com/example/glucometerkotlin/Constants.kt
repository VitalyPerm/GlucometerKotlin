package com.example.glucometerkotlin

object Constants {
    const val BROADCAST_CONNECTION_STATE = "com.appia.bioland.BROADCAST_CONNECTION_STATE"
    const val BROADCAST_SERVICES_DISCOVERED = "com.appia.bioland.BROADCAST_SERVICES_DISCOVERED"
    const val BROADCAST_DEVICE_READY = "com.appia.bioland.DEVICE_READY"
    const val BROADCAST_BOND_STATE = "com.appia.bioland.BROADCAST_BOND_STATE"
    const val BROADCAST_BATTERY_LEVEL = "com.appia.bioland.BROADCAST_BATTERY_LEVEL"
    const val BROADCAST_ERROR = "com.appia.bioland.BROADCAST_ERROR"


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


    const val STATE_LINK_LOSS = -1
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTED = 1
    const val STATE_CONNECTING = 2
    const val STATE_DISCONNECTING = 3
}