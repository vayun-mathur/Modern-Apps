plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260530
        versionName = "v2.5.0"
        applicationId = "com.vayunmathur.music"
    }
}

dependencies {
    implementation(project(":library:downloadservice"))

    implementation(libs.jaudiotagger)

    implementRoom(libs)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.coil.compose)
}