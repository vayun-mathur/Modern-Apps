plugins {
    id("common-conventions-app")
}

launcherIcon {
    symbol = "my_location"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.networklocation"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native code for this module, loaded as libnetworklocation.so: RANSAC + EM multilateration
// device-position estimation over the resolved beacon fixes, AND the offline geocoder
// search over geocoder.db. Both live in networklocation/src/main/rust/.
rustNativeLib("networklocation")

// Only the classes below actually load libnetworklocation: they call
// the *Native bridges directly, or drive OfflineBeaconStore /
// GeocodeService / LocationReportingTask, which open and query through
// WpsStoreNative / GeocoderNative / NetworkLocationNative (touching a
// bridge's `available` already loads the library). Pure key/position
// math, feed, provider-contract and compose tests never touch a bridge
// and stay in the main task. Quarantine the loaders into
// testDevUnitTestNative (forkEvery = 1); the remaining classes batch
// 20-per-JVM in testDevUnitTest, which depends on the quarantine task so
// the usual invocation still runs everything. No isolatedClasses: this
// module has no ViewModel and no DataStore owner (OfflineDatabases is
// plain files, and every test installs/clears them itself), so no
// shared-JVM polluters exist here.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.networklocation.GeocodeServiceTest",
            "com.vayunmathur.networklocation.NativeBridgesTest",
            "com.vayunmathur.networklocation.provider.LocationReportingTaskTest",
            "com.vayunmathur.networklocation.provider.OfflineBeaconStoreTest",
        )
    )
}

// The offline databases (geocoder.db, wifi.wpsdb up to ~2 GB, cells.wpsdb far
// smaller) are NOT bundled. Shipping them as assets put a 3.5 GB APK in the MAOS system image,
// which pushed the factory install zip past fastboot's 4 GiB zip limit. They are downloaded to
// device-protected storage on request instead — see OfflineDatabases.kt. Until they arrive every
// lookup misses, which the readers already treat as "no data": beacons stay unresolved and no
// position is reported, and the geocoder reports itself unavailable.

dependencies {
    // Compile-only stubs for the framework's unbundled provider API (com.android.location.provider).
    // Provided at runtime by <uses-library>; must NOT be packaged.
    compileOnly(project(":library:locationprovider"))
    // Reporting loop + IO for the offline database fetch.
    implementation(libs.kotlinx.coroutines.android)
    // Resumable, checksummed, unmetered-only fetch of the offline databases, which are no
    // longer bundled (see OfflineDatabases). Also supplies the progress UI on the status screen.
    implementation(project(":library:downloadservice"))
    // The provider stubs are compile-only in production (the framework supplies them
    // at runtime), but unit tests exercise GeocodeService's provider callbacks on the
    // JVM, so the stub signatures must be on the test runtime classpath too.
    testImplementation(project(":library:locationprovider"))
    // For initializing WorkManager when a test taps the offline-database download button.
    testImplementation(libs.androidx.work.runtime.ktx)
    // The offline geocoder search runs natively (Rust/ruzstd) over geocoder.db; there is no
    // Kotlin-side DB code, so no zstd-jni / serialization deps are needed here.
}
