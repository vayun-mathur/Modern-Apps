android {
    defaultConfig {
        versionCode = 20260317
        versionName = "v2.2.0"
        applicationId = "com.vayunmathur.contacts"
    }
}

dependencies {
    // External Libraries
    implementation(libs.libphonenumber)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
}