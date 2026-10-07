plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "emergency"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.emergency"
    }
}

dependencies {
    // Read-only Personal Health Record (FHIR) access, to surface allergies and current
    // medications from Health Connect on the view screen. Same client the health app uses.
    implementation(libs.androidx.connect.client)

    // Contact-URI serialize/parse tests call android.net.Uri.parse, which the unit-test stub
    // android.jar does not implement ("not mocked"). Robolectric supplies the framework
    // shadows so those round-trips can be asserted on the JVM; same reason pdf uses it.
    testImplementation(libs.robolectric)
}
