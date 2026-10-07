plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:minesweeper:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "flag"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.games.minesweeper"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
