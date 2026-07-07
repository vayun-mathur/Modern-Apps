plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

android {
    defaultConfig {
        versionCode = 20260706
        versionName = "v2.5.7"
        applicationId = "com.vayunmathur.camera"
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("org.opencv:opencv:4.10.0")
}
