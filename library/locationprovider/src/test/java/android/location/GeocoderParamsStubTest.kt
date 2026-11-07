package android.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
/**
 * Pins the GeocoderParams stub contract. The test lives in the stub's own
 * package because the stub constructor is package-private (see the source
 * note in GeocoderParams.java), which is what makes instantiation possible
 * without reflection.
 *
 * Deliberately NOT Robolectric: under Robolectric, `android.location.*`
 * resolves to the framework's real class (which returns nulls), so the stub
 * would never execute. On the plain JVM the module's own stub class wins.
 */
class GeocoderParamsStubTest {

    @Test
    fun accessorsAreStubs() {
        val params = GeocoderParams()
        assertEquals("stub", assertFailsWith<RuntimeException> { params.locale }.message)
        assertEquals("stub", assertFailsWith<RuntimeException> { params.clientPackage }.message)
    }
}
