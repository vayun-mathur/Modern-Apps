plugins {
    id("common-conventions-wear")
}

launcherIcon {
    symbol = "calendar_month"
}

android {
    // The Wearable Data Layer only connects apps that share a package name and
    // signing certificate, so this module MUST ship as the phone app's package:
    // same applicationId (below) and the same signing config the convention
    // plugins already apply to both (debug key for dev, release key for
    // release). The namespace only names this module's own R/resources.
    namespace = "com.vayunmathur.calendar.wear"
    defaultConfig {
        applicationId = "com.vayunmathur.calendar"
        // Convention defaults to 30, but :library:log (pulled in by the wear
        // convention) requires 31, so the watch floor is 31.
        minSdk = 31
    }
}

dependencies {
    // Watch side of the Wear OS sync (see domain/WearCalendarProtocol.kt, the
    // copy of the phone protocol it must stay in step with).
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":library:wearlink"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
