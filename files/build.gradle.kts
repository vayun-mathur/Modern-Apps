plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260625
        versionName = "v2.5.6"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
}
