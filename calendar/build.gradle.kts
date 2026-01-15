android {
    defaultConfig {
        applicationId = "com.vayunmathur.calendar"
        versionCode = 21
        versionName = "v2.0.0"
    }
}

dependencies {
    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // Glance app widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
}