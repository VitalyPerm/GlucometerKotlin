package com.example.glucometerkotlin

import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.interfaces.ProtocolCallBacks
import com.example.glucometerkotlin.ui.log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.ceil

private enum class StateBA {
    IDLE, SENDING, RECEIVING
}

class Protocol(callBacks: ProtocolCallBacks, aMaxPacketSize: Int) {

    private var mState: State = State.IDLE
    private val protocolCallbacks: ProtocolCallBacks = callBacks
    private var timer: Timer = Timer()
    private var mSynced = false
    private var mHighestMeasID: Short = 0
    private var mHighestStoredMeasID: Short = 0

    // BA
    private var mStateBA: StateBA = StateBA.IDLE
    private var mMaxPayloadSize: Int = 0
    private var mNpackets = 0
    private var mTxData: ByteArrayInputStream? = null
    private var mRxData: ByteArrayOutputStream? = null

    init {
        mMaxPayloadSize = aMaxPacketSize - Constants.BLEUART_HEADER_SIZE
    }

    private val mMeasurements = mutableListOf<OneTouchMeasurement>()


    private val hexArray = "0123456789ABCDEF".toCharArray()

    private fun intFromByteArray(bytes: ByteArray?): Int {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun shortFromByteArray(bytes: ByteArray?): Short {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short
    }


    fun onPacketReceived(aBytes: ByteArray?) {
        kotlin.runCatching {
            val bytes = aBytes ?: return
            val payload: ByteArray = extractPayload(bytes) ?: return
            when (mState) {
                State.WAITING_TIME -> if (payload.size == 4) { // Time get response
                    handleTimeGet(
                        computeUnixTime(payload).toLong()
                    )
                } else if (payload.isEmpty()) { // Time set response (empty)
                    handleTimeSet()
                } else {
                    log("Unexpected payload waiting for time request!")
                }
                State.WAITING_HIGHEST_ID -> if (payload.size == 4) {
                    val highestID: Int = intFromByteArray(payload)
                    handleHighestRecordID(highestID.toShort())
                } else {
                    log("Unexpected payload waiting for highest record ID!")
                }
                State.WAITING_OLDEST_INDEX -> if (payload.size == 2) {
                    val recordCount: Short = shortFromByteArray(payload)
                    handleTotalRecordCount(recordCount)
                } else {
                    log("Unexpected payload waiting for total record request!")
                }
                State.WAITING_MEASUREMENT -> if (payload.size == 11) {
                    val measTime: Int = computeUnixTime(payload.copyOfRange(0, 0 + 4))
                    val measValue: Short = shortFromByteArray(payload.copyOfRange(4, 4 + 2))
                    val measError: Short = shortFromByteArray(payload.copyOfRange(9, 9 + 2))
                    handleMeasurementByID(measTime, measValue, measError)
                } else if (payload.isEmpty()) {
                    // Measurement was not found! Indicate with aMeasTime=0
                    handleMeasurementByID(0, 0.toShort(), 0.toShort())
                } else if (payload.size == 16) {
                    val measIndex: Short = shortFromByteArray(payload.copyOfRange(0, 0 + 2))
                    val measID: Short = shortFromByteArray(payload.copyOfRange(3, 3 + 2))
                    val measTime: Int = computeUnixTime(payload.copyOfRange(5, 5 + 4))
                    val measValue: Short = shortFromByteArray(payload.copyOfRange(9, 9 + 2))
                    val measUnknownValue: Short =
                        shortFromByteArray(payload.copyOfRange(13, 13 + 2))
                    handleMeasurementByIndex(
                        measIndex,
                        measID,
                        measTime,
                        measValue,
                        measUnknownValue
                    )
                }
                else -> {}
            }
        }
    }

    fun sendData(aBytes: ByteArray?) {
        protocolCallbacks.sendData(aBytes)
    }


    private fun bytesToHex(bytes: ByteArray): String? {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray.get(v ushr 4)
            hexChars[j * 2 + 1] = hexArray.get(v and 0x0F)
        }
        return String(hexChars)
    }

    @Throws(Exception::class)
    private fun extractPayload(packet: ByteArray): ByteArray? {
        if (checkCRC16(packet)) {
            return if (packet.size == extractLength(packet) && packet.size >= Constants.PROTOCOL_OVERHEAD) {
                packet.copyOfRange(
                    Constants.PACKET_PAYLOAD_BEGIN,
                    Constants.PACKET_PAYLOAD_BEGIN + packet.size - Constants.PROTOCOL_OVERHEAD
                )
            } else {
                val length = extractLength(packet)
                val msg = "Bad Length! Received ${packet.size} bytes but should have been $length"
                throw Exception(msg)
            }
        } else {
            val computedCRC: Int = computeCRC(packet, 0, packet.size - 2)
            val receivedCRC: Int = extractCRC(packet)
            throw Exception(
                "Bad CRC! Expected " + Integer.toHexString(computedCRC) +
                        " but got " + Integer.toHexString(receivedCRC) + "."
            )
        }
    }

