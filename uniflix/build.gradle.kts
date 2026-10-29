plugins {
    id("common-conventions-app")
    // androidx.tv: the TV entry is built on a design system that knows what a D-pad is.
    id("common-conventions-tv")
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "movie"
}

// Google TV hardware runs a 32-bit userspace: an arm64-only APK is rejected at install.
// Uniflix ships the QuickJS native runtime, so it needs the 32-bit slice too.
nativeAbis {
    armv7 = true
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.uniflix"
    }
}

dependencies {
    implementation(project(":library:tv"))
    implementation(project(":library:network"))
    implementation(project(":library:image"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    // JS plugin runtime: QuickJS engine + pure-Java WASM host (Chicory).
    implementation(libs.quickjs.kt)
    implementation(libs.chicory.runtime)
}

// Desktop JVM unit tests cannot load the Android QuickJS .so: run them against
// the -jvm artifact (per quickjs-kt README) while the app keeps the -android AAR.
// configureEach (not named) so this holds however AGP creates the test configs.
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.dokar3:quickjs-kt-android"))
                .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
        }
    }
}
