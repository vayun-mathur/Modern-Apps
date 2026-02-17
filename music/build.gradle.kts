android {
    defaultConfig {
        applicationId = "com.vayunmathur.music"
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {

    implementation(libs.androidx.navigation3.runtime)

    // room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
}