plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260419
        versionName = "v2.4.0"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
}
