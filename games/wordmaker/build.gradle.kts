android {
    defaultConfig {
        versionCode = 20260310
        versionName = "v2.1.0"
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    implementation(libs.kotlin.csv.jvm)
    implementation(libs.androidx.datastore.preferences)
}