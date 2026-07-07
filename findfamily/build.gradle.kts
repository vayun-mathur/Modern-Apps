plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260707
        versionName = "v2.5.7b"
        applicationId = "com.vayunmathur.findfamily"
    }
}

dependencies {

    // ktor
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))

    implementRoom(libs)

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