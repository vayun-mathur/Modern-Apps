plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:sudoku:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "grid_3x3"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.sudoku"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(libs.androidx.datastore.preferences)
}
