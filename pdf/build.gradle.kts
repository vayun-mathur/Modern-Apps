android {
    defaultConfig {
        versionCode = 20260221
        versionName = "v2.0.0"
        minSdk = 35
        applicationId = "com.vayunmathur.pdf"
    }
}

dependencies {
    // pdf
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.androidx.pdf.compose)
    implementation(libs.androidx.pdf.document.service)
}