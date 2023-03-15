package com.example.glucometerkotlin.entity

import java.util.*

data class OneTouchInfo(
    val protocolVersion: Int,
    val batteryCapacity: Int,
    val serialNumber: ByteArray,
    val productionDate: GregorianCalendar
) {


    private fun bytesToHex(bytes: ByteArray): String {
        val hexArray = "0123456789ABCDEF".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OneTouchInfo

        if (protocolVersion != other.protocolVersion) return false
        if (batteryCapacity != other.batteryCapacity) return false
        if (!serialNumber.contentEquals(other.serialNumber)) return false
        if (productionDate != other.productionDate) return false

        return true
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + batteryCapacity
        result = 31 * result + serialNumber.contentHashCode()
        result = 31 * result + productionDate.hashCode()
        return result
    }

    override fun toString(): String =
        "Battery: $batteryCapacity % Protocol: $protocolVersion Serial N°: ${bytesToHex(serialNumber)}"
}
