package com.vayunmathur.euicc.telephony

import android.telephony.IccOpenLogicalChannelResponse
import android.telephony.TelephonyManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTelephonyManager

/**
 * Robolectric shadow that stands in for the privileged telephony stack:
 * reports a fake eUICC (via [ShadowTelephonyManager.setUiccCardsInfo] on the
 * instance) and answers the `@SystemApi` ByPort APDU calls the channel
 * manager reaches reflectively. Responses are scripted per test through the
 * companion so the shadow instance Robolectric creates sees them.
 */
@Implements(TelephonyManager::class)
class FakeEuiccTelephonyShadow : ShadowTelephonyManager() {

    @Implementation
    fun iccOpenLogicalChannelByPort(
        slotIndex: Int, portIndex: Int, aid: String, p2: Int,
    ): IccOpenLogicalChannelResponse? {
        openCalls += OpenCall(slotIndex, portIndex, aid, p2)
        return openResponse
    }

    @Implementation
    fun iccTransmitApduLogicalChannelByPort(
        slotIndex: Int, portIndex: Int, channel: Int, cla: Int, instruction: Int,
        p1: Int, p2: Int, p3: Int, data: String,
    ): String {
        val call = TransmitCall(slotIndex, portIndex, channel, cla, instruction, p1, p2, p3, data)
        transmitCalls += call
        transmitHandler?.let { return it(call) }
        return if (responses.isEmpty()) defaultResponse else responses.removeFirst()
    }

    @Implementation
    fun iccCloseLogicalChannelByPort(slotIndex: Int, portIndex: Int, channel: Int) {
        closeCalls += Triple(slotIndex, portIndex, channel)
    }

    data class OpenCall(val slotIndex: Int, val portIndex: Int, val aid: String, val p2: Int)
    data class TransmitCall(
        val slotIndex: Int, val portIndex: Int, val channel: Int, val cla: Int,
        val instruction: Int, val p1: Int, val p2: Int, val p3: Int, val data: String,
    )

    companion object {
        // IccOpenLogicalChannelResponse.STATUS_NO_ERROR in the framework.
        const val STATUS_NO_ERROR: Int = 1
        val openCalls = mutableListOf<OpenCall>()
        val transmitCalls = mutableListOf<TransmitCall>()
        val closeCalls = mutableListOf<Triple<Int, Int, Int>>()
        val responses = ArrayDeque<String>()
        var defaultResponse: String = "9000"
        var transmitHandler: ((TransmitCall) -> String)? = null
        var openResponse: IccOpenLogicalChannelResponse? = openResult(channel = 1, status = STATUS_NO_ERROR)

        fun reset() {
            openCalls.clear(); transmitCalls.clear(); closeCalls.clear(); responses.clear()
            defaultResponse = "9000"
            transmitHandler = null
            openResponse = openResult(channel = 1, status = STATUS_NO_ERROR)
        }

        fun openResult(channel: Int, status: Int): IccOpenLogicalChannelResponse =
            IccOpenLogicalChannelResponse::class.java
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, ByteArray::class.java)
                .newInstance(channel, status, null)
    }
}
