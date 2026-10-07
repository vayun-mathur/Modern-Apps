plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:weather:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "partly_cloudy_day"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.weather"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native `.om` weather-file decoder (Rust). See weather/src/main/rust/.
rustNativeLib("weather_om", "weather")

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:widgets"))
    implementation(libs.androidx.datastore.preferences)
    implementation(project(":library:map"))
    implementRoom(libs)
    implementation(project(":library:room"))

    // Android Auto (WEATHER): AndroidX Car App Library. `app` provides the
    // CarAppService/Session/Screen/template model. Only pulled into the car
    // code path (service/car/) — the phone UI is untouched.
    implementation(libs.androidx.car.app)

    // Car-view store-listing screenshots render through the shared host renderer.
    add("screenshotTestImplementation", project(":library:carhost"))
}
