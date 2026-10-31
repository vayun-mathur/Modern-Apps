plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:todo:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "checklist"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.todo"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:widgets"))
    testImplementation(libs.androidx.sqlite.bundled.jvm)
}
