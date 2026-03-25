plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260325
        versionName = "v2.2.2"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
}