plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260430
        versionName = "v2.4.3"
        minSdk = 35
        applicationId = "com.vayunmathur.pdf"
    }
}

dependencies {
    // pdf
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.androidx.pdf.compose)
    implementation(libs.androidx.pdf.ink)
    implementation(libs.androidx.pdf.document.service)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
}