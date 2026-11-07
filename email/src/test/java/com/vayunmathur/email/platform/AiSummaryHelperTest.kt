package com.vayunmathur.email.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [AiSummaryHelper] without OpenAssistant installed: request is a no-op, clear resets state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSummaryHelperTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun preview(id: Long) = EmailPreview(
        accountEmail = "me@x.com", folderName = "INBOX", id = id,
        subject = "s$id", from = "Sender <f@x.com>", date = "d", peekContent = "body $id",
    )

    @Test
    fun `request without openassistant installed does not enter loading`() {
        val helper = AiSummaryHelper(context)
        helper.request(listOf(preview(1L), preview(2L)))
        assertFalse(helper.loading.value)
        assertNull(helper.summary.value)
    }

    @Test
    fun `clear after request keeps empty state`() {
        val helper = AiSummaryHelper(context)
        helper.request(listOf(preview(1L)))
        helper.clear()
        assertFalse(helper.loading.value)
        assertNull(helper.summary.value)
        assertEquals(null, helper.summary.value)
    }
}
