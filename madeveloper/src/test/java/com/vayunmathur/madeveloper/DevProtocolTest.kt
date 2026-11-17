package com.vayunmathur.madeveloper

import com.vayunmathur.madeveloper.domain.DevMessage
import com.vayunmathur.madeveloper.domain.DevProtocol
import com.vayunmathur.madeveloper.domain.InstallPhase
import com.vayunmathur.madeveloper.domain.InstalledAppInfo
import com.vayunmathur.madeveloper.domain.PendingInstall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests for the push protocol, backoff, hashes and install state machine. */
class DevProtocolTest {
    @Test fun parsesPushMessages() {
        assertTrue(DevProtocol.parse("""{"type":"ping"}""") is DevMessage.Ping)
        assertTrue(DevProtocol.parse("""{"type":"listApps"}""") is DevMessage.ListApps)
        val install = DevProtocol.parse("""{"type":"install","package":"com.vayunmathur.notes","versionCode":3,"apkUrl":"https://x/a.apk","sha256":"ab","sizeBytes":10}""")
        assertTrue(install is DevMessage.Install)
        assertEquals(3L, (install as DevMessage.Install).versionCode)
        assertTrue(DevProtocol.parse("not json") is DevMessage.Unknown)
    }

    @Test fun backoff() {
        assertEquals(1000L, DevProtocol.backoffMs(0))
        assertEquals(2000L, DevProtocol.backoffMs(1))
        assertEquals(60000L, DevProtocol.backoffMs(20))
    }

    @Test fun registerContractRoundTrip() {
        assertEquals("""{"deviceName":"Pixel 9"}""", DevProtocol.registerRequestBody("Pixel 9"))
        assertEquals("""{"deviceName":"a\"b"}""", DevProtocol.registerRequestBody("a\"b"))
        val registration = DevProtocol.parseRegistration(
            """{"deviceId":"dev-1","pushToken":"tok-1","pairingCode":"123456"}""",
        )
        assertEquals("dev-1", registration?.deviceId)
        assertEquals("tok-1", registration?.pushToken)
        assertEquals("123456", registration?.pairingCode)
        assertEquals(null, DevProtocol.parseRegistration("""{"deviceId":"dev-1"}"""))
        assertEquals(null, DevProtocol.parseRegistration("not json"))
        // A stale server "paired" frame is tolerated as unknown, never acted on.
        assertTrue(DevProtocol.parse("""{"type":"paired","token":"t","deviceId":"d"}""") is DevMessage.Unknown)
    }

    @Test fun shaVerification() {
        val bytes = "hello".toByteArray()
        val hash = DevProtocol.sha256Hex(bytes)
        assertTrue(DevProtocol.verifySha256(bytes, hash.uppercase()))
        assertFalse(DevProtocol.verifySha256(bytes, "00"))
    }

    @Test fun installStateMachineRejectsIllegalJumps() {
        val pushed = PendingInstall("com.vayunmathur.notes", 1, "https://x/a.apk", "ab", 10)
        assertEquals(InstallPhase.PUSHED, DevProtocol.transition(pushed, InstallPhase.READY).phase)
        val downloading = DevProtocol.transition(pushed, InstallPhase.DOWNLOADING)
        assertEquals(InstallPhase.VERIFYING, DevProtocol.transition(downloading, InstallPhase.VERIFYING).phase)
    }

    @Test fun listAppsReplyShape() {
        val reply = DevProtocol.listAppsReply(listOf(InstalledAppInfo("com.vayunmathur.notes", 5)))
        assertEquals("""{"type":"installed","packages":[{"package":"com.vayunmathur.notes","versionCode":5}]}""", reply)
    }
}
