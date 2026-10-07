plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:health:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "ecg_heart"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.health"
    }

    androidResources {
        // The food database asset is already brotli-compressed by
        // scripts/generate_food_db.py. Letting AAPT deflate it again costs
        // build time, gains nothing, and makes the app pay a second inflate
        // pass on top of its own when unpacking it.
        noCompress += "br"
    }
}

dependencies {
    // healthconnect
    implementation(libs.androidx.connect.client)

    implementation(libs.androidx.work.runtime.ktx)

    // room
    implementRoom(libs)
    implementation(project(":library:room"))

    // Decodes the brotli-compressed food database asset. This is the only
    // reason the app links a codec at all; it does not use the network.
    implementation(libs.brotli.dec)
}