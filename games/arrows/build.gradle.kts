plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:arrows:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "double_arrow"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.arrows"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
}

// Per-class JVM isolation for unit tests, overriding the shared
// non-Rust batching (forkEvery = 20 in build-logic's RustNative.kt).
// Proven pollution, not a precaution: every class here drives the
// process-wide "settings" preferences DataStore, and classes are
// batched into one JVM per Gradle fork. ArrowsViewModelTest passes
// alone on taskset -c 0-4 and passes batched with its DataStore /
// Pages / Achievements neighbours, but in the full module batch a
// neighbour's leftover store state and actors leave its board won
// or its next-level load starved (CI run 200d0e1f, and reproduced
// locally under the same taskset shape). The ViewModel test classes
// already clear every ViewModel they create; what they cannot clear
// is another class's actors in the shared sandbox. One JVM per class
// makes the store each class sees its own. (Registered in
// afterEvaluate so it runs after the convention plugin's own
// afterEvaluate batching and wins.)
afterEvaluate {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        forkEvery = 1
    }
}
