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

// This module loads two Rust libraries in tests: its own libweather_om
// (above) and libmap_renderer from :library:map (implementation dep
// below), so every unit test here has kept forkEvery = 1. Only the
// classes below actually load one: they render WeatherMapPage (whose
// VectorMap surface touches MapNative.isAvailable and whose metadata /
// decode effects touch OmTilesNative.isAvailable) or compose the map
// metadata/decode effects directly. Tests that merely construct
// WeatherMapState, use the pure-Kotlin WxTiles/WxGrid decoders, or stub
// the native path before isAvailable is evaluated never load a library
// and stay in the main task. Quarantine the loaders and the shared-JVM
// polluters below into testDevUnitTestNative (forkEvery = 1); the
// remaining classes batch 20-per-JVM in testDevUnitTest, which depends
// on the quarantine task so the usual invocation still runs everything.
rustNativeTests {
    nativeClasses.addAll(
        listOf(
            "com.vayunmathur.weather.ui.MapDecodeCacheTest",
            "com.vayunmathur.weather.ui.MapPageTest",
            "com.vayunmathur.weather.ui.PagesTest",
            "com.vayunmathur.weather.ui.Wave3bWeatherCoverageTest",
        )
    )
    // Shared-JVM polluters keep forkEvery = 1 in the same companion task:
    // WeatherViewModel turns the repository's locations flow into a
    // viewModelScope stateIn collector (SharingStarted.WhileSubscribed)
    // that is never cleared when the test never calls onCleared, and its
    // init enqueues WeatherRefreshWorker's hourly WorkManager work;
    // its fetch paths also launch viewModelScope coroutines the test
    // drains by polling. Same hazard for tests that launch MainActivity
    // (which builds the ViewModel over WeatherRepository.get's
    // process-wide singleton). This module has no DataStore owner (its
    // datastore dependency is unused by main/test sources), so no
    // DataStore polluters exist here. Audit rule: any test instantiating
    // WeatherViewModel (or launching MainActivity, which builds one)
    // belongs here; Wave3bWeatherCoverageTest and PagesTest also
    // instantiate one but are already quarantined as native loaders
    // above. All other classes batch.
    isolatedClasses.addAll(
        listOf(
            "com.vayunmathur.weather.platform.ViewModelAddLocationTest",
            "com.vayunmathur.weather.platform.ViewModelDeviceFixTest",
            "com.vayunmathur.weather.platform.ViewModelFetchTest",
            "com.vayunmathur.weather.platform.ViewModelSuccessTest",
            "com.vayunmathur.weather.platform.WeatherViewModelTest",
            "com.vayunmathur.weather.ui.DeviceLocationGrantedTest",
            "com.vayunmathur.weather.ui.HomeAndLocationsExtraTest",
            "com.vayunmathur.weather.ui.MoreEdgesTest",
            "com.vayunmathur.weather.ui.Wave3WeatherCoverageTest",
        )
    )
}

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

    testImplementation(libs.androidx.car.app.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
    // work-testing carries Robolectric for JVM tests; on-device it is
    // unavailable from the mirror and unused, so exclude it there.
    "androidTestImplementation"(libs.androidx.work.testing) {
        exclude(group = "org.robolectric")
    }
    "androidTestImplementation"(libs.androidx.car.app.testing) {
        exclude(group = "org.robolectric")
    }
}
