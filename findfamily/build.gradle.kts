plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "moved_location"
}

android {
    defaultConfig {
        versionCode = 20260718
        versionName = "v2.6.0"
        applicationId = "com.vayunmathur.findfamily"
    }
}

dependencies {

    // ktor
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))

    implementation(libs.coil.compose)

    // maplibre
    implementation(libs.maplibre.compose)

    // Public AOSP ranging API (android.ranging.*) is part of the framework
    // on Android 15+ — no third-party library needed. We intentionally avoid
    // androidx.core.uwb because its only backend is GMS-mediated, which fails
    // on GrapheneOS (sandboxed Play Services can't talk to the platform UWB
    // service). All UWB code paths are gated on Build.VERSION.SDK_INT >= 35.
}