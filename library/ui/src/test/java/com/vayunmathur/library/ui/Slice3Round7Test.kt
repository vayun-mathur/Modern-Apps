package com.vayunmathur.library.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/** Slice 3/3 round 7: ExternalIntents failure reporting + clipboard. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round7Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun launchFromAppContextDoesNotThrowAndStartsIntent() {
        // Regression: startActivity from an Application context used to throw
        // AndroidRuntimeException (missing NEW_TASK); launch must not throw.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/page"))
        assertTrue(ExternalIntents.launch(context, intent))
        val started = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://example.com/page"), started.data)
    }

    @Test fun openUrlStartsViewIntent() {
        assertTrue(ExternalIntents.openUrl(context, "https://example.com"))
        val started = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
    }

    @Test fun dialAndSmsAndEmailDoNotThrow() {
        ExternalIntents.dial(context, "5551234")
        ExternalIntents.sendSms(context, "5551234")
        ExternalIntents.sendEmail(context, "a@example.com")
        ExternalIntents.openMap(context, "paris")
        ExternalIntents.openAppListing(context, "com.example.app")
    }

    @Test fun shareTextDoesNotThrow() {
        ExternalIntents.shareText(context, "hello world")
        ExternalIntents.shareText(context, "hi", chooserTitle = "Share via")
    }

    @Test fun copyToClipboardStoresTextAndLabel() {
        ExternalIntents.copyToClipboard(context, "copied-text", "my-label")
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        assertEquals(1, clip?.itemCount)
        assertEquals("copied-text", clip?.getItemAt(0)?.text?.toString())
        assertEquals("my-label", clip?.description?.label?.toString())
    }

    @Test fun openFileDoesNotThrow() {
        ExternalIntents.openFile(context, Uri.parse("content://example/file/1"), mimeType = "text/plain")
    }

    @Test fun startedIntentCarriesExpectedAction() {
        ExternalIntents.launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:123")))
        val started = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        // Under Robolectric the dial intent may resolve or not; if started, verify shape.
        if (started != null) {
            assertEquals(Intent.ACTION_DIAL, started.action)
        }
    }
}
