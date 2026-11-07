package com.vayunmathur.email.platform

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailPreview
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [AiSummaryHelper] with OpenAssistant "installed" (fake package in the
 * shadow package manager): request must hand the inference service a
 * prompt built from the message snippets plus the JSON schema, enter
 * the loading state, ignore a second request while busy, and publish
 * the returned summary (or just stop loading on a malformed reply).
 * Separate class so the fake package never leaks into the
 * not-installed tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSummaryInstalledTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var helper: AiSummaryHelper

    private fun preview(id: Long, subject: String) = EmailPreview(
        accountEmail = "me@x.com", folderName = "INBOX", id = id,
        subject = subject, from = "Sender <f@x.com>", date = "d", peekContent = "body $id",
    )

    @BeforeTest
    fun installOpenAssistant() {
        shadowOf(context.packageManager).addPackage(
            PackageInfo().apply {
                packageName = "com.vayunmathur.openassistant"
                versionCode = 1
            }
        )
        helper = AiSummaryHelper(context)
    }

    private fun lastStartedService() =
        shadowOf(context as Application).nextStartedService

    @Test
    fun `request starts inference with the snippet prompt and schema`() {
        helper.request(listOf(preview(1L, "Trip plans"), preview(2L, "Invoice due")))
        assertTrue(helper.loading.value)
        val intent = lastStartedService()
        assertNotNull(intent)
        assertEquals(
            "com.vayunmathur.openassistant.util.InferenceService",
            intent.component?.className,
        )
        val prompt = intent.getStringExtra("user_text") ?: ""
        assertTrue(prompt.contains("Summarize these emails"), prompt)
        assertTrue(prompt.contains("Trip plans"), prompt)
        assertTrue(prompt.contains("Invoice due"), prompt)
        assertTrue(prompt.contains("Sender"), prompt)
        assertTrue(intent.getStringExtra("schema")?.contains("\"summary\"") == true)
    }

    @Test
    fun `a second request while loading is ignored`() {
        helper.request(listOf(preview(1L, "One")))
        assertNotNull(lastStartedService())
        helper.request(listOf(preview(2L, "Two")))
        assertNull(lastStartedService())
        assertTrue(helper.loading.value)
    }

    @Test
    fun `delivering a summary publishes it and clears loading`() {
        helper.request(listOf(preview(1L, "Trip plans")))
        val intent = lastStartedService()
        assertNotNull(intent)
        val receiver = intent.getParcelableExtra("RECEIVER", ResultReceiver::class.java)
        assertNotNull(receiver)
        receiver.send(0, Bundle().apply {
            putString("json_result", """{"summary":"Two short updates"}""")
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Two short updates", helper.summary.value)
        assertFalse(helper.loading.value)
    }

    @Test
    fun `delivering a malformed reply just clears loading`() {
        helper.request(listOf(preview(1L, "Trip plans")))
        val intent = lastStartedService()
        assertNotNull(intent)
        val receiver = intent.getParcelableExtra("RECEIVER", ResultReceiver::class.java)
        assertNotNull(receiver)
        receiver.send(0, Bundle().apply { putString("json_result", "not json") })
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(helper.summary.value)
        assertFalse(helper.loading.value)
    }

    @Test
    fun `an error result code publishes nothing`() {
        helper.request(listOf(preview(1L, "Trip plans")))
        val intent = lastStartedService()
        assertNotNull(intent)
        val receiver = intent.getParcelableExtra("RECEIVER", ResultReceiver::class.java)
        assertNotNull(receiver)
        receiver.send(1, Bundle().apply {
            putString("json_result", """{"summary":"should not land"}""")
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(helper.summary.value)
        assertFalse(helper.loading.value)
    }
}
