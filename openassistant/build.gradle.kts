plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260413
        versionName = "v2.3.2"
        applicationId = "com.vayunmathur.openassistant"
    }
}

dependencies {
    // room
    implementRoom(libs)

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(libs.coil.compose)

    // ai
    implementation(libs.litertlm.android)

    // markdown
    implementation(libs.compose.markdown)
}