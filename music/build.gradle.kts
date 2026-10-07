plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:music:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "music_note"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.music"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // Android Auto templates (P-car-dock): AndroidX Car App Library. `app`
    // provides the CarAppService/Session/Screen/template model;
    // `app-projected` provides the phone-projected (Android Auto) host
    // connection. Only pulled into the car code path (service/car/) — the
    // phone UI and the Media3 playback path are untouched.
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    implementation(project(":library:image"))

    // Casting. `:sdk:cast` owns no sockets and needs no network permission - which is the point,
    // because this app deliberately has no INTERNET permission. `:library:media` is here for the
    // Opus transcoder: every cast audio track is 48 kHz Opus, and most of the library is not.
    implementation(project(":sdk:cast"))
    implementation(project(":library:media"))

    // Car-view store-listing screenshots render through the shared host renderer.
    add("screenshotTestImplementation", project(":library:carhost"))
}