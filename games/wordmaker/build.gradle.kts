android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.wordmaker"
        versionCode = 5
        versionName = "1.4"
    }
}

dependencies {
    implementation(libs.kotlin.csv.jvm)
    implementation(libs.androidx.datastore.preferences)
}