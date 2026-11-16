package com.vayunmathur.shop.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.shop.MainActivity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows

/** Worker doWork and MainActivity intent handling where Robolectric supports it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainAndWorkerCoverageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        context.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun mainActivityShowOrdersIntentAndCompanion() {
        assertEquals("com.vayunmathur.shop.action.SHOW_ORDERS", MainActivity.ACTION_SHOW_ORDERS)
        val controller = Robolectric.buildActivity(MainActivity::class.java,
            android.content.Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_ORDERS))
        val activity = controller.create().get()
        assertTrue(activity != null)
        controller.start().resume()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // onNewIntent with a VIEW link must not crash
        controller.newIntent(android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://cool.example/products/shoe")))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }
}
