package com.vayunmathur.networklocation

import android.content.Context
import android.location.Address
import android.location.GeocoderParams
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeocodeServiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The framework's GeocoderParams constructors are hidden from the SDK stub. */
    private fun params(): GeocoderParams {
        val ctor = GeocoderParams::class.java
            .getConstructor(Context::class.java, Locale::class.java)
        return ctor.newInstance(context, Locale.US)
    }

    private fun clearDb() {
        OfflineDatabases.file(context, OfflineDatabases.GEOCODER).delete()
    }

    private fun installDb() {
        writeGeocoderDb(OfflineDatabases.file(context, OfflineDatabases.GEOCODER))
    }

    @Test
    fun `without the database every query reports unavailable`() {
        clearDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        val service = controller.get()
        val addrs = mutableListOf<Address>()
        assertEquals(
            "geocoder database unavailable",
            service.provider.onGetFromLocation(37.4215, -122.084, 1, params(), addrs),
        )
        assertEquals(
            "geocoder database unavailable",
            service.provider.onGetFromLocationName("Main St", 0.0, 0.0, 0.0, 0.0, 5, params(), addrs),
        )
        assertTrue(addrs.isEmpty())
        controller.destroy()
    }

    @Test
    fun `reverse geocode maps the flat native result onto an address`() {
        installDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        val service = controller.get()
        val addrs = mutableListOf<Address>()
        val err = service.provider.onGetFromLocation(37.4215, -122.084, 1, params(), addrs)
        assertNull(err)
        assertEquals(1, addrs.size)
        val a = addrs[0]
        assertEquals(37.4215, a.latitude, 1e-6)
        assertEquals(-122.084, a.longitude, 1e-6)
        assertEquals("Test Cafe", a.getAddressLine(0), "a named feature leads the address line")
        assertEquals("Test Cafe", a.featureName)
        assertEquals("Main St", a.thoroughfare)
        assertEquals("Springfield", a.locality)
        assertEquals("CA", a.adminArea)
        assertEquals("12345", a.postalCode)
        assertEquals("US", a.countryCode)
        controller.destroy()
    }

    @Test
    fun `reverse geocode far from any record adds nothing and succeeds`() {
        installDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        val addrs = mutableListOf<Address>()
        val err = controller.get().provider.onGetFromLocation(0.0, 0.0, 1, params(), addrs)
        assertNull(err)
        assertTrue(addrs.isEmpty())
        controller.destroy()
    }

    @Test
    fun `structured name query uses the forward index and honours max results`() {
        installDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        val service = controller.get()
        val addrs = mutableListOf<Address>()
        val err = service.provider.onGetFromLocationName(
            "Main St, Springfield, CA, US", 0.0, 0.0, 0.0, 0.0, 10, params(), addrs,
        )
        assertNull(err)
        assertEquals(2, addrs.size)
        // First result is the named cafe; the second is a plain "<number> <street>" address.
        assertEquals("Test Cafe", addrs[0].getAddressLine(0))
        assertEquals("124 Main St", addrs[1].getAddressLine(0))
        assertEquals("124", addrs[1].featureName)

        val one = mutableListOf<Address>()
        service.provider.onGetFromLocationName(
            "Main St, Springfield, CA, US", 0.0, 0.0, 0.0, 0.0, 1, params(), one,
        )
        assertEquals(1, one.size, "maxResults caps the appended addresses")
        controller.destroy()
    }

    @Test
    fun `unstructured name query uses the name index`() {
        installDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        val service = controller.get()
        val addrs = mutableListOf<Address>()
        val err = service.provider.onGetFromLocationName(
            "Test Cafe", 0.0, 0.0, 0.0, 0.0, 10, params(), addrs,
        )
        assertNull(err)
        assertEquals(1, addrs.size)
        assertEquals("Test Cafe", addrs[0].featureName)

        val none = mutableListOf<Address>()
        val err2 = service.provider.onGetFromLocationName(
            "No Such Place", 0.0, 0.0, 0.0, 0.0, 10, params(), none,
        )
        assertNull(err2)
        assertTrue(none.isEmpty())
        controller.destroy()
    }

    @Test
    fun `structured query for an unknown street adds nothing`() {
        installDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        val addrs = mutableListOf<Address>()
        val err = controller.get().provider.onGetFromLocationName(
            "Nowhere Road, Springfield, CA, US", 0.0, 0.0, 0.0, 0.0, 10, params(), addrs,
        )
        assertNull(err)
        assertTrue(addrs.isEmpty())
        controller.destroy()
    }

    @Test
    fun `a database arriving after create is opened on the next query`() {
        clearDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        val service = controller.get()
        val addrs = mutableListOf<Address>()
        assertEquals(
            "geocoder database unavailable",
            service.provider.onGetFromLocation(37.4215, -122.084, 1, params(), addrs),
        )
        installDb()
        assertNull(service.provider.onGetFromLocation(37.4215, -122.084, 1, params(), addrs))
        assertEquals(1, addrs.size)
        // Reopening explicitly while open releases and reopens the handle.
        service.openDatabase()
        val again = mutableListOf<Address>()
        assertNull(service.provider.onGetFromLocation(37.4215, -122.084, 1, params(), again))
        assertEquals(1, again.size)
        controller.destroy()
    }

    @Test
    fun `blank name query falls back to the raw name and finds nothing`() {
        installDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        val addrs = mutableListOf<Address>()
        val err = controller.get().provider.onGetFromLocationName(
            " , , ", 0.0, 0.0, 0.0, 0.0, 10, params(), addrs,
        )
        assertNull(err)
        assertTrue(addrs.isEmpty())
        controller.destroy()
    }

    @Test
    fun `service can be created and destroyed without a database`() {
        clearDb()
        val controller = Robolectric.buildService(GeocodeService::class.java).create()
        assertNotNull(controller.get())
        controller.destroy()
    }
}
