plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260430
        versionName = "v2.4.3"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
}
