plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "chess"
}

android {
    defaultConfig {
        versionCode = 20260718
        versionName = "v2.6.0"
        applicationId = "com.vayunmathur.games.chess"
    }
    androidResources {
        // Store the Stockfish NNUE uncompressed so it can be read in place from
        // the APK via AssetManager.openFd() (fd + offset), avoiding a copy into
        // internal storage on first launch.
        noCompress += "nnue"
    }
}

dependencies {
    // Prebuilt Stockfish engine (native .so + Kotlin API), built once on JitPack.
    // 1.1.0 adds the "fd:<fd>:<offset>:<length>" EvalFile scheme used below.
    implementation("com.github.vayun-mathur:Stockfish-Library:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
