plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260416
        versionName = "v2.3.3"
        applicationId = "com.vayunmathur.games.chess"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}