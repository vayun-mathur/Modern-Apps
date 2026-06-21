plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260620
        versionName = "v2.5.5"
        applicationId = "com.vayunmathur.games.unblockjam"
    }
}

dependencies {
    implementation(project(":library:ui"))
}