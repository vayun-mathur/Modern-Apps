plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:files:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "folder"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    // Zip/unzip workers now use java.io.File + java.util.zip – no okio needed
    implementation(libs.androidx.work.runtime.ktx)
    // Image thumbnails for image files in the browser (AsyncImage).
    implementation(project(":library:image"))
    // collectAsStateWithLifecycle in the directory binders (as in :auto).
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation("androidx.work:work-testing:${libs.versions.workRuntimeKtx.get()}")
}
