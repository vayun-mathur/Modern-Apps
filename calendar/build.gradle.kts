android {
    defaultConfig {
        versionCode = 20260317
        versionName = "v2.2.0"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)

    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
}