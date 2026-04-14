plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260413
        versionName = "v2.3.2"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
}