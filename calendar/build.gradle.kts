plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260430
        versionName = "v2.4.3"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
}