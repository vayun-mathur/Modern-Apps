plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:chess:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "chess"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.games.chess"
    }
    androidResources {
        // Maia3's weights are read in place through an `AssetFileDescriptor`, and
        // `AssetManager.openFd` throws for a deflated entry. Costs nothing on download size:
        // fp16 weights barely compress.
        noCompress += "maml"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:ml"))
}
