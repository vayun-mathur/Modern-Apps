plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260427
        versionName = "v2.4.2"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
}
