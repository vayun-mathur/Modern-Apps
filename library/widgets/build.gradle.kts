plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    api(libs.androidx.glance)
    api(libs.androidx.glance.appwidget)
    api(libs.androidx.glance.material3)
    api(libs.androidx.work.runtime.ktx)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.glance.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
}
