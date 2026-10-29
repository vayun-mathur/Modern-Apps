plugins {
    id("common-conventions-app")
    // Store-listing screenshots come from Compose previews (src/screenshotTest).
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "lock"
}

android {
    defaultConfig {
        versionCode = 20261028
        versionName = "v2.6.11b"
        applicationId = "com.vayunmathur.securemail"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }
}

dependencies {
    // Custom REST + WebSocket relay to mail.maemail.org (dynamic host → SYSTEM trust).
    implementation(project(":library:network"))
    // Biometric gate before the Keystore-wrapped PGP private key is unwrapped.
    implementation(project(":library:biometric"))
    // Periodic inbox poll as a backstop behind the live socket.
    implementation(project(":library:work"))
    implementation(project(":library:room"))
    implementRoom(libs)
    // Address token + onboarded flag.
    implementation(libs.androidx.datastore.preferences)
    // OpenPGP via the native Rust core (rPGP in libe2ee_pqc.so). No BouncyCastle.
    implementation(project(":library:e2ee-p2p"))
}
