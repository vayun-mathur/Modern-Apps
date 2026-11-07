package com.android.location.provider;

/**
 * Compile-only stub. Real implementation lives in the platform's com.android.location.provider
 * shared library. Do not package into an APK.
 */
public final class ProviderRequestUnbundled {
    public boolean getReportLocation() {
        throw new RuntimeException("stub");
    }

    public long getInterval() {
        throw new RuntimeException("stub");
    }

    // Package-private (not private as in AOSP) so JVM tests in this package can
    // instantiate the stub and pin its throwing accessors. Compile-only either way:
    // this class is never packaged, so the difference is invisible at runtime.
    ProviderRequestUnbundled() {}
}
