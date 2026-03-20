plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.music"
    }
}

dependencies {

    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.jaudiotagger)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
}