package com.android.location.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The platform-only value types have private constructors (mirroring AOSP):
 * their accessors exist purely as compile-time signatures and must throw the
 * stub exception if ever invoked on the stub class itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationStubReflectionTest {

    private fun <T> instantiate(type: Class<T>): T {
        val ctor = type.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    @Test
    fun providerRequestAccessorsThrowStub() {
        val request = instantiate(ProviderRequestUnbundled::class.java)
        val report = assertFailsWith<RuntimeException> { request.reportLocation }
        assertEquals("stub", report.message)
        val interval = assertFailsWith<RuntimeException> { request.interval }
        assertEquals("stub", interval.message)
    }

}
