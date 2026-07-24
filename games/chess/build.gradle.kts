plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "chess"
}

android {
    defaultConfig {
        versionCode = 20260723
        versionName = "v2.6.1"
        applicationId = "com.vayunmathur.games.chess"
    }
    androidResources {
        noCompress += "nnue"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation("com.github.vayun-mathur:Stockfish-Library:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
