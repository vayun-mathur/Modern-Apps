// :whatsapp-signal — provides the CLASSIC X3DH libsignal
// (org.whispersystems.libsignal.*) that the WhatsApp bridge in :messages
// needs. The Signal bridge's libsignal-android 0.86 dropped X3DH
// (PQXDH-only), but WhatsApp companion sessions still require it, so this
// module bundles the old pure-Java implementation under its original
// package. Its protobuf runtime is RELOCATED to
// com.vayunmathur.messages.shadedproto so it doesn't collide with the
// app's protobuf 4.x.
//
// Built as a fat/relocated jar via the Shadow plugin and exposed to
// :messages through the consumable "shaded" configuration:
//   implementation(project(path = ":whatsapp-signal", configuration = "shaded"))

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.0.0"
}

dependencies {
    // Classic X3DH Signal protocol. Transitively pulls curve25519-java:0.5.0
    // and protobuf-javalite:3.10.0, both bundled into the shaded jar.
    implementation("org.whispersystems:signal-protocol-java:2.8.1")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("shaded")
    // Relocate the bundled protobuf so it can't clash with the app's
    // protobuf 4.x on the :messages classpath. libsignal's own generated
    // classes are rewritten to the relocated package by Shadow.
    relocate("com.google.protobuf", "com.vayunmathur.messages.shadedproto")
    // Drop protobuf's descriptor resources (google/protobuf/*.proto). The
    // runtime classes are relocated, so these are dead weight — and if left
    // in they collide with the app's protobuf-java at
    // :messages:mergeDevJavaResource ("2 files found with path
    // 'google/protobuf/type.proto'").
    exclude("google/protobuf/**")
    exclude("**/*.proto")
}

// Consumable configuration :messages depends on by name.
val shaded: Configuration = configurations.create("shaded") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add("shaded", tasks.named<ShadowJar>("shadowJar"))
}
