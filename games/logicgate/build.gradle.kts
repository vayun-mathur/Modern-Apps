plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:logicgate:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "memory"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.logicgate"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
