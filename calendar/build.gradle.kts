plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260321
        versionName = "v2.2.1"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
}