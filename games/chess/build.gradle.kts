plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260401
        versionName = "v2.3.0"
        applicationId = "com.vayunmathur.games.chess"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}