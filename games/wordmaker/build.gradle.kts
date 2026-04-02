plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
}