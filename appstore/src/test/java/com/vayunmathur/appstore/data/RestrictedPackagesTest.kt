package com.vayunmathur.appstore.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Source-restricted packages: GrapheneOS reserves some Google packages for its
 * own first-party source, so committing a session for one always fails. The
 * store tracks those packages and leaves them out of update checks instead of
 * re-offering a doomed update every 12 hours.
 */
@RunWith(RobolectricTestRunner::class)
class RestrictedPackagesTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @AfterTest
    fun clearPrefs() {
        // SharedPreferences survive across tests in the same sandbox; start clean.
        context.getSharedPreferences("appstore-restricted-packages", 0).edit().clear().apply()
    }

    @Test
    fun `stock android restricts nothing by default`() {
        assertTrue(RestrictedPackages.forDevice(context).isEmpty())
        assertFalse(RestrictedPackages.isGrapheneOS(context))
    }

    @Test
    fun `a first-party refusal is remembered`() {
        RestrictedPackages.recordIfRestricted(
            context,
            "com.example.app",
            "INSTALL_FAILED_SESSION_INVALID: Only the first party package source and shell are allowed",
        )

        assertEquals(setOf("com.example.app"), RestrictedPackages.forDevice(context))
    }

    @Test
    fun `the refusal match is case-insensitive`() {
        RestrictedPackages.recordIfRestricted(context, "com.example.app", "FIRST PARTY PACKAGE SOURCE denied")

        assertEquals(setOf("com.example.app"), RestrictedPackages.forDevice(context))
    }

    @Test
    fun `ordinary failures are not remembered`() {
        RestrictedPackages.recordIfRestricted(context, "com.example.app", "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        RestrictedPackages.recordIfRestricted(context, "com.example.other", null)

        assertTrue(RestrictedPackages.forDevice(context).isEmpty())
    }

    @Test
    fun `blank package names are ignored`() {
        RestrictedPackages.recordIfRestricted(context, null, "first party package source")
        RestrictedPackages.recordIfRestricted(context, "", "first party package source")

        assertTrue(RestrictedPackages.forDevice(context).isEmpty())
    }

    @Test
    fun `learned packages accumulate`() {
        RestrictedPackages.recordIfRestricted(context, "com.example.a", "first party package source")
        RestrictedPackages.recordIfRestricted(context, "com.example.b", "first party package source")

        assertEquals(
            setOf("com.example.a", "com.example.b"),
            RestrictedPackages.forDevice(context),
        )
    }
}