    private fun extractLength(data: ByteArray): Int {
        return (data[2].toInt() shl 8 and 0xFF00 or (data[1].toInt() and 0x00FF))
    }

    private fun handleMeasurementByIndex(
        aMeasIndex: Short,
        aMeasID: Short,
        aMeasTime: Int,
        aMeasValue: Short,
        aMeasUnknownValue: Short
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
            protocolCallbacks.onMeasurementsReceived(mMeasurements)
            mMeasurements.clear()
            mSynced = true
            getHighestRecordID()
            // Start timer to poll for new measurements??
        } else {
            log("Requesting next measurement: " + (aMeasIndex - 1))
            getMeasurementsByIndex(aMeasIndex - 1)
        }
    }

    private fun checkCRC16(data: ByteArray): Boolean {
        val computedCRC: Int = computeCRC(data, 0, data.size - 2)
        val receivedCRC: Int = extractCRC(data)
        return receivedCRC == computedCRC
    }

    private fun computeUnixTime(sysTime: ByteArray): Int {
        return Constants.DEVICE_TIME_OFFSET + intFromByteArray(sysTime)
    }

    private fun handleMeasurementByID(aMeasTime: Int, aMeasValue: Short, aMeasError: Short) {
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
        } else {
            log("Measurement with ID: $mHighestStoredMeasID was not found!")
        }
        if (mHighestStoredMeasID < mHighestMeasID) {
            log("Requesting next measurement, ID: " + (mHighestStoredMeasID + 1))
            getMeasurementsById(mHighestStoredMeasID + 1)
        } else {
            log("Measurement up to date!")
            // Notify application
            protocolCallbacks.onMeasurementsReceived(mMeasurements)
            mMeasurements.clear()
            // Start timer to poll for new measurements??
        }
    }

    private fun handleTotalRecordCount(aRecordCount: Short) {
        log("Total records stored on Glucometer: $aRecordCount")
        // After getting the number of stored measurements, start from the oldest one!
        getMeasurementsByIndex(aRecordCount - 1)
    }

    private fun getMeasurementsByIndex(index: Int) {
        val array = byteArrayOf(
            0x31, 0x02, (index and 0x00FF).toByte(), (index and 0xFF00 shr 8).toByte(),
            0x00
        )
        sendPacketBA(buildPacket(array))
        mState = State.WAITING_MEASUREMENT
    }

    private fun handleTimeGet(aSeconds: Long) {
        log("Glucometer time is: " + Date(1000 * aSeconds).toString())
        log("System time is: " + Date(System.currentTimeMillis()).toString())
        setTime()
    }

    private fun handleTimeSet() {
        log("Time has been set!")
        if (!mSynced) {
            getOldestRecordIndex()
        } else {
            getHighestRecordID()
        }
    }

    private fun getHighestRecordID() {
        sendPacketBA(buildPacket(byteArrayOf(0x0A, 0x02, 0x06)))
        mState = State.WAITING_HIGHEST_ID
    }

    private fun getOldestRecordIndex() {
        sendPacketBA(buildPacket(byteArrayOf(0x27, 0x00)))
        mState = State.WAITING_OLDEST_INDEX
    }

    private fun setTime() {
        val currTime: Long = computeSystemTime().toLong()
        sendPacketBA(
            buildPacket(
                byteArrayOf(
                    0x20,
                    0x01,
                    (currTime and 0x000000FFL).toByte(),
                    (currTime and 0x0000FF00L shr 8).toByte(),
                    (currTime and 0x00FF0000L shr 16).toByte(),
                    (currTime and 0xFF000000L shr 24).toByte()
                )
            )
        )
        mState = State.WAITING_TIME
    }

    private fun handleHighestRecordID(aRecordID: Short) {
        log("Highest record ID: $aRecordID")
        if (aRecordID > mHighestMeasID) {
            mHighestStoredMeasID = mHighestMeasID
            mHighestMeasID = aRecordID
            log("There are " + (mHighestMeasID - mHighestStoredMeasID) + " new records!")
            getMeasurementsById(mHighestStoredMeasID + 1)
        } else {
            log("Measurements are up to date!")
        }
    }

    private fun getMeasurementsById(id: Int) {
        val array =
            byteArrayOf(0xB3.toByte(), (id and 0x00FF).toByte(), (id and 0xFF00 shr 8).toByte())
        sendPacketBA(buildPacket(array))
        mState = State.WAITING_MEASUREMENT
    }
    private fun buildPacket(payload: ByteArray): ByteArray {
        val N = payload.size
        val packetLength: Int = Constants.PROTOCOL_SENDING_OVERHEAD + N
        log("N - $N")
        log("PROTOCOL_SENDING_OVERHEAD - ${Constants.PROTOCOL_SENDING_OVERHEAD}")
        log("packetLength - $packetLength")
        val packet = ByteArray(packetLength)
        packet[0] = 0x02.toByte()
        packet[1] = packetLength.toByte()
        packet[2] = 0x00.toByte()
        packet[3] = 0x04.toByte()
        System.arraycopy(payload, 0, packet, 4, N)
        packet[4 + N] = 0x03.toByte()
        appendCRC16(packet, packetLength - 2)
        return packet
    }

    private fun appendCRC16(data: ByteArray, length: Int) {
        val crc: Int = computeCRC(data, 0, length)
        data[length] = (crc and 0x00FF).toByte()
        data[length + 1] = (crc and 0xFF00 shr 8).toByte()
    }

    private fun computeSystemTime(): Int {
        return (System.currentTimeMillis() / 1000).toInt() - Constants.DEVICE_TIME_OFFSET
    }

    private fun extractCRC(data: ByteArray): Int {
        return (data[data.size - 1].toInt() shl 8 and 0xFF00 or (data[data.size - 2].toInt() and 0x00FF))
    }

    private fun computeCRC(data: ByteArray?, offset: Int, length: Int): Int {
        if ((data == null) || (offset < 0) || (offset > data.size - 1) || (offset + length > data.size)) {
            return 0
        }
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[offset + i].toInt() shl 8)
            for (j in 0..7) {
                crc = if (crc and 0x8000 > 0) crc shl 1 xor 0x1021 else crc shl 1
            }
        }
        return crc and 0xFFFF
    }

    fun connect() {
        if (mState == State.IDLE) {
            timer = Timer()
            getTime()
        }
    }

    fun disconnect() {
        // Cancel any pending schedules
        timer.cancel()
        // Set state to disconnected
        mState = State.IDLE
    }

    fun onDataReceived(bytes: ByteArray?) {
        onDataReceivedBA(bytes!!)
    }

    fun getTime() {
        sendPacketBA(buildPacket(byteArrayOf(0x20, 0x02)))
        mState = State.WAITING_TIME
    }


    //BA
    fun sendPacketBA(aBytes: ByteArray) {
        if (BuildConfig.DEBUG && mStateBA != StateBA.IDLE) {
            throw AssertionError("Was busy to send packet!")
        }

        mStateBA = StateBA.SENDING
        mNpackets = ceil(aBytes.size / mMaxPayloadSize.toDouble()).toInt()
        mTxData = ByteArrayInputStream(aBytes)
        buildAndSendFragmentBA(true)
    }

    private fun buildAndSendFragmentBA(aFirstPacket: Boolean) {
        val nBytesToSend = mMaxPayloadSize.coerceAtMost(
            Constants.BLEUART_HEADER_SIZE + (mTxData?.available() ?: 0)
        )
        val bytesToSend = ByteArray(nBytesToSend)

        bytesToSend[0] = (0x0F and mNpackets).toByte()
        //      java.lang.ClassCastException: java.lang.Byte cannot be cast to java.lang.Integer
        val someValue = if (aFirstPacket) 0x00.toByte() else 0x40.toByte()
            .toInt()
        bytesToSend[0] =
            (bytesToSend[0].toInt() or someValue.toInt()).toByte()

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
                    } else {
                        log("Wrong ACK number!. Expecting $mNpackets but $nAck received.")
                    }
                } else {
                    log("Expecting ACK but received: $aBytes")
                }
            StateBA.RECEIVING -> if (headerIsBA(aBytes[0], Constants.HEADER_FRAG_PACKET)) {
                val remainingPackets = aBytes[0].toInt() and 0x0F
                if (remainingPackets == mNpackets) {
                    handleDataReceivedBA(aBytes)
                } else {
                    log("Wrong packet number!. Expecting $mNpackets but $remainingPackets received.")
                }
            } else {
                log("Wrong header code!. Expecting " + 0x40 + " but " + (aBytes[0].toInt() and 0xF0) + " received.")
            }
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
            onPacketReceived(mRxData!!.toByteArray())
        }
    }

    private fun headerIsBA(aHeader: Byte, aHeaderType: Byte): Boolean {
        return aHeader.toInt() and 0xF0.toByte().toInt() == aHeaderType.toInt()
    }


}

enum class State {
    IDLE, WAITING_TIME, WAITING_HIGHEST_ID, WAITING_OLDEST_INDEX, WAITING_MEASUREMENT
}