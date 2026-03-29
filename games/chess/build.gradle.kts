plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260329
        versionName = "v2.2.3"
        applicationId = "com.vayunmathur.games.chess"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}