plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "description"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.office"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native ODF formula engine + document CRDT (Rust). See office/src/main/rust/.
rustNativeLib("office_engine", "office")

dependencies {
    implementation(project(":library:network"))
    implementation(project(":library:e2ee-p2p"))

    // Provides a real XmlPullParser implementation for JVM unit tests (Android's is a stub).
    testImplementation(libs.kxml2)
}
