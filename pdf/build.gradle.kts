android {
    defaultConfig {
        minSdk = 35
        applicationId = "com.vayunmathur.pdf"
        versionCode = 22
        versionName = "v2.0.0"
    }
}

dependencies {
    // pdf
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.androidx.pdf.compose)
    implementation(libs.androidx.pdf.document.service)
}