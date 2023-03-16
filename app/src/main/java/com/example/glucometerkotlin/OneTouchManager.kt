package com.example.glucometerkotlin

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.interfaces.OneTouchCallbacks
import com.example.glucometerkotlin.ui.log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.ceil


private enum class StateBA {
    IDLE, SENDING, RECEIVING
}

enum class State {
    IDLE, WAITING_TIME, WAITING_HIGHEST_ID, WAITING_OLDEST_INDEX, WAITING_MEASUREMENT
}

class OneTouchManager(context: Context) : BleManager<OneTouchCallbacks>(context) {

    private var mState: State = State.IDLE
    private var timer: Timer = Timer()
    private var mSynced = false
    private var mHighestMeasID: Short = 0
    private var mHighestStoredMeasID: Short = 0

    // BA
    private var mStateBA: StateBA = StateBA.IDLE
    private var mMaxPayloadSize: Int = 19
    private var mNpackets = 0
    private var mTxData: ByteArrayInputStream? = null
    private var mRxData: ByteArrayOutputStream? = null

    private val mMeasurements = mutableListOf<OneTouchMeasurement>()


    private fun onPacketReceivedP(aBytes: ByteArray?) {
        kotlin.runCatching {
            val bytes = aBytes ?: return
            val payload: ByteArray = extractPayloadP(bytes)
            when (mState) {
                State.WAITING_TIME -> if (payload.size == 4) handleTimeGetP(computeUnixTimeP(payload).toLong())
                else if (payload.isEmpty()) handleTimeSetP()
                else log("Unexpected payload waiting for time request!")

                State.WAITING_HIGHEST_ID -> if (payload.size == 4) {
                    val highestID: Short = intFromByteArray(payload).toShort()
                    log("Highest record ID: $highestID")
                    if (highestID > mHighestMeasID) {
                        mHighestStoredMeasID = mHighestMeasID
                        mHighestMeasID = highestID
                        log("There are " + (mHighestMeasID - mHighestStoredMeasID) + " new records!")
                        getMeasurementsByIdP(mHighestStoredMeasID + 1)
                    } else log("Measurements are up to date!")
                } else log("Unexpected payload waiting for highest record ID!")

                State.WAITING_OLDEST_INDEX -> if (payload.size == 2) {
                    val recordCount: Short = shortFromByteArray(payload)
                    log("Total records stored on Glucometer: $recordCount")
                    // After getting the number of stored measurements, start from the oldest one!
                    getMeasurementsByIndexP(recordCount - 1)
                } else log("Unexpected payload waiting for total record request!")

                State.WAITING_MEASUREMENT -> if (payload.size == 11) {
                    val measTime: Int = computeUnixTimeP(payload.copyOfRange(0, 0 + 4))
                    val measValue: Short = shortFromByteArray(payload.copyOfRange(4, 4 + 2))
                    val measError: Short = shortFromByteArray(payload.copyOfRange(9, 9 + 2))
                    handleMeasurementByIDP(measTime, measValue, measError)
                } else if (payload.isEmpty()) {
                    // Measurement was not found! Indicate with aMeasTime=0
                    handleMeasurementByIDP(0, 0.toShort(), 0.toShort())
                } else if (payload.size == 16) {
                    val measIndex: Short = shortFromByteArray(payload.copyOfRange(0, 0 + 2))
                    val measID: Short = shortFromByteArray(payload.copyOfRange(3, 3 + 2))
                    val measTime: Int = computeUnixTimeP(payload.copyOfRange(5, 5 + 4))
                    val measValue: Short = shortFromByteArray(payload.copyOfRange(9, 9 + 2))
                    handleMeasurementByIndexP(
                        measIndex,
                        measID,
                        measTime,
                        measValue
                    )
                }
                else -> {}
            }
        }
    }

    @Throws(Exception::class)
    private fun extractPayloadP(packet: ByteArray): ByteArray {
        val computedCRC: Int = computeCRCP(packet, 0, packet.size - 2)
        val receivedCRC: Int =
            (packet[packet.size - 1].toInt() shl 8 and 0xFF00 or (packet[packet.size - 2].toInt() and 0x00FF))
        val isCRC16 = receivedCRC == computedCRC
        if (isCRC16) {
            val length = (packet[2].toInt() shl 8 and 0xFF00 or (packet[1].toInt() and 0x00FF))
            return if (packet.size == length && packet.size >= Constants.PROTOCOL_OVERHEAD) {
                packet.copyOfRange(
                    Constants.PACKET_PAYLOAD_BEGIN,
                    Constants.PACKET_PAYLOAD_BEGIN + packet.size - Constants.PROTOCOL_OVERHEAD
                )
            } else throw Exception("Bad Length! Received")
        } else throw Exception("Bad CRC! Expected ")
    }

    private fun handleMeasurementByIndexP(
        aMeasIndex: Short,
        aMeasID: Short,
        aMeasTime: Int,
        aMeasValue: Short,
    ) {
        log(
            "Measurement " + aMeasIndex + " |" +
                    " Value: " + aMeasValue +
                    " Time: " + Date(1000 * aMeasTime.toLong()).toString() +
                    " ID:" + aMeasID
        )

        // Update latest ID
        mHighestMeasID = aMeasID.toInt().coerceAtLeast(mHighestMeasID.toInt()).toShort()
        mHighestStoredMeasID = mHighestMeasID
        val date = Date(1000 * aMeasTime.toLong())
        val measurement = OneTouchMeasurement(
            mDate = date,
            mGlucose = aMeasValue.toFloat().div(18),
            mId = aMeasID.toString(),
            mErrorId = 0
        )
        mMeasurements.add(measurement)
        if (aMeasIndex.toInt() == 0) { // The latest measurement
            // Notify application
            onMeasurementsReceived(mMeasurements)
            mMeasurements.clear()
            mSynced = true
            getHighestRecordIDP()
            // Start timer to poll for new measurements??
        } else {
            log("Requesting next measurement: " + (aMeasIndex - 1))
            getMeasurementsByIndexP(aMeasIndex - 1)
        }
    }

    private fun computeUnixTimeP(sysTime: ByteArray): Int {
        return Constants.DEVICE_TIME_OFFSET + intFromByteArray(sysTime)
    }

    private fun handleMeasurementByIDP(aMeasTime: Int, aMeasValue: Short, aMeasError: Short) {
        // Update latest ID
        mHighestStoredMeasID++
        if (aMeasTime != 0) { // If measurement was found..
            log(
                "Measurement - Value: " + aMeasValue +
                        " Time: " + Date(1000 * aMeasTime.toLong()).toString() +
                        " Error: " + aMeasError
            )
            val date = Date(1000 * aMeasTime.toLong())
            val measurement = OneTouchMeasurement(
                mDate = date,
                mErrorId = aMeasError.toInt(),
                mId = mHighestStoredMeasID.toString(),
                mGlucose = aMeasValue.toFloat().div(18)
            )
            mMeasurements.add(measurement)
        } else log("Measurement with ID: $mHighestStoredMeasID was not found!")
        if (mHighestStoredMeasID < mHighestMeasID) {
            log("Requesting next measurement, ID: " + (mHighestStoredMeasID + 1))
            getMeasurementsByIdP(mHighestStoredMeasID + 1)
        } else {
            log("Measurement up to date!")
            // Notify application
            onMeasurementsReceived(mMeasurements)
            mMeasurements.clear()
            // Start timer to poll for new measurements??
        }
    }

    private fun getMeasurementsByIndexP(index: Int) {
        val array = byteArrayOf(
            0x31, 0x02, (index and 0x00FF).toByte(), (index and 0xFF00 shr 8).toByte(),
            0x00
        )
        sendPacketBA(buildPacketP(array))
        mState = State.WAITING_MEASUREMENT
    }

    private fun handleTimeGetP(aSeconds: Long) {
        log("Glucometer time is: " + Date(1000 * aSeconds).toString())
        log("System time is: " + Date(System.currentTimeMillis()).toString())
        setTimeP()
    }

    private fun handleTimeSetP() {
        log("Time has been set!")
        if (!mSynced) {
            getOldestRecordIndexP()
        } else {
            getHighestRecordIDP()
        }
    }

    private fun getHighestRecordIDP() {
        sendPacketBA(buildPacketP(byteArrayOf(0x0A, 0x02, 0x06)))
        mState = State.WAITING_HIGHEST_ID
    }

    private fun getOldestRecordIndexP() {
        sendPacketBA(buildPacketP(byteArrayOf(0x27, 0x00)))
        mState = State.WAITING_OLDEST_INDEX
    }

    private fun setTimeP() {
        val currTime: Long =
            ((System.currentTimeMillis() / 1000).toInt() - Constants.DEVICE_TIME_OFFSET).toLong()
        val array = byteArrayOf(
            0x20,
            0x01,
            (currTime and 0x000000FFL).toByte(),
            (currTime and 0x0000FF00L shr 8).toByte(),
            (currTime and 0x00FF0000L shr 16).toByte(),
            (currTime and 0xFF000000L shr 24).toByte()
        )
        sendPacketBA(buildPacketP(array))
        mState = State.WAITING_TIME
    }

