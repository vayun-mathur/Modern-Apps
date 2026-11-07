package com.vayunmathur.cast.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.MirrorPreferences
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionExtraTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    private suspend fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timeout; phase=${CastController.sessionState.value.phase}")
            delay(15)
        }
    }

    private fun device(port: Int, id: String = "tv-fake-1") =
        CastDevice(id = id, friendlyName = "Fake TV", host = "127.0.0.1", port = port)

    @Test
    fun `pairing with mirror intent launches consent after pairing`() = runBlocking {
        val tv = FakeTv()
        try {
            CastController.connect(context, device(tv.port), thenMirror = true)
            await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
            CastController.submitPairCode(context, FakeTv.CODE)
            await { CastController.sessionState.value.phase == ClientPhase.Paired }
            // The consent trampoline was launched for the newly paired TV
            // (state flips to Paired a breath before the launch, so poll).
            val shadowApp = Shadows.shadowOf(
                ApplicationProvider.getApplicationContext<android.app.Application>(),
            )
            val intentDeadline = System.currentTimeMillis() + 5_000
            var intent = shadowApp.peekNextStartedActivity()
            while (intent == null && System.currentTimeMillis() < intentDeadline) {
                delay(25)
                intent = shadowApp.peekNextStartedActivity()
            }
            assertTrue(
                intent?.component?.className?.contains("MirrorConsentActivity") == true,
                "expected the consent activity, got $intent",
            )
        } finally {
            tv.close()
        }
        Unit
    }

    @Test
    fun `switching devices tears down and pairs with the new tv`() = runBlocking {
        val tvA = FakeTv()
        val tvB = FakeTv()
        try {
            CastController.connect(context, device(tvA.port), thenMirror = false)
            await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
            CastController.submitPairCode(context, FakeTv.CODE)
            await { CastController.sessionState.value.phase == ClientPhase.Paired }

            CastController.connect(context, device(tvB.port), thenMirror = false)
            await {
                CastController.sessionState.value.phase == ClientPhase.AwaitingCode ||
                    CastController.sessionState.value.phase == ClientPhase.Paired
            }
            // The second TV uses the default sender flow: it asks for a code, and
            // the stored key from TV A does not silently pair with TV B's gate
            // unless submitted. Submit and confirm the session moved.
            if (CastController.sessionState.value.phase == ClientPhase.AwaitingCode) {
                CastController.submitPairCode(context, FakeTv.CODE)
            }
            await { CastController.sessionState.value.phase == ClientPhase.Paired }
            assertEquals("tv-fake-1", CastController.device.value?.id)
        } finally {
            tvA.close()
            tvB.close()
        }
        Unit
    }

    @Test
    fun `remembered key connects without a code at controller level`() = runBlocking {
        val key = ByteArray(32) { (it + 3).toByte() }
        MirrorPreferences.rememberDeviceKey(context, "tv-fake-1", key)
        val tv = FakeTv(codeRequired = false, rememberedKey = key)
        try {
            CastController.connect(context, device(tv.port), thenMirror = false)
            await { CastController.sessionState.value.phase == ClientPhase.Paired }
            assertEquals("Fake TV", CastController.sessionState.value.receiverName)
        } finally {
            tv.close()
        }
        Unit
    }
}
