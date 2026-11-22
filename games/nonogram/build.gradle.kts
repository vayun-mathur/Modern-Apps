plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:nonogram:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "grid_view"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.nonogram"
    }
}

// One JVM per test class, overriding the non-Rust batching
// (forkEvery = 20) the convention applies to this module: every suite
// here shares the process-wide NonogramDataStore file, and a leaked
// NonogramViewModel keeps collecting it — in a shared JVM its
// regeneration work queues ahead of the next class's load and, under
// CI CPU contention, the whole fork (and with it the task's 45-minute
// budget) wedges behind one class. 13 classes is cheap to fork per
// class; the batched classes were never the throughput problem.
// Registered in afterEvaluate (and so after the convention plugin's
// own afterEvaluate, whose configureEach otherwise overwrites this at
// task realization — verified: without the deferral the task ran
// forkEvery = 20, maxParallelForks = 3). maxHeapSize from the
// convention's wiring stays.
afterEvaluate {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        forkEvery = 1
        // Serial class JVMs on purpose: the convention's
        // (cores / 2) fork count puts 2-3 Robolectric JVMs on a low-core CI
        // box at once, and the generator-heavy ViewModel suite's load race
        // (a fresh board's generation cancelled/suspended behind the other
        // forks' work) flips under that contention — its task then burns
        // the whole 45-minute CI budget while every worker sits idle. One
        // fork at a time gives the running JVM the whole box; 13 small
        // classes still finish in a few minutes.
        maxParallelForks = 1
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
}
