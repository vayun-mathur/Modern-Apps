plugins { id("common-conventions-wear")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Renders land in this module's own
    // metadata_data/photos/<wear-module-key>/wear/ bucket.
    id("common-conventions-preview-metadata") }
launcherIcon { symbol = "partly_cloudy_day" }
android {
    namespace = "com.vayunmathur.weather.wear"
    defaultConfig {
        applicationId = "com.vayunmathur.weather"
        minSdk = 31
    }
}
dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":library:wearlink"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
