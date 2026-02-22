android {
    defaultConfig {
        versionCode = 20260221
        versionName = "v2.0.0"
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    implementation(libs.kotlin.csv.jvm)
    implementation(libs.androidx.datastore.preferences)
}