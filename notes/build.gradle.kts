plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        versionCode = 20260625
        versionName = "v2.5.6"
        applicationId = "com.vayunmathur.notes"
    }
}

dependencies {
    implementRoom(libs)
    implementation(libs.reorderable)
    implementation("androidx.compose.material:material-icons-extended")
}