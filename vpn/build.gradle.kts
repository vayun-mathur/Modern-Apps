plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:vpn:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "vpn_lock"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.vpn"
        minSdk = 31 // required for VpnService with per-app config etc (keep same as others)
    }
    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

rustNativeLib("vpn_wireguard", "vpn")

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.datastore.preferences)
    // User-supplied VPN endpoint: cannot pin, must use platform SYSTEM trust; document escape hatch.
    implementation(project(":library:network"))
}
