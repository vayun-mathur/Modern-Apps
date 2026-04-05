plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.compose.gradle)
    implementation(libs.kotlin.serialization.gradle)
    implementation(libs.symbol.processing.gradle.plugin)
    implementation(libs.gradle)
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    implementation("com.autonomousapps.dependency-analysis:com.autonomousapps.dependency-analysis.gradle.plugin:3.6.1")
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
