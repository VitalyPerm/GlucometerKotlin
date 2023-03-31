package com.example.glucometerkotlin

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.ui.MainActivity
import com.example.glucometerkotlin.ui.log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.ceil


private enum class DataState { IDLE, SENDING, RECEIVING }

private enum class PacketState {
    IDLE, WAITING_TIME, WAITING_HIGHEST_ID, WAITING_OLDEST_INDEX, WAITING_MEASUREMENT
}

class OneTouchManager : BleManager(App.instance) {

    private var packetState: PacketState = PacketState.IDLE
    private var dataState: DataState = DataState.IDLE
    private var synced = false
    private var highestMeasID = 0
    private var highestStoredMeasID = 0
    private val maxPayloadSize: Int = 19
    private val headerSize = 1
    private var nPackets = 0
    private val payloadStartIndex = 5
    private val protocolOverhead = 8
    private val protocolSendingOverhead = 7
    private val deviceTimeOffset = 946684799
    private val serviceUuid: UUID by lazy { UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b") }
    private val rxCharacteristicUuid: UUID by lazy { UUID.fromString("af9df7a2-e595-11e3-96b4-0002a5d5c51b") }
    private val txCharacteristicUuid: UUID by lazy { UUID.fromString("af9df7a3-e595-11e3-96b4-0002a5d5c51b") }
    private var txData: ByteArrayInputStream? = null
    private var rxData: ByteArrayOutputStream? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    val measurements = mutableListOf<OneTouchMeasurement>()


    fun clear() {
        txData = null
        rxData = null
        rxCharacteristic = null
        txCharacteristic = null
    }


    private fun onPacketReceived(bytes: ByteArray?) {
        if (bytes == null) return
        val computedCRC: Int = computeCRC(bytes, bytes.size - 2)
        val receivedCRC: Int =
            (bytes[bytes.size - 1].toInt() shl 8 and 0xFF00 or (bytes[bytes.size - 2].toInt() and 0x00FF))
        if (receivedCRC != computedCRC) return
        val length = (bytes[2].toInt() shl 8 and 0xFF00 or (bytes[1].toInt() and 0x00FF))
        if ((bytes.size == length && bytes.size >= protocolOverhead).not()) return

        val payload: ByteArray = bytes.copyOfRange(
            payloadStartIndex, payloadStartIndex + bytes.size - protocolOverhead
        )

        when (packetState) {
            PacketState.WAITING_TIME -> {
                if (payload.size == 4) {
                    val time = computeUnixTime(payload).toLong()
                    log("Glucometer time is: " + Date(1000 * time).toString())
                    log("System time is: " + Date(System.currentTimeMillis()).toString())
                    val currTime = ((System.currentTimeMillis() / 1000) - deviceTimeOffset)
                    val array = byteArrayOf(
                        0x20, 0x01, (currTime and 0x000000FFL).toByte(),
                        (currTime and 0x0000FF00L shr 8).toByte(),
                        (currTime and 0x00FF0000L shr 16).toByte(),
                        (currTime and 0xFF000000L shr 24).toByte()
                    )
                    sendPacket(buildPacket(array))
                    packetState = PacketState.WAITING_TIME
                } else if (payload.isEmpty()) {
                    if (!synced) {
                        sendPacket(buildPacket(byteArrayOf(0x27, 0x00)))
                        packetState = PacketState.WAITING_OLDEST_INDEX
                    } else getHighestRecordID()
                }
            }

            PacketState.WAITING_HIGHEST_ID -> if (payload.size == 4) {
                val highestID = intFromByteArray(payload)
                if (highestID < highestMeasID) return
                highestStoredMeasID = highestMeasID
                highestMeasID = highestID
                log("There are " + (highestMeasID - highestStoredMeasID) + " new records!")
                getMeasurementsById(highestStoredMeasID + 1)
            }

            PacketState.WAITING_OLDEST_INDEX -> if (payload.size == 2) {
                val recordCount: Short = shortFromByteArray(payload)
                log("Total records stored on Glucometer: $recordCount")
                getMeasurementsByIndex(recordCount - 1)
            }

            PacketState.WAITING_MEASUREMENT -> if (payload.size == 11) {
                val measTime: Int = computeUnixTime(payload.copyOfRange(0, 4))
                val measValue: Short = shortFromByteArray(payload.copyOfRange(4, 6))
                val measError: Short = shortFromByteArray(payload.copyOfRange(9, 11))
                handleMeasurementByID(measTime, measValue, measError)
            } else if (payload.isEmpty()) {
                handleMeasurementByID(0, 0.toShort(), 0.toShort())
            } else if (payload.size == 16) {
                val measIndex: Short = shortFromByteArray(payload.copyOfRange(0, 2))
                val measID: Short = shortFromByteArray(payload.copyOfRange(3, 5))
                val measTime: Int = computeUnixTime(payload.copyOfRange(5, 9))
                val measValue: Short = shortFromByteArray(payload.copyOfRange(9, 11))
                highestMeasID = measID.toInt().coerceAtLeast(highestMeasID)
                highestStoredMeasID = highestMeasID
                val date = Date(1000 * measTime.toLong())
                val measurement = OneTouchMeasurement(
                    date = date,
                    glucose = measValue.toFloat().div(18),
                    id = measID.toString(),
                    errorId = 0
                )
                measurements.add(measurement)
                if (measIndex.toInt() == 0) {
                    synced = true
                    getHighestRecordID()
                } else getMeasurementsByIndex(measIndex - 1)
            }
            else -> {}
        }
    }


    private fun handleMeasurementByID(aMeasTime: Int, aMeasValue: Short, aMeasError: Short) {
        highestStoredMeasID++
        if (aMeasTime != 0) {
            val date = Date(1000 * aMeasTime.toLong())
            val measurement = OneTouchMeasurement(
                date = date,
                errorId = aMeasError.toInt(),
                id = highestStoredMeasID.toString(),
                glucose = aMeasValue.toFloat().div(18)
            )
            measurements.add(measurement)
        }
        if (highestStoredMeasID < highestMeasID) getMeasurementsById(highestStoredMeasID + 1)
        else {
            log("FINISH!!!")
            MainActivity.allMeasurementsReceived.tryEmit(Unit)
        }
    }

    private fun getMeasurementsByIndex(index: Int) {
        val array = byteArrayOf(
            0x31, 0x02, (index and 0x00FF).toByte(), (index and 0xFF00 shr 8).toByte(), 0x00
        )
        sendPacket(buildPacket(array))
        packetState = PacketState.WAITING_MEASUREMENT
    }

    private fun getHighestRecordID() {
        sendPacket(buildPacket(byteArrayOf(0x0A, 0x02, 0x06)))
        packetState = PacketState.WAITING_HIGHEST_ID
    }

    private fun getMeasurementsById(id: Int) {
        val array =
            byteArrayOf(0xB3.toByte(), (id and 0x00FF).toByte(), (id and 0xFF00 shr 8).toByte())
        sendPacket(buildPacket(array))
        packetState = PacketState.WAITING_MEASUREMENT
    }

    private fun buildPacket(payload: ByteArray): ByteArray {
        val packetLength: Int = protocolSendingOverhead + payload.size
        val packet = ByteArray(packetLength)
        packet[0] = 0x02.toByte()
        packet[1] = packetLength.toByte()
        packet[2] = 0x00.toByte()
        packet[3] = 0x04.toByte()
        System.arraycopy(payload, 0, packet, 4, payload.size)
        packet[4 + payload.size] = 0x03.toByte()
        val length = packetLength - 2
        val crc: Int = computeCRC(packet, length)
        packet[length] = (crc and 0x00FF).toByte()
        packet[length + 1] = (crc and 0xFF00 shr 8).toByte()
        return packet
    }

    private fun getTime() {
        val getTimeByteArray = byteArrayOf(0x20, 0x02)
        sendPacket(buildPacket(getTimeByteArray))
        packetState = PacketState.WAITING_TIME
    }


    private fun sendPacket(aBytes: ByteArray) {
        dataState = DataState.SENDING
        nPackets = ceil(aBytes.size / maxPayloadSize.toDouble()).toInt()
        txData = ByteArrayInputStream(aBytes)
        val nBytesToSend = maxPayloadSize
            .coerceAtMost(headerSize + (txData?.available() ?: 0))
        val bytesToSend = ByteArray(nBytesToSend)
        bytesToSend[0] = (0x0F and nPackets).toByte()
        bytesToSend[0] = (bytesToSend[0].toInt() or 0x00.toByte().toInt()).toByte()
        txData?.read(bytesToSend, headerSize, nBytesToSend - headerSize)
        sendData(bytesToSend)
    }

    private fun onDataReceived(aBytes: ByteArray) {
        when (dataState) {
            DataState.IDLE -> if (headerIs(aBytes[0], 0x00.toByte())) {
                nPackets = aBytes[0].toInt() and 0x0F
                rxData = ByteArrayOutputStream()
                handleDataReceived(aBytes)
            }
            DataState.SENDING ->
                if (aBytes.size == 1 && headerIs(aBytes[0], 0x80.toByte())) {
                    val nAck = aBytes[0].toInt() and 0x0F
                    if (nAck != nPackets) return
                    nPackets--
                    if (nPackets == 0) {
                        txData = null
                        dataState = DataState.IDLE
                    } else {
                        val nBytesToSend = maxPayloadSize
                            .coerceAtMost(headerSize + (txData?.available() ?: 0))
                        val bytesToSend = ByteArray(nBytesToSend)
                        bytesToSend[0] = (0x40 or (0x0F and nPackets)).toByte()
                        txData?.read(bytesToSend, headerSize, nBytesToSend - headerSize)
                        sendData(bytesToSend)
                    }
                }

            DataState.RECEIVING -> if (headerIs(aBytes[0], 0x40.toByte())) {
                val remainingPackets = aBytes[0].toInt() and 0x0F
                if (remainingPackets == nPackets) handleDataReceived(aBytes)
            }
        }
    }

    private fun handleDataReceived(aBytes: ByteArray) {
        rxData?.write(aBytes, 1, aBytes.size - 1)
        val bytesToSend = ByteArray(1)
        bytesToSend[0] = (0x80 or (0x0F and nPackets)).toByte()
        sendData(bytesToSend)
        nPackets--
        if (nPackets > 0) dataState = DataState.RECEIVING
        else {
            txData = null
            dataState = DataState.IDLE
            onPacketReceived(rxData?.toByteArray())
        }
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        gatt.getService(serviceUuid)?.let { service ->
            rxCharacteristic = service.getCharacteristic(rxCharacteristicUuid)
            txCharacteristic = service.getCharacteristic(txCharacteristicUuid)
        }
        var writeRequest = false
        var writeCommand = false
        rxCharacteristic?.also { rx ->
            val properties = rx.properties
            writeRequest = properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0
            writeCommand =
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return (rxCharacteristic != null) && (txCharacteristic != null) && (writeCommand || writeRequest)
    }

    override fun onDeviceReady() {
        super.onDeviceReady()
        if (packetState == PacketState.IDLE) getTime()
    }

    override fun initialize() {
        super.initialize()
        requestMtu(23).enqueue()

        setNotificationCallback(txCharacteristic)
            .with { _, data: Data -> data.value?.let(::onDataReceived) }

        enableNotifications(txCharacteristic).done { getTime() }.enqueue()
    }

    private fun sendData(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        rxCharacteristic?.let { rx ->
            writeCharacteristic(rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .with { _, data: Data -> log("$data sent") }
                .enqueue()
        }
    }

    private fun computeCRC(data: ByteArray?, length: Int): Int {
        if ((data == null) || (0 > data.size - 1) || (0 + length > data.size)) return 0
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[0 + i].toInt() shl 8)
            for (j in 0..7) {
                crc = if (crc and 0x8000 > 0) crc shl 1 xor 0x1021 else crc shl 1
            }
        }
        return crc and 0xFFFF
    }

    private fun computeUnixTime(sysTime: ByteArray) = deviceTimeOffset + intFromByteArray(sysTime)

    private fun headerIs(aHeader: Byte, aHeaderType: Byte) =
        aHeader.toInt() and 0xF0.toByte().toInt() == aHeaderType.toInt()

    private fun intFromByteArray(bytes: ByteArray) =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortFromByteArray(bytes: ByteArray) =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short
}