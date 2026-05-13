import java.util.Properties

plugins {
    id("common-conventions-app")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "com.vayunmathur.crypto"
        buildConfigField("String", "JUPITER_API_KEY", "\"${localProps.getProperty("JUPITER_API_KEY", "")}\"")
    }
}

dependencies {
    // ktor
    implementation(project(":library:network"))

    // solana
    implementation(libs.sol4k)
}