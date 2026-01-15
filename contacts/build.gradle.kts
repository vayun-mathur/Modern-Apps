android {
    defaultConfig {
        applicationId = "com.vayunmathur.contacts"
        versionCode = 21
        versionName = "v2.0.0"
    }
}

dependencies {
    // External Libraries
    implementation(libs.libphonenumber)

    // Navigation 3
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.compose.adaptive.navigation3)
}