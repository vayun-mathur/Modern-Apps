plugins {
    id("common-conventions-app")
}

android {
    namespace = "com.vayunmathur.email"
    defaultConfig {
        versionCode = 20260625
        versionName = "v2.5.6"
        applicationId = "com.vayunmathur.email"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            // Ensure JavaMail provider descriptors are kept
            pickFirsts += "META-INF/javamail.providers"
            pickFirsts += "META-INF/javamail.default.providers"
            pickFirsts += "META-INF/javamail.default.address.map"
            pickFirsts += "META-INF/mailcap"
            pickFirsts += "META-INF/mailcap.default"
        }
    }
}

dependencies {
    implementation(libs.jakarta.mail)
    implementation(libs.jakarta.activation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(project(":library:widgets"))
}
