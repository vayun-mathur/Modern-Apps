plugins {
    id("common-conventions-app")
}

android {
    defaultConfig {
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
            path = file("src/main/cpp/Stockfish-Library/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
