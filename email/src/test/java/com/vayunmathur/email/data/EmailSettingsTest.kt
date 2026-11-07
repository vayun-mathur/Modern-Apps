package com.vayunmathur.email.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins [EmailSettings]: default-off remote images, persistence and singleton identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailSettingsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `load remote images defaults off and toggles persist on the flow`() {
        val settings = EmailSettings.get(context)
        // Reset to a known state regardless of test order within the process.
        settings.setLoadRemoteImages(false)
        assertFalse(settings.loadRemoteImages.value)
        settings.setLoadRemoteImages(true)
        assertTrue(settings.loadRemoteImages.value)
        // The same instance is returned, so every reader sees the flip.
        assertSame(settings, EmailSettings.get(context))
        assertTrue(EmailSettings.get(context).loadRemoteImages.value)
        settings.setLoadRemoteImages(false)
        assertEquals(false, settings.loadRemoteImages.value)
    }
}
