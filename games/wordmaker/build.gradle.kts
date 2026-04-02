plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260401
        versionName = "v2.3.0"
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
}