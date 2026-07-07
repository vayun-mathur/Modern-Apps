plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260706
        versionName = "v2.5.7"
        applicationId = "com.vayunmathur.music"
    }
}

metadataScreenshots {
    permissions.add("android.permission.READ_MEDIA_AUDIO")
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.jaudiotagger)

    implementRoom(libs)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.coil.compose)
}