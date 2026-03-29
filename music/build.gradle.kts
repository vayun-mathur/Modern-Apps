plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260329
        versionName = "v2.2.3"
        applicationId = "com.vayunmathur.music"
    }
}

dependencies {

    implementation(libs.jaudiotagger)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
}