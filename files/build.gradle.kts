plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260506
        versionName = "v2.4.4"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
}
