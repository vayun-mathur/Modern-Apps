plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260413
        versionName = "v2.3.2"
        applicationId = "com.vayunmathur.music"
    }
}

dependencies {

    implementation(libs.jaudiotagger)

    implementRoom(libs)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.coil.compose)
}