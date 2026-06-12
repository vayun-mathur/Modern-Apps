plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.launcher"
    }
}

dependencies {
    implementation("androidx.appsearch:appsearch:1.1.0-alpha06")
    implementation("androidx.appsearch:appsearch-local-storage:1.1.0-alpha06")
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.foundation)
    implementRoom(libs)
}
