plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260321
        versionName = "v2.2.1"
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