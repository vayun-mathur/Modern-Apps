package com.vayunmathur.passwords.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyUtilsExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun authenticatorDataSignCountAndFlagCombinations() {
        val maxed = PasskeyUtils.buildAuthenticatorData(
            "example.com",
            userPresent = true, userVerified = false,
            backupEligible = false, backupState = true,
            attestedCredentialData = true, signCount = -1,
        )
        // UP + BS + AT, sign count written big-endian even for -1 (all FF).
        assertEquals(0x01 or 0x10 or 0x40, maxed[32].toInt() and 0xFF)
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF), maxed.takeLast(4).map { it.toInt() and 0xFF })
        // Different rpIds hash differently in the first 32 bytes.
        val other = PasskeyUtils.buildAuthenticatorData("other.example")
        assertEquals(32, other.take(32).size)
        assertEquals(false, maxed.take(32) == other.take(32))
        // Zero-state: no flags, zero count.
        val bare = PasskeyUtils.buildAuthenticatorData(
            "example.com", userPresent = false, userVerified = false,
            backupEligible = false, backupState = false,
        )
        assertEquals(0, bare[32].toInt() and 0xFF)
        assertEquals(listOf(0, 0, 0, 0), bare.takeLast(4).map { it.toInt() and 0xFF })
    }

    @Test fun b64UrlEdgeCases() {
        assertEquals("", PasskeyUtils.encodeB64Url(ByteArray(0)))
        assertEquals(0, PasskeyUtils.decodeB64Url("").size)
        // Correct padding on input is tolerated even though output omits it:
        // four bytes encode to six unpadded chars, which take "==" to complete.
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = PasskeyUtils.encodeB64Url(data)
        assertEquals(data.toList(), PasskeyUtils.decodeB64Url(encoded).toList())
        val four = byteArrayOf(1, 2, 3, 4)
        assertEquals(four.toList(), PasskeyUtils.decodeB64Url(PasskeyUtils.encodeB64Url(four) + "==").toList())
        val ids = (1..8).map { PasskeyUtils.generateCredentialId().toList() }
        assertEquals(8, ids.distinct().size)
    }

    @Test fun privilegedOriginDoesNotMatchOrdinaryApp() {
        // The bundled allow-list only names privileged browsers; an arbitrary
        // package cannot resolve a privileged origin.
        val info = CallingAppInfoFactory.callingAppInfo("com.example.app")
        assertNull(PasskeyUtils.getPrivilegedOrigin(info, ctx))
    }
}
