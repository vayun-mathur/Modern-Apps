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
        versionCode = 20261021
        versionName = "v2.6.10"
        applicationId = "com.vayunmathur.weather"
    }

    androidResources {
        // The places database asset is already brotli-compressed by
        // scripts/generate_places_db.py. Letting AAPT deflate it again costs
        // build time, gains nothing, and makes the app pay a second inflate
        // pass on top of its own when unpacking it.
        noCompress += "br"
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

    // Decodes the brotli-compressed places database asset. This is the only
    // reason the app links a codec at all; it does not use the network.
    implementation(libs.brotli.dec)

    // Android Auto (WEATHER): AndroidX Car App Library. `app` provides the
    // CarAppService/Session/Screen/template model. Only pulled into the car
    // code path (service/car/) — the phone UI is untouched.
    implementation(libs.androidx.car.app)

    // Car-view store-listing screenshots render through the shared host renderer.
    add("screenshotTestImplementation", project(":library:carhost"))
}
