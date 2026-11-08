package android.location;

import java.util.Locale;

/**
 * Compile-only stub of the hidden @SystemApi android.location.GeocoderParams. Needed because it
 * appears in GeocodeProvider's method signatures but isn't in the public SDK. This module is
 * depended on with compileOnly, so this class is NOT packaged — at runtime the real framework
 * GeocoderParams (on the boot classpath) is used.
 */
public class GeocoderParams {
    public Locale getLocale() {
        throw new RuntimeException("stub");
    }

    public String getClientPackage() {
        throw new RuntimeException("stub");
    }

    private GeocoderParams() {}
}
