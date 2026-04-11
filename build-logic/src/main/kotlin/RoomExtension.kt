import gradle.kotlin.dsl.accessors._fbec4b978d86c39e55b50a6ada210591.implementation
import gradle.kotlin.dsl.accessors._fbec4b978d86c39e55b50a6ada210591.ksp
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