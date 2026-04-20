plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260419
        versionName = "v2.4.0"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
}