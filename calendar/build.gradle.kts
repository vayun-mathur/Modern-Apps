plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260427
        versionName = "v2.4.2"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
}