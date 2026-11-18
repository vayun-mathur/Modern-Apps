package com.vayunmathur.findfamily.tracker

import com.vayunmathur.findfamily.domain.TrackerBle
import com.vayunmathur.findfamily.domain.UwbAccessoryProtocol
import com.vayunmathur.findfamily.platform.TrackerUwbGatt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Pure byte-layout tests for the tracker UWB session-params frame and accessory stub. */
class TrackerUwbGattEncodeTest {

    @Test fun sessionParamsLayoutIsBigEndian() {
        val frame = TrackerUwbGatt.encodeSessionParams(
            TrackerUwbGatt.SessionParams(
                localAddress = byteArrayOf(0xAB.toByte(), 0xCD.toByte()),
                sessionId = 0x01020304,
                channelNumber = 9,
                preambleIndex = 10,
            )
        )
        assertContentEquals(
            byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 1, 2, 3, 4, 9, 10),
            frame,
        )
    }

    @Test fun sessionParamsHandlesNegativeSessionId() {
        val frame = TrackerUwbGatt.encodeSessionParams(
            TrackerUwbGatt.SessionParams(byteArrayOf(0, 1), -1, 5, 6)
        )
        assertContentEquals(byteArrayOf(0, 1, -1, -1, -1, -1, 5, 6), frame)
    }

    @Test fun sessionParamsRejectsWrongAddressSize() {
        assertFailsWith<IllegalArgumentException> {
            TrackerUwbGatt.encodeSessionParams(
                TrackerUwbGatt.SessionParams(byteArrayOf(1), 1, 9, 10)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TrackerUwbGatt.encodeSessionParams(
                TrackerUwbGatt.SessionParams(byteArrayOf(1, 2, 3), 1, 9, 10)
            )
        }
    }

    @Test fun trackerBleConstantsMatchFirmwareContract() {
        assertEquals(48, TrackerBle.PROVISION_BLOB_LEN)
        assertEquals(40, TrackerBle.PROVISION_BLOB_LEN_NO_TIME)
        assertEquals(
            TrackerBle.PROVISION_BLOB_LEN - 8,
            TrackerBle.PROVISION_BLOB_LEN_NO_TIME,
        )
    }

    @Test fun accessoryConfigIsVersionPrefixPlusAddress() {
        val addr = byteArrayOf(9, 8)
        assertContentEquals(
            byteArrayOf(0x01, 0x00, 9, 8),
            UwbAccessoryProtocol.encodeAccessoryConfigurationData(addr),
        )
    }

    @Test fun shareableConfigParseIsStillStubbed() {
        // The Apple accessory byte format is not implemented yet; callers rely on
        // this throwing so the session fails closed instead of ranging on garbage.
        assertFailsWith<NotImplementedError> {
            UwbAccessoryProtocol.parseShareableConfigurationData(byteArrayOf(1, 2, 3))
        }
    }
}
