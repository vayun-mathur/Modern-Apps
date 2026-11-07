package com.vayunmathur.euicc.telephony

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.euicc.EuiccNative
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.UiccCardInfoBuilder
import org.robolectric.shadows.UiccPortInfoBuilder
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [FakeEuiccTelephonyShadow::class])
class EuiccChannelManagerFakeTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        FakeEuiccTelephonyShadow.reset()
        EuiccNative.activeChannel = null
        installCards(euiccCard(slot = 0, ports = listOf(port(0))))
    }

    private fun port(index: Int) =
        UiccPortInfoBuilder.newBuilder().setPortIndex(index).setLogicalSlotIndex(0).setIsActive(true).build()

    private fun euiccCard(slot: Int, ports: List<android.telephony.UiccPortInfo>) =
        UiccCardInfoBuilder.newBuilder()
            .setIsEuicc(true).setCardId(1).setEid("89044000001234567890123456789012")
            .setSlotIndex(slot).setPhysicalSlotIndex(slot).setPorts(ports).build()

    private fun installCards(vararg cards: android.telephony.UiccCardInfo) {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        Shadows.shadowOf(tm).setUiccCardsInfo(cards.toList())
    }

    private fun manager() = EuiccChannelManager(ctx)

    @Test fun `opens ISDR on euicc slot and port closes after block`() {
        installCards(
            UiccCardInfoBuilder.newBuilder().setIsEuicc(false).setCardId(0).setSlotIndex(1)
                .setPhysicalSlotIndex(1).setPorts(listOf(port(0))).build(),
            euiccCard(slot = 2, ports = listOf(port(3))),
        )
        val result = manager().withIsdrChannel {
            assertTrue(EuiccNative.activeChannel != null)
            "done"
        }
        assertEquals("done", result)
        assertEquals(1, FakeEuiccTelephonyShadow.openCalls.size)
        val open = FakeEuiccTelephonyShadow.openCalls.single()
        assertEquals(2, open.slotIndex)
        assertEquals(3, open.portIndex)
        assertEquals(EuiccChannelManager.ISDR_AID, open.aid)
        assertEquals(listOf(Triple(2, 3, 1)), FakeEuiccTelephonyShadow.closeCalls)
        assertNull(EuiccNative.activeChannel)
    }

    @Test fun `card without ports falls back to port zero`() {
        installCards(euiccCard(slot = 1, ports = emptyList()))
        manager().withIsdrChannel { }
        assertEquals(0, FakeEuiccTelephonyShadow.openCalls.single().portIndex)
        assertEquals(1, FakeEuiccTelephonyShadow.openCalls.single().slotIndex)
    }

    @Test fun `negative slot index is rejected`() {
        installCards(
            UiccCardInfoBuilder.newBuilder().setIsEuicc(true).setCardId(1)
                .setSlotIndex(-1).setPhysicalSlotIndex(-1).setPorts(emptyList()).build(),
        )
        val ex = assertFailsWith<EuiccException> { manager().withIsdrChannel { } }
        assertTrue(ex.message!!.contains("physical slot"), ex.message!!)
    }

    @Test fun `open error status and missing response and zero channel are rejected`() {
        FakeEuiccTelephonyShadow.openResponse = FakeEuiccTelephonyShadow.openResult(channel = 1, status = 4)
        assertFailsWith<EuiccException> { manager().withIsdrChannel { } }
        FakeEuiccTelephonyShadow.openResponse = null
        val ex = assertFailsWith<EuiccException> { manager().withIsdrChannel { } }
        assertTrue(ex.message!!.contains("no response"), ex.message!!)
        FakeEuiccTelephonyShadow.openResponse = FakeEuiccTelephonyShadow.openResult(channel = 0, status = FakeEuiccTelephonyShadow.STATUS_NO_ERROR)
        val ex2 = assertFailsWith<EuiccException> { manager().withIsdrChannel { } }
        assertTrue(ex2.message!!.contains("invalid ISD-R channel"), ex2.message!!)
    }

    @Test fun `block failure still closes channel and clears active channel`() {
        val ex = assertFailsWith<IllegalStateException> {
            manager().withIsdrChannel { throw IllegalStateException("inside") }
        }
        assertEquals("inside", ex.message)
        assertEquals(1, FakeEuiccTelephonyShadow.closeCalls.size)
        assertNull(EuiccNative.activeChannel)
    }

    @Test fun `transmit case1 case2 and case3 apdus`() {
        manager().withIsdrChannel {
            // Case 1: header only.
            var out = EuiccNative.transmitApdu(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00))
            assertEquals(listOf(0x90.toByte(), 0x00.toByte()), out.toList())
            var call = FakeEuiccTelephonyShadow.transmitCalls.last()
            assertEquals(0, call.p3); assertEquals("", call.data)
            assertEquals(0xA4, call.instruction)

            // Case 2: header + Le.
            out = EuiccNative.transmitApdu(byteArrayOf(0x80.toByte(), 0xE2.toByte(), 0x91.toByte(), 0x00, 0x20))
            assertEquals(listOf(0x90.toByte(), 0x00.toByte()), out.toList())
            call = FakeEuiccTelephonyShadow.transmitCalls.last()
            assertEquals(0x20, call.p3); assertEquals("", call.data)

            // Case 3: header + Lc + data (trailing Le ignored).
            out = EuiccNative.transmitApdu(
                byteArrayOf(0x80.toByte(), 0xE2.toByte(), 0x91.toByte(), 0x00, 0x03, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x7F),
            )
            assertEquals(listOf(0x90.toByte(), 0x00.toByte()), out.toList())
            call = FakeEuiccTelephonyShadow.transmitCalls.last()
            assertEquals(3, call.p3); assertEquals("AABBCC", call.data)
            assertEquals(0x80, call.cla); assertEquals(0x91, call.p1)
        }
    }

    @Test fun `transmit follows 61xx get response chaining`() {
        FakeEuiccTelephonyShadow.responses.addAll(listOf("AB6102", "CD9000"))
        manager().withIsdrChannel {
            val out = EuiccNative.transmitApdu(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x10))
            assertEquals(
                listOf(0xAB.toByte(), 0xCD.toByte(), 0x90.toByte(), 0x00.toByte()), out.toList(),
            )
        }
        assertEquals(2, FakeEuiccTelephonyShadow.transmitCalls.size)
        val getResponse = FakeEuiccTelephonyShadow.transmitCalls[1]
        assertEquals(0xC0, getResponse.instruction)
        assertEquals(2, getResponse.p3)
    }

    @Test fun `transmit follows 6Cxx wrong Le resend`() {
        FakeEuiccTelephonyShadow.responses.addAll(listOf("6C08", "EF9000"))
        manager().withIsdrChannel {
            val out = EuiccNative.transmitApdu(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x04, 0x11, 0x22, 0x33, 0x44))
            assertEquals(listOf(0xEF.toByte(), 0x90.toByte(), 0x00.toByte()), out.toList())
        }
        assertEquals(2, FakeEuiccTelephonyShadow.transmitCalls.size)
        assertEquals(8, FakeEuiccTelephonyShadow.transmitCalls[1].p3)
        assertEquals("11223344", FakeEuiccTelephonyShadow.transmitCalls[1].data)
    }

    @Test fun `transmit short and odd responses decode to empty`() {
        FakeEuiccTelephonyShadow.responses.addAll(listOf("90", "12345"))
        manager().withIsdrChannel {
            assertEquals(0, EuiccNative.transmitApdu(byteArrayOf(0, 0xB0.toByte(), 0, 0)).size)
            assertEquals(0, EuiccNative.transmitApdu(byteArrayOf(0, 0xB0.toByte(), 0, 0)).size)
        }
    }

    @Test fun `transmit rejects too short apdu`() {
        manager().withIsdrChannel {
            assertFailsWith<IllegalArgumentException> {
                EuiccNative.transmitApdu(byteArrayOf(0x00, 0x01))
            }
        }
    }

    @Test fun `data response body is returned with status word`() {
        FakeEuiccTelephonyShadow.defaultResponse = "DEADBEEF9000"
        manager().withIsdrChannel {
            val out = EuiccNative.transmitApdu(byteArrayOf(0, 0xB0.toByte(), 0, 0))
            assertEquals(
                listOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x90.toByte(), 0x00.toByte()),
                out.toList(),
            )
        }
    }
}
