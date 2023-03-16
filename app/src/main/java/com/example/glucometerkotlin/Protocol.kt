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


class Protocol(private val protocolCallbacks: ProtocolCallBacks, aMaxPacketSize: Int) {

    private var mState: State = State.IDLE
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


    private fun onPacketReceived(aBytes: ByteArray?) {
        kotlin.runCatching {
            val bytes = aBytes ?: return
            val payload: ByteArray = extractPayload(bytes)
            when (mState) {
                State.WAITING_TIME -> if (payload.size == 4) { // Time get response
                    handleTimeGet(computeUnixTime(payload).toLong())
                } else if (payload.isEmpty()) { // Time set response (empty)
                    handleTimeSet()
                } else {
                    log("Unexpected payload waiting for time request!")
                }
                State.WAITING_HIGHEST_ID -> if (payload.size == 4) {
                    val highestID: Short = intFromByteArray(payload).toShort()
                    log("Highest record ID: $highestID")
                    if (highestID > mHighestMeasID) {
                        mHighestStoredMeasID = mHighestMeasID
                        mHighestMeasID = highestID
                        log("There are " + (mHighestMeasID - mHighestStoredMeasID) + " new records!")
                        getMeasurementsById(mHighestStoredMeasID + 1)
                    } else log("Measurements are up to date!")
                } else log("Unexpected payload waiting for highest record ID!")

                State.WAITING_OLDEST_INDEX -> if (payload.size == 2) {
                    val recordCount: Short = shortFromByteArray(payload)
                    log("Total records stored on Glucometer: $recordCount")
                    // After getting the number of stored measurements, start from the oldest one!
                    getMeasurementsByIndex(recordCount - 1)
                } else log("Unexpected payload waiting for total record request!")

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
                    handleMeasurementByIndex(
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
    private fun extractPayload(packet: ByteArray): ByteArray {
        val computedCRC: Int = computeCRC(packet, 0, packet.size - 2)
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

    private fun handleMeasurementByIndex(
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
        sendPacketBA(buildPacket(array))
        mState = State.WAITING_TIME
    }

    private fun getMeasurementsById(id: Int) {
        val array =
            byteArrayOf(0xB3.toByte(), (id and 0x00FF).toByte(), (id and 0xFF00 shr 8).toByte())
        sendPacketBA(buildPacket(array))
        mState = State.WAITING_MEASUREMENT
    }

    private fun buildPacket(payload: ByteArray): ByteArray {
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
        val crc: Int = computeCRC(packet, 0, length)
        packet[length] = (crc and 0x00FF).toByte()
        packet[length + 1] = (crc and 0xFF00 shr 8).toByte()
        return packet
    }

    private fun computeCRC(data: ByteArray?, offset: Int, length: Int): Int {
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

    fun getTime() {
        sendPacketBA(buildPacket(byteArrayOf(0x20, 0x02)))
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
        protocolCallbacks.sendData(bytesToSend)
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
                            protocolCallbacks.sendData(bytesToSend)
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
        protocolCallbacks.sendData(bytesToSend)
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

    private fun headerIsBA(aHeader: Byte, aHeaderType: Byte) =
        aHeader.toInt() and 0xF0.toByte().toInt() == aHeaderType.toInt()


    private fun intFromByteArray(bytes: ByteArray?) =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortFromByteArray(bytes: ByteArray?) =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short


}

private enum class StateBA {
    IDLE, SENDING, RECEIVING
}

enum class State {
    IDLE, WAITING_TIME, WAITING_HIGHEST_ID, WAITING_OLDEST_INDEX, WAITING_MEASUREMENT
}