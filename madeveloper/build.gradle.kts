plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "code"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.madeveloper"
    }
}

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:format"))
    implementation(project(":library:work"))
}
