plugins {
    id("common-conventions-app")
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
    packaging {
        resources.excludes += "META-INF/INDEX.LIST"
    }
}

dependencies {
    implementation(project(":library:biometric"))
    implementRoom(libs)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.credentials.lib)
    implementation(libs.androidx.autofill)
    implementation(libs.keepassjava2.dom)
    implementation(libs.bouncycastle)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.bouncycastle)
}