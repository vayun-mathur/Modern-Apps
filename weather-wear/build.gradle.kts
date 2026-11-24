plugins { id("common-conventions-wear") }
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
