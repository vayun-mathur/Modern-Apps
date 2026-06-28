plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260625
        versionName = "v2.5.6"
        applicationId = "com.vayunmathur.youpipe"
    }
}

dependencies {
    implementation(libs.coil.compose)
    implementation(libs.newpipeextractor)

    implementRoom(libs)

    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))
    implementation(libs.androidx.compose.runtime.livedata)

    implementation(project(":library:network"))
}