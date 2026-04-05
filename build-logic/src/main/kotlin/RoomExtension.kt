import gradle.kotlin.dsl.accessors._b80debccebb9ece8e914e516f0647706.implementation
import gradle.kotlin.dsl.accessors._b80debccebb9ece8e914e516f0647706.ksp
import org.gradle.kotlin.dsl.DependencyHandlerScope

fun DependencyHandlerScope.implementRoom(libs: org.gradle.accessors.dm.LibrariesForLibs) {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}

fun DependencyHandlerScope.implementKtor(libs: org.gradle.accessors.dm.LibrariesForLibs) {
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
}