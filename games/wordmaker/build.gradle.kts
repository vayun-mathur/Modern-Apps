android {
    defaultConfig {
        versionCode = 20260317
        versionName = "v2.2.0"
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    implementation(libs.kotlin.csv.jvm)
    implementation(libs.androidx.datastore.preferences)
}