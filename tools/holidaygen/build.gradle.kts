// Host-only JVM tool (NOT shipped in any app). Generates bundled holiday JSON
// assets for the calendar app using Jollyday, which runs fine on a desktop JVM
// but isn't Android-compatible (XML/StAX). Run: ./gradlew :tools:holidaygen:run
plugins {
    application
}

dependencies {
    implementation("de.focus-shift:jollyday-jackson:1.5.2")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")
}

application {
    mainClass.set("HolidayGen")
}

// Emit assets relative to the repo root.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