    private fun getMeasurementsByIdP(id: Int) {
        val array =
            byteArrayOf(0xB3.toByte(), (id and 0x00FF).toByte(), (id and 0xFF00 shr 8).toByte())
        sendPacketBA(buildPacketP(array))
        mState = State.WAITING_MEASUREMENT
    }

    private fun buildPacketP(payload: ByteArray): ByteArray {
        val payloadSize = payload.size
        val packetLength: Int = Constants.PROTOCOL_SENDING_OVERHEAD + payloadSize
        log("N - $payloadSize")
        log("PROTOCOL_SENDING_OVERHEAD - ${Constants.PROTOCOL_SENDING_OVERHEAD}")
        log("packetLength - $packetLength")
        val packet = ByteArray(packetLength)
        packet[0] = 0x02.toByte()
        packet[1] = packetLength.toByte()
        packet[2] = 0x00.toByte()
        packet[3] = 0x04.toByte()
        System.arraycopy(payload, 0, packet, 4, payloadSize)
        packet[4 + payloadSize] = 0x03.toByte()
        val length = packetLength - 2
        val crc: Int = computeCRCP(packet, 0, length)
        packet[length] = (crc and 0x00FF).toByte()
        packet[length + 1] = (crc and 0xFF00 shr 8).toByte()
        return packet
    }

