plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "grid_on"
}

android {
    defaultConfig {
        versionCode = 20260723
        versionName = "v2.6.1"
        applicationId = "com.vayunmathur.games.unblockjam"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
