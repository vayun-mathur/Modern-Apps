plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
        versionCode = 20260615
        versionName = "v2.5.4"
        applicationId = "com.vayunmathur.games.chess"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    implementation(project(":library:downloadservice"))
}