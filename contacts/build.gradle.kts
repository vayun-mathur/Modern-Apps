plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:contacts:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "person"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.contacts"
    }
    // vCard parser tests call the real Log facade on the JVM: without this every
    // android.util stub throws "not mocked". Same flag :maps sets for its
    // side-file reader tests.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    // VCF export/import now uses java.io BufferedReader/Writer – no okio needed
    implementation(libs.libphonenumber)
    implementation(project(":library:wearlink"))
    implementation(libs.androidx.work.runtime.ktx)
    // Wear OS companion (:contacts-wear, same applicationId + signing): Data Layer sync.
}
