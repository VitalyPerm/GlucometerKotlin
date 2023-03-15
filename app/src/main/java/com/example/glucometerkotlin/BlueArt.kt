package com.example.glucometerkotlin

import android.util.Log
import com.example.glucometerkotlin.interfaces.BlueArtCallbacks
import com.example.glucometerkotlin.ui.log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

class BlueArt(callbacks: BlueArtCallbacks, aMaxPacketSize: Int) {

    private val mCallBacks: BlueArtCallbacks = callbacks
    private var mMaxPayloadSize: Int = 0
    private var mState: State = State.IDLE
    private var mNpackets = 0
    private var mTxData: ByteArrayInputStream? = null
    private var mRxData: ByteArrayOutputStream? = null

    init {
        mMaxPayloadSize = aMaxPacketSize - Constants.BLEUART_HEADER_SIZE
    }

    fun sendPacket(aBytes: ByteArray) {
        if (BuildConfig.DEBUG && mState != State.IDLE) {
            throw AssertionError("Was busy to send packet!")
        }

        mState = State.SENDING
        mNpackets = ceil(aBytes.size / mMaxPayloadSize.toDouble()).toInt()
        mTxData = ByteArrayInputStream(aBytes)
        buildAndSendFragment(true)
    }

    private fun buildAndSendFragment(aFirstPacket: Boolean) {
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
        mCallBacks.sendData(bytesToSend)
    }

    fun onDataReceived(aBytes: ByteArray) {
        when (mState) {
            State.IDLE -> if (headerIs(aBytes[0], Constants.HEADER_FIRST_PACKET)) {
                mNpackets = aBytes[0].toInt() and 0x0F
                log("Receiving 1 of $mNpackets")
                mRxData = ByteArrayOutputStream()
                handleDataReceived(aBytes)
            }
            State.SENDING ->
                if (aBytes.size == 1 && headerIs(aBytes[0], Constants.HEADER_ACK_PACKET)) {
                    // Acknowledge packet
                    val nAck = aBytes[0].toInt() and 0x0F
                    if (nAck == mNpackets) {
                        mNpackets--
                        if (mNpackets == 0) {
                            mTxData = null
                            mState = State.IDLE
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
                            mCallBacks.sendData(bytesToSend)
                        }
                    } else {
                        log("Wrong ACK number!. Expecting $mNpackets but $nAck received.")
                    }
                } else {
                    log("Expecting ACK but received: $aBytes")
                }
            State.RECEIVING -> if (headerIs(aBytes[0], Constants.HEADER_FRAG_PACKET)) {
                val remainingPackets = aBytes[0].toInt() and 0x0F
                if (remainingPackets == mNpackets) {
                    handleDataReceived(aBytes)
                } else {
                    log("Wrong packet number!. Expecting $mNpackets but $remainingPackets received.")
                }
            } else {
                log("Wrong header code!. Expecting " + 0x40 + " but " + (aBytes[0].toInt() and 0xF0) + " received.")
            }
        }
    }

    private fun handleDataReceived(aBytes: ByteArray) {
        mRxData!!.write(aBytes, 1, aBytes.size - 1)
        val bytesToSend = ByteArray(1)
        bytesToSend[0] = (0x80 or (0x0F and mNpackets)).toByte()
        mCallBacks.sendData(bytesToSend)
        mNpackets--
        if (mNpackets > 0) {
            log("$mNpackets remaining.")
            mState = State.RECEIVING
        } else {
            log("${mRxData!!.size()} bytes received")
            mTxData = null
            mState = State.IDLE
            mCallBacks.onPacketReceived(mRxData!!.toByteArray())
        }
    }

    private fun headerIs(aHeader: Byte, aHeaderType: Byte): Boolean {
        return aHeader.toInt() and 0xF0.toByte().toInt() == aHeaderType.toInt()
    }


    private enum class State {
        IDLE, SENDING, RECEIVING
    }
}