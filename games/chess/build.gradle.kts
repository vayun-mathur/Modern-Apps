plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

android {
    defaultConfig {
        versionCode = 20260707
        versionName = "v2.5.7b"
        applicationId = "com.vayunmathur.games.chess"
    }
}

dependencies {
    // Prebuilt Stockfish engine (native .so + Kotlin API), built once on JitPack.
    implementation("com.github.vayun-mathur:Stockfish-Library:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
