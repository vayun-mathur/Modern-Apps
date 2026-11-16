package com.android.location.provider

import android.location.GeocoderParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Plain-JVM (no Robolectric): the `android.location` package is loaded from
 * the boot classpath under Robolectric, which shadows this module's stub.
 * On a plain classpath the module class wins, so its stub contract is
 * directly observable here.
 */
class GeocoderParamsJvmTest {

    @Test
    fun geocoderParamsAccessorsThrowStub() {
        val ctor = GeocoderParams::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        val params = ctor.newInstance()
        assertEquals("stub", assertFailsWith<RuntimeException> { params.locale }.message)
        assertEquals("stub", assertFailsWith<RuntimeException> { params.clientPackage }.message)
    }
}
