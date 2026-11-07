package com.vayunmathur.sdk.openassistant

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class OpenAssistantTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun installAssistant() {
        val info = PackageInfo().apply { packageName = "com.vayunmathur.openassistant" }
        Shadows.shadowOf(app.packageManager).installPackage(info)
    }

    private fun nextStartedService(): Intent? =
        Shadows.shadowOf(app as android.app.Application).nextStartedService

    private fun receiverOf(intent: Intent): ResultReceiver {
        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>("RECEIVER")
        return assertNotNull(receiver)
    }

    private fun idleMain() {
        ShadowLooper.idleMainLooper()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun notAvailableByDefault() {
        val oa = OpenAssistant(app)
        assertEquals(false, oa.isAvailable())
    }

    @Test fun generateThrowsWhenNotInstalled() = runBlocking {
        val oa = OpenAssistant(app)
        val e = assertFailsWith<AssistantNotInstalledException> { oa.generate("hi") }
        assertEquals("OpenAssistant app is not installed on this device", e.message)
        assertFailsWith<AssistantNotInstalledException> { oa.generateJson("hi", "{}") }
        Unit
    }

    @Test fun availableAfterInstall() {
        installAssistant()
        assertTrue(OpenAssistant(app).isAvailable())
    }

    @Test fun generateReturnsJsonResultAndSendsIntent() = runBlocking {
        installAssistant()
        val oa = OpenAssistant(app, timeoutMs = 10_000)
        val deferred = async(Dispatchers.Default) { oa.generate("hello") }
        // Wait until the service intent has been dispatched.
        var intent: Intent? = null
        var guard = 0
        while (intent == null && guard++ < 100) {
            idleMain()
            intent = nextStartedService()
            if (intent == null) Thread.sleep(10)
        }
        val started = assertNotNull(intent, "no service intent dispatched")
        assertEquals(
            ComponentName("com.vayunmathur.openassistant", "com.vayunmathur.openassistant.util.InferenceService"),
            started.component,
        )
        assertEquals("hello", started.getStringExtra("user_text"))
        // generate() without a schema must send the default response schema.
        assertTrue(started.getStringExtra("schema")!!.contains("\"response\""))
        receiverOf(started).send(0, Bundle().apply { putString("json_result", "{\"a\":1}") })
        idleMain()
        assertEquals("{\"a\":1}", withTimeoutOrNull(5_000) { deferred.await() })
    }

    @Test fun generateJsonSendsCustomSchema() = runBlocking {
        installAssistant()
        val oa = OpenAssistant(app, timeoutMs = 10_000)
        val schema = "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"number\"}}}"
        val deferred = async(Dispatchers.Default) { oa.generateJson("count", schema) }
        var intent: Intent? = null
        var guard = 0
        while (intent == null && guard++ < 100) {
            idleMain()
            intent = nextStartedService()
            if (intent == null) Thread.sleep(10)
        }
        val started = assertNotNull(intent)
        assertEquals(schema, started.getStringExtra("schema"))
        assertEquals("count", started.getStringExtra("user_text"))
        receiverOf(started).send(0, Bundle().apply { putString("json_result", "{\"x\":2}") })
        idleMain()
        assertEquals("{\"x\":2}", withTimeoutOrNull(5_000) { deferred.await() })
    }

    @Test fun successWithNullDataReturnsEmpty() = runBlocking {
        installAssistant()
        val oa = OpenAssistant(app, timeoutMs = 10_000)
        val deferred = async(Dispatchers.Default) { oa.generate("hi") }
        var intent: Intent? = null
        var guard = 0
        while (intent == null && guard++ < 100) {
            idleMain(); intent = nextStartedService(); if (intent == null) Thread.sleep(10)
        }
        receiverOf(assertNotNull(intent)).send(0, null)
        idleMain()
        assertEquals("", withTimeoutOrNull(5_000) { deferred.await() })
    }

    @Test fun errorResultThrowsAssistantException() = runBlocking {
        installAssistant()
        val oa = OpenAssistant(app, timeoutMs = 10_000)
        val deferred = async(Dispatchers.Default) { runCatching { oa.generate("hi") } }
        var intent: Intent? = null
        var guard = 0
        while (intent == null && guard++ < 100) {
            idleMain(); intent = nextStartedService(); if (intent == null) Thread.sleep(10)
        }
        receiverOf(assertNotNull(intent)).send(1, Bundle().apply { putString("error", "model exploded") })
        idleMain()
        val result = withTimeoutOrNull(5_000) { deferred.await() }
        val e = assertNotNull(result).exceptionOrNull()
        assertTrue(e is AssistantException, "got $e")
        assertEquals("model exploded", e!!.message)
    }

    @Test fun errorResultWithoutMessageUsesFallback() = runBlocking {
        installAssistant()
        val oa = OpenAssistant(app, timeoutMs = 10_000)
        val deferred = async(Dispatchers.Default) { runCatching { oa.generate("hi") } }
        var intent: Intent? = null
        var guard = 0
        while (intent == null && guard++ < 100) {
            idleMain(); intent = nextStartedService(); if (intent == null) Thread.sleep(10)
        }
        receiverOf(assertNotNull(intent)).send(7, null)
        idleMain()
        val e = assertNotNull(withTimeoutOrNull(5_000) { deferred.await() }).exceptionOrNull()
        assertTrue(e is AssistantException, "got $e")
        assertEquals("Inference failed", e!!.message)
    }

    @Test fun generateTimesOutWhenServiceNeverResponds() = runBlocking {
        installAssistant()
        val oa = OpenAssistant(app, timeoutMs = 300)
        assertFailsWith<TimeoutCancellationException> { oa.generate("slow") }
        Unit
    }

    /** Context whose startForegroundService always throws [failure]. */
    private fun throwingContext(failure: RuntimeException): Context = object : ContextWrapper(app) {
        override fun startForegroundService(service: Intent?): ComponentName? = throw failure
    }

    @Test fun securityExceptionBecomesAssistantException() = runBlocking {
        installAssistant()
        val oa = OpenAssistant(throwingContext(SecurityException("denied")), timeoutMs = 10_000)
        val e = assertFailsWith<AssistantException> { oa.generate("hi") }
        assertTrue(e.message!!.contains("Failed to start InferenceService"), e.message)
        assertTrue(e.message!!.contains("denied"), e.message)
        val e2 = assertFailsWith<AssistantException> {
            OpenAssistant(throwingContext(IllegalStateException("bg start")), timeoutMs = 10_000)
                .generateJson("hi", "{}")
        }
        assertTrue(e2.message!!.contains("bg start"), e2.message)
    }

    @Test fun closeIsNoOp() = runBlocking {
        OpenAssistant(app).close()
        OpenAssistant(app).close()
    }

    @Test fun secureResultReceiverForwards() {
        var gotCode: Int? = null
        var gotData: Bundle? = null
        val receiver = SecureResultReceiver(null) { code, data -> gotCode = code; gotData = data }
        val bundle = Bundle().apply { putString("k", "v") }
        receiver.send(3, bundle)
        assertEquals(3, gotCode)
        assertEquals("v", gotData!!.getString("k"))
        receiver.send(0, null)
        assertEquals(0, gotCode)
        assertNull(gotData)
    }

    @Test fun exceptionsCarryMessages() {
        assertEquals(
            "OpenAssistant app is not installed on this device",
            AssistantNotInstalledException().message,
        )
        assertEquals("custom", AssistantException("custom").message)
        assertTrue(AssistantNotInstalledException() is Exception)
    }
}
