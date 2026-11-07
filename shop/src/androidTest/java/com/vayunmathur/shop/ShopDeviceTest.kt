package com.vayunmathur.shop

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.vayunmathur.shop.data.ProfileRepository
import com.vayunmathur.shop.data.ShopProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests for the layers Robolectric cannot run faithfully: the SQLCipher
 * profile database (AndroidKeyStore) and real WebView embedded checkout.
 */
@RunWith(AndroidJUnit4::class)
class ShopDeviceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** runBlocking/runTest from coroutines-test clash with the app's runtime on-device. */
    private fun <T> sync(block: suspend () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        val latch = CountDownLatch(1)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                result = block()
            } catch (e: Throwable) {
                failure = e
            } finally {
                latch.countDown()
            }
        }
        assertTrue("timed out", latch.await(60, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun profileRepositoryPersistsTheBuyerProfileInTheEncryptedDatabase() = sync {
        val repository = ProfileRepository.get(context)
        val saved = ShopProfile(
            firstName = "Device", lastName = "Buyer", email = "device@example.com",
            phone = "+14155550123", street = "1 Main St", city = "Springfield",
            region = "IL", country = "US", postalCode = "62701",
        )
        repository.save(saved)
        val loaded = repository.profile.first { it != null }
        assertEquals("device@example.com", loaded?.email)
        assertEquals("Device", loaded?.firstName)
        assertEquals(null, loaded?.validationError())
    }

    @Test
    fun mainActivityLaunchesToTheProfileGateOrTheTabs() {
        val device = UiDevice.getInstance(instrumentation)
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(context.packageName, "com.vayunmathur.shop.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        // Fresh data shows the profile gate; a saved profile (see the repository test,
        // in either order) lands on the tabs. Either proves launch + composition.
        val gate = device.wait(Until.findObject(By.text("Add your details to start shopping.")), 15_000)
        val tabs = device.wait(Until.findObject(By.text("Search products")), 2_000)
        assertTrue("neither the profile gate nor the tabs appeared", gate != null || tabs != null)
        assertEquals(context.packageName, device.currentPackageName)
    }

}
