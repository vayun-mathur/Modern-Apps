plugins {
    id("common-conventions-app")
    // androidx.tv, so the TV entry is built on a design system that knows what a D-pad is
    // (the cast:tv precedent).
    id("common-conventions-tv")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:youpipe:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "play_arrow"
}

// Google TV hardware runs a 32-bit userspace: an arm64-only APK is rejected at install.
nativeAbis {
    armv7 = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// YouTube (InnerTube) extractor — a Rust port of PipePipeExtractor. See youpipe/src/main/rust/.
// extraAbis: the .so must also load on 32-bit Google TV.
rustNativeLib("youpipe_extractor", "youpipe", extraAbis = listOf("armeabi-v7a"))

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.youpipe"
    }
}

dependencies {
    implementation(project(":library:tv"))
    implementation(project(":library:image"))
    implementation(project(":youpipe:extractor"))
    // Puts the video on a TV through the installed Cast app: a Surface and a PCM pipe, no sockets.
    implementation(project(":sdk:cast"))
    implementation(libs.quickjs.kt)
    implementation(libs.androidx.webkit)

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)
    // DefaultHttpDataSource (HttpURLConnection) for progressive; SABR via library:network – no okhttp

    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))

    // Android-only network – HttpURLConnection + own WS; no direct okhttp dep
    implementation(project(":library:network"))

}