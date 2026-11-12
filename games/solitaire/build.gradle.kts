import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:solitaire:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "playing_cards"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.solitaire"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}

// Offline generator for src/main/assets/solvable_deals.bin (AGENTS.md, "Solvable deals").
// Inert unless -PsolitaireSeedGen=<output dir> is given; then the unit-test JVM gets the
// generator's settings and a heap big enough for one solver per thread.
val solitaireSeedGen = providers.gradleProperty("solitaireSeedGen")
val solitaireSeedGenTarget = providers.gradleProperty("solitaireSeedGenTarget")
val solitaireSeedGenThreads = providers.gradleProperty("solitaireSeedGenThreads")
val solitaireSeedGenGroups = providers.gradleProperty("solitaireSeedGenGroups")
val solitaireSeedGenHeap = providers.gradleProperty("solitaireSeedGenHeap")
tasks.withType<Test>().configureEach {
    if (solitaireSeedGen.isPresent) {
        systemProperty("solitaire.seedgen.out", solitaireSeedGen.get())
        systemProperty("solitaire.seedgen.target", solitaireSeedGenTarget.getOrElse("10000"))
        systemProperty("solitaire.seedgen.threads", solitaireSeedGenThreads.getOrElse("0"))
        systemProperty("solitaire.seedgen.groups", solitaireSeedGenGroups.getOrElse(""))
        maxHeapSize = solitaireSeedGenHeap.getOrElse("40g")
        // -ea and coverage probes (below) in the solvers' hot loops, shared across
        // threads, slow the run by an order of magnitude; neither means anything here.
        enableAssertions = false
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    }
}
// Registered once the coverage plugin is in, so this runs after it adds its task extension.
plugins.withId("jacoco") {
    tasks.withType<Test>().configureEach {
        if (solitaireSeedGen.isPresent) extensions.getByType(JacocoTaskExtension::class.java).isEnabled = false
    }
}
