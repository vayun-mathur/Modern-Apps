// build-logic/src/main/kotlin/common-conventions.gradle.kts
plugins {
    id("com.android.application")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.okio)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose UI (BOM Managed)
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
}
