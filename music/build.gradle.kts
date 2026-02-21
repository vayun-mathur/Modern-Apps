android {
    defaultConfig {
        applicationId = "com.vayunmathur.music"
    }
}

dependencies {

    implementation(libs.androidx.navigation3.runtime)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
}