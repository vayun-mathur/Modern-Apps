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
        versionCode = 20261005
        versionName = "v2.6.9"
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

    // Android Auto (P12): AndroidX Car App Library. `app` provides the
    // CarAppService/Session/Screen/template model; `app-projected` provides the
    // phone-projected (Android Auto) host connection. Only pulled into the car
    // code path — the phone UI is untouched.
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    // Car-view store-listing screenshots render through the shared host renderer.
    add("screenshotTestImplementation", project(":library:carhost"))
}
