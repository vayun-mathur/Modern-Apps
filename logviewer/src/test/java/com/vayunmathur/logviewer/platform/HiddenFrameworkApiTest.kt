package com.vayunmathur.logviewer.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class HiddenFrameworkApiTest {

    class FakeHidden {
        @JvmField
        var longVersionCode: Long = 4242L

        @JvmField
        var tracesFilePath: String = "/data/anr/traces.txt"

        @JvmField
        var applicationInfo: Any = "the-app-info"

        @JvmField
        var notALong: String = "text"
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `longField reads a public long field and rejects wrong type or missing`() {
        val fake = FakeHidden()
        assertEquals(4242L, HiddenFrameworkApi.longField(fake, "longVersionCode"))
        assertNull(HiddenFrameworkApi.longField(fake, "notALong"))
        assertNull(HiddenFrameworkApi.longField(fake, "noSuchField"))
    }

    @Test
    fun `stringField reads a string field and rejects other types`() {
        val fake = FakeHidden()
        assertEquals("/data/anr/traces.txt", HiddenFrameworkApi.stringField(fake, "tracesFilePath"))
        assertNull(HiddenFrameworkApi.stringField(fake, "longVersionCode"))
        assertNull(HiddenFrameworkApi.stringField(fake, "missing"))
    }

    @Test
    fun `objectField returns the field value or null when missing`() {
        val fake = FakeHidden()
        assertEquals("the-app-info", HiddenFrameworkApi.objectField(fake, "applicationInfo"))
        assertEquals(4242L, HiddenFrameworkApi.objectField(fake, "longVersionCode"))
        assertNull(HiddenFrameworkApi.objectField(fake, "missing"))
    }

    @Test
    fun `userType resolves through reflection and bootloader degrades to null`() {
        // Robolectric's hidden UserManager.getUserInfo resolves a secondary full user;
        // OemLockManager is absent, which is exactly the degraded path production must survive.
        assertEquals("android.os.usertype.full.SECONDARY", HiddenFrameworkApi.userType(context))
        assertNull(HiddenFrameworkApi.isBootloaderUnlocked(context))
    }
}