    private fun computeCRCP(data: ByteArray?, offset: Int, length: Int): Int {
        if ((data == null) || (offset < 0) || (offset > data.size - 1) || (offset + length > data.size)) return 0
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[offset + i].toInt() shl 8)
            for (j in 0..7) {
                crc = if (crc and 0x8000 > 0) crc shl 1 xor 0x1021 else crc shl 1
            }
        }
        return crc and 0xFFFF
    }

    fun connectP() {
        if (mState == State.IDLE) {
            timer = Timer()
            getTimeP()
        }
    }

    fun disconnectP() {
        // Cancel any pending schedules
        timer.cancel()
        // Set state to disconnected
        mState = State.IDLE
    }

    fun getTimeP() {
        sendPacketBA(buildPacketP(byteArrayOf(0x20, 0x02)))
        mState = State.WAITING_TIME
    }


    //BA
    private fun sendPacketBA(aBytes: ByteArray) {
        if (BuildConfig.DEBUG && mStateBA != StateBA.IDLE) {
            throw AssertionError("Was busy to send packet!")
        }

        mStateBA = StateBA.SENDING
        mNpackets = ceil(aBytes.size / mMaxPayloadSize.toDouble()).toInt()
        mTxData = ByteArrayInputStream(aBytes)
        val nBytesToSend = mMaxPayloadSize.coerceAtMost(
            Constants.BLEUART_HEADER_SIZE + (mTxData?.available() ?: 0)
        )
        val bytesToSend = ByteArray(nBytesToSend)

        bytesToSend[0] = (0x0F and mNpackets).toByte()
        bytesToSend[0] =
            (bytesToSend[0].toInt() or 0x00.toByte().toInt()).toByte()

        mTxData?.read(
            bytesToSend,
            Constants.BLEUART_HEADER_SIZE,
            nBytesToSend - Constants.BLEUART_HEADER_SIZE
        )
        sendData(bytesToSend)
    }

    fun onDataReceivedBA(aBytes: ByteArray) {
        when (mStateBA) {
            StateBA.IDLE -> if (headerIsBA(aBytes[0], Constants.HEADER_FIRST_PACKET)) {
                mNpackets = aBytes[0].toInt() and 0x0F
                log("Receiving 1 of $mNpackets")
                mRxData = ByteArrayOutputStream()
                handleDataReceivedBA(aBytes)
            }
            StateBA.SENDING ->
                if (aBytes.size == 1 && headerIsBA(aBytes[0], Constants.HEADER_ACK_PACKET)) {
                    // Acknowledge packet
                    val nAck = aBytes[0].toInt() and 0x0F
                    if (nAck == mNpackets) {
                        mNpackets--
                        if (mNpackets == 0) {
                            mTxData = null
                            mStateBA = StateBA.IDLE
                            log("SENDING -> IDLE.")
                        } else {
                            val nBytesToSend =
                                mMaxPayloadSize.coerceAtMost(Constants.BLEUART_HEADER_SIZE + mTxData!!.available())
                            val bytesToSend = ByteArray(nBytesToSend)
                            bytesToSend[0] = (0x40 or (0x0F and mNpackets)).toByte()
                            mTxData!!.read(
                                bytesToSend,
                                Constants.BLEUART_HEADER_SIZE,
                                nBytesToSend - Constants.BLEUART_HEADER_SIZE
                            )
                            sendData(bytesToSend)
                        }
                    } else log("Wrong ACK number!. Expecting $mNpackets but $nAck received.")
                } else log("Expecting ACK but received: $aBytes")

            StateBA.RECEIVING -> if (headerIsBA(aBytes[0], Constants.HEADER_FRAG_PACKET)) {
                val remainingPackets = aBytes[0].toInt() and 0x0F
                if (remainingPackets == mNpackets) handleDataReceivedBA(aBytes)
                else log("Wrong packet number!. Expecting $mNpackets but $remainingPackets received.")
            } else log("Wrong header code!. Expecting " + 0x40 + " but " + (aBytes[0].toInt() and 0xF0) + " received.")
        }
    }

    private fun handleDataReceivedBA(aBytes: ByteArray) {
        mRxData!!.write(aBytes, 1, aBytes.size - 1)
        val bytesToSend = ByteArray(1)
        bytesToSend[0] = (0x80 or (0x0F and mNpackets)).toByte()
        sendData(bytesToSend)
        mNpackets--
        if (mNpackets > 0) {
            log("$mNpackets remaining.")
            mStateBA = StateBA.RECEIVING
        } else {
            log("${mRxData!!.size()} bytes received")
            mTxData = null
            mStateBA = StateBA.IDLE
            onPacketReceivedP(mRxData!!.toByteArray())
        }
    }

    private fun headerIsBA(aHeader: Byte, aHeaderType: Byte) =
        aHeader.toInt() and 0xF0.toByte().toInt() == aHeaderType.toInt()


    private fun intFromByteArray(bytes: ByteArray?) =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortFromByteArray(bytes: ByteArray?) =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short



    private var mRxCharacteristic: BluetoothGattCharacteristic? = null
    private var mTxCharacteristic: BluetoothGattCharacteristic? = null


    override fun getGattCallback(): BleManagerGattCallback {
        return object : BleManagerGattCallback() {
            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                val service = gatt.getService(Constants.ONETOUCH_SERVICE_UUID)
                service?.let { s ->
                    mRxCharacteristic =
                        s.getCharacteristic(Constants.ONETOUCH_RX_CHARACTERISTIC_UUID)
                    mTxCharacteristic =
                        s.getCharacteristic(Constants.ONETOUCH_TX_CHARACTERISTIC_UUID)
                }
                var writeRequest = false
                var writeCommand = false
                mRxCharacteristic?.also { rx ->
                    val rxProperties = rx.properties
                    writeRequest = rxProperties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0
                    writeCommand =
                        rxProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                return (mRxCharacteristic != null) && (mTxCharacteristic != null) && (writeCommand || writeRequest)
            }

            override fun onDeviceDisconnected() {
                disconnectP()
                mRxCharacteristic = null
                mTxCharacteristic = null
            }

            override fun onDeviceReady() {
                super.onDeviceReady()
                connectP()
            }

            override fun initialize() {
                super.initialize()
                if (isConnected) {
                    requestMtu(20 + 3)
                        .with { device: BluetoothDevice?, mtu: Int ->
                            log("MTU set to $mtu")
                        }
                        .fail { device: BluetoothDevice?, status: Int ->
                            log("MTU change failed.")
                        }
                        .enqueue()

                    /* Register callback to get data from the device. */
                    setNotificationCallback(mTxCharacteristic)
                        .with { device: BluetoothDevice?, data: Data ->
                            log("BLE data received: $data")
                            onDataReceivedBA(data.value!!)
                        }
                    enableNotifications(mTxCharacteristic)
                        .done { device: BluetoothDevice? ->
                            log("Onetouch TX characteristic  notifications enabled")
                            getTimeP()
                        }
                        .fail { device: BluetoothDevice?, status: Int ->
                            log("Onetouch TX characteristic  notifications not enabled")
                        }
                        .enqueue()
                }
            }

        }
    }

    fun onProtocolError(message: String) {
        mCallbacks.onProtocolError(message)
    }

    fun sendData(bytes: ByteArray?) {
        // Are we connected?
        if (mRxCharacteristic == null) {
            log("Tried to send data but mRxCharacteristic was null: " + bytes.toString())
            return
        }

        if (bytes != null && bytes.isNotEmpty()) {
            writeCharacteristic(mRxCharacteristic, bytes)
                .with { device: BluetoothDevice?, data: Data ->
                    log("$data sent")
                } //.split()
                .enqueue()
        }
    }

    fun onMeasurementsReceived(measurements: List<OneTouchMeasurement>) {
        mCallbacks.onMeasurementsReceived(measurements)
    }
}