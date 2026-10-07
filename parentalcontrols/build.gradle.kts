plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "supervised_user_circle"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.parentalcontrols"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))

    // @SystemApi supervision classes: SupervisionAppService (which the platform binds), plus
    // SupervisionManager and the Policy hierarchy used to write enforcement decisions.
    // compileOnly - these exist in the framework at runtime and must not be packaged.
    compileOnly(project(":library:supervision-stubs"))
}
