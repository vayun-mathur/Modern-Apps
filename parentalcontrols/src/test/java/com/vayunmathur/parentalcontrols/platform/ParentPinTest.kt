package com.vayunmathur.parentalcontrols.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import com.vayunmathur.parentalcontrols.auth.ParentPin
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.data.ParentalControlsDatabase
import com.vayunmathur.parentalcontrols.data.SupervisionRules
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParentPinTest {

    companion object {
        private var sharedDb: ParentalControlsDatabase? = null
    }

    private lateinit var app: Application
    private lateinit var rules: SupervisionRules

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, ParentalControlsDatabase::class.java)
            .setSingleConnectionPool()
            .setSingleConnectionPool()
            .allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[ParentalControlsDatabase::class] = mem }
        rules = SupervisionRules.get(app)
    }

    @Test
    fun parentPinSetVerifyLockoutAndClear() {
        FakeAndroidKeyStore.install()
        val pin = ParentPin.get(app)
        pin.clear()
        assertFalse(pin.isSet())
        assertFalse(pin.verify("1234")) // nothing set yet
        pin.set("1234")
        assertTrue(pin.isSet())
        assertTrue(pin.verify("1234"))
        assertFalse(pin.verify("0000"))
        assertTrue(pin.verify("1234")) // a success resets the failure counter
        // Five wrong attempts trigger the one-minute lockout.
        repeat(5) { assertFalse(pin.verify("9999")) }
        assertTrue(pin.isLockedOut())
        assertTrue(pin.lockoutRemainingMillis() > 0L)
        assertFalse(pin.verify("1234")) // even the right PIN is refused while locked out
        pin.clear()
        assertFalse(pin.isSet())
    }

    @Test
    fun parentPinSingletonAndShortPinGuard() {
        val pin = ParentPin.get(app)
        assertSame(pin, ParentPin.get(app))
        assertEquals(4, ParentPin.MIN_LENGTH)
        assertEquals("parentalcontrols_pin", ParentPin.PREFS)
        // Too-short PINs are rejected before any keystore work happens.
        val error = assertFailsWith<IllegalArgumentException> { pin.set("123") }
        assertEquals("PIN too short", error.message)
        // No lockout is recorded on a fresh install.
        assertEquals(0L, pin.lockoutRemainingMillis())
        assertFalse(pin.isLockedOut())
    }
}
