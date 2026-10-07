plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device: every screen here is driven by live microphone
    // audio, which an instrumented test has no way to supply.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "tune"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.tuner"
    }
}

// No dependencies beyond what common-conventions-app supplies (:library, :library:ui,
// Compose, lifecycle, kotlinx-serialization, kotlin-test). The whole analysis chain is
// plain Kotlin under domain/, and nothing here touches the network - adding a module
// that declares INTERNET would silently invalidate the "100% offline" store listing.
