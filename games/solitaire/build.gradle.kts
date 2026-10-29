plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:solitaire:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "playing_cards"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.games.solitaire"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
