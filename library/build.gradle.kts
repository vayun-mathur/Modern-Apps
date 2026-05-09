plugins {
    id("common-conventions-library")
    alias(libs.plugins.ksp)
}

dependencies {
    // navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.adaptive.navigation3)

    // glance widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    //reorderable
    implementation(libs.reorderable)

    // room
    implementRoom(libs)
    api("net.zetetic:sqlcipher-android:4.14.1")
    implementation("androidx.sqlite:sqlite:2.6.2")

    // work
    implementation(libs.androidx.work.runtime.ktx)

    // datastore
    implementation(libs.androidx.datastore.preferences)

    api(libs.material)
}