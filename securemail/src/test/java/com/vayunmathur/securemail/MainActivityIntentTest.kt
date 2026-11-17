package com.vayunmathur.securemail

import com.vayunmathur.securemail.platform.IntentState

import android.content.Intent
import android.net.Uri
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MainActivity's intent-to-route mapping, which had never run in a test:
 * SENDTO/SEND intents from other apps must land as a seeded composer
 * route, and plain launches must not invent one.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityIntentTest {

    @BeforeTest fun setUp() = FakeAndroidKeyStore.install()

    @AfterTest fun tearDown() {
        IntentState.navigationRoute = null
    }

    @Test fun `sendto intent becomes a composer route addressed to the recipient`() {
        IntentState.navigationRoute = null
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ada@example.org")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Hello Ada")
            putExtra(Intent.EXTRA_TEXT, "Body text")
        }
        Robolectric.buildActivity(MainActivity::class.java, intent).create()
        assertEquals(
            Route.Composer(to = "ada@example.org", subject = "Hello Ada", body = "Body text"),
            IntentState.navigationRoute,
        )
    }

    @Test fun `send intent and plain launch map as documented`() {
        IntentState.navigationRoute = null
        val share = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "shared words")
            type = "text/plain"
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, share)
        controller.create()
        assertEquals(
            Route.Composer(to = "", subject = "", body = "shared words"),
            IntentState.navigationRoute,
        )

        // A later plain intent replaces the pending route with nothing to do.
        IntentState.navigationRoute = null
        controller.newIntent(Intent(Intent.ACTION_MAIN))
        assertNull(IntentState.navigationRoute)
        controller.destroy()
    }
}
