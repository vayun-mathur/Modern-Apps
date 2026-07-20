plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "music_note"
}

android {
    defaultConfig {
        versionCode = 20260718
        versionName = "v2.6.0"
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
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.coil.compose)
}