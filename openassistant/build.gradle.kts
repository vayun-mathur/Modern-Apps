plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.openassistant"
    }
}

dependencies {
    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(libs.coil.compose)

    // ai
    implementation(libs.litertlm.android)

    // markdown
    implementation(libs.compose.markdown)
}