plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

android {
    defaultConfig {
        versionCode = 20260706
        versionName = "v2.5.7"
        applicationId = "com.vayunmathur.games.chess"
        // Build Stockfish from source for arm64 only (matches the prior prebuilt .so).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Compile the Stockfish-Library git submodule into libstockfish.so at build time
    // instead of shipping a prebuilt binary.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
