android {
    defaultConfig {
        versionCode = 20260317
        versionName = "v2.2.0"
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
}