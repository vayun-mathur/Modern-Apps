plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "location_on"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.maps"
    }
    // PoiIndexTest exercises the real side-file reader, which logs. Without this every
    // android.util.Log call throws "not mocked" and the test can only cover code that never logs.
    testOptions.unitTests.isReturnDefaultValues = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native offline routing engine + traffic MVT tile encoder (Rust). See
// maps/src/main/rust/. Replaces the previous CMake/C++ libofflinerouter.
rustNativeLib("offlinerouter", "maps")

dependencies {
    implementation(project(":library:map"))
    implementation(project(":library:image"))
    implementation(project(":library:network"))

    implementation(project(":library:downloadservice"))

    // WorkManager: the map-overlay check worker outlives the settings screen.
    implementation(project(":library:work"))

    // Manifest JSON parsing (pure JVM: unit-testable, unlike org.json).
    implementation(libs.kotlinx.serialization.json)

    // Android Auto (P12): AndroidX Car App Library. `app` provides the
    // CarAppService/Session/Screen/template model; `app-projected` provides the
    // phone-projected (Android Auto) host connection. Only pulled into the car
    // code path — the phone UI is untouched.
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    // Host-side car screens (CarSearchScreen/CarMapRenderer) run against
    // TestCarContext under Robolectric; the real head unit is DHU-only.
    testImplementation(libs.androidx.car.app.testing)

    // Car-view store-listing screenshots render through the shared host renderer.
    add("screenshotTestImplementation", project(":library:carhost"))
}
