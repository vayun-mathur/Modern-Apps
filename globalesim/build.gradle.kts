plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:globalesim:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "sim_card_download"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.globalesim"
    }
}

dependencies {
    // First-party backend (api.maproj.org) over pinned TLS roots.
    implementation(project(":library:network"))
    implementation(project(":library:log"))
    // Anonymous account id persistence.
    implementation(libs.androidx.datastore.preferences)
    // Credit top-ups via Stripe PaymentSheet (server creates the
    // PaymentIntent; the secret key never leaves the server).
    implementation(libs.stripe.android)
    // stripe-android applies the kotlin-parcelize compiler plugin but never publishes
    // kotlin-parcelize-runtime in its POM (see fooddelivery/build.gradle.kts).
    implementation(libs.kotlin.parcelize.runtime)
}
