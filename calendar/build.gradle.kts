plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:calendar:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "calendar_month"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))
    // Phone side of the Wear OS sync (sync/WearCalendarSync): capability
    // discovery and snapshot publish to :calendar-wear, which shares this
    // app's applicationId and signing certificate.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.androidx.glance.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)

}
