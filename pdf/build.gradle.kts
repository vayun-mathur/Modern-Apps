android {
    defaultConfig {
        minSdk = 35
        applicationId = "com.vayunmathur.pdf"
        versionCode = 20
        versionName = "2.0"
    }
}

dependencies {
    // pdf
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.androidx.pdf.compose)
    implementation(libs.androidx.pdf.document.service)
}