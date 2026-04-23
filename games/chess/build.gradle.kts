plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260422
        versionName = "v2.4.1"
        applicationId = "com.vayunmathur.games.chess"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}