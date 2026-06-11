plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games"
    }
}

dependencies {
    implementation(project(":library"))
    implementRoom(libs)
}
