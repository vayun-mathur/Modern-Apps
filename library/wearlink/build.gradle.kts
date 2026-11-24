plugins {
    id("common-conventions-library")
}

android {
    namespace = "com.vayunmathur.library.wearlink"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
