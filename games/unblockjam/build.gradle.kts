plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.unblockjam"
    }
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)
}