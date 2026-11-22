plugins {
    // No version: build-logic's `kotlin-dsl` plugin already puts the Kotlin
    // Gradle plugin on the root build's classpath, and re-requesting it with a
    // version fails because the classpath copy has no version to check against.
    id("org.jetbrains.kotlin.jvm")
}

// A plain JVM module: lint checks run inside Lint's own JVM, not on device, so
// this must not be an Android library.
dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint.tests)
    testImplementation(libs.kotlin.test.junit)
}

kotlin {
    compilerOptions {
        // Lint loads checks reflectively; the registry below declares the API
        // version it was built against.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Lint-Registry-v2" to "com.vayunmathur.lint.ModernAppsIssueRegistry",
        )
    }
}
