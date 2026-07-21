plugins {
    id("common-conventions-library")
}

android {
    namespace = "org.pjsip.pjsua2"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // PJSIP Java bindings have no external dependencies beyond Android SDK
}
