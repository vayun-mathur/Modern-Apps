plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.notes"
    }
}

dependencies {
    implementRoom(libs)
    implementation(libs.reorderable)
    implementation(libs.coil.compose)
    implementation("androidx.compose.material:material-icons-extended")
}