plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "play_arrow"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.youpipe"
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(libs.coil.compose)
    implementation(project(":youpipe:extractor"))
    implementation(libs.quickjs.kt)
    implementation(libs.newpipe.nanojson)
    implementation(libs.androidx.webkit)

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":library:work"))
    implementation(libs.androidx.compose.runtime.livedata)

    implementation(project(":library:network"))
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}