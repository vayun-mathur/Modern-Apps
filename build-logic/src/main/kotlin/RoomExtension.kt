import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.configure

fun DependencyHandlerScope.implementRoom(libs: org.gradle.accessors.dm.LibrariesForLibs) {
    add("implementation", libs.androidx.room3.runtime)
    add("ksp", libs.androidx.room3.compiler)
}

/**
 * Every Room module exports its schema to `<module>/schemas` (committed, so
 * migrations can be tested against the actual previous schema). Wired once
 * here instead of a per-module `ksp { arg("room.schemaLocation", ...) }`
 * block (only `:todo` had one, and the other committed schema dirs went
 * stale). Configuration-cache safe: resolves the project dir eagerly and
 * captures only the resulting String.
 */
fun Project.wireRoomSchemaLocation() {
    val schemaDir = layout.projectDirectory.dir("schemas").asFile.absolutePath
    pluginManager.withPlugin("com.google.devtools.ksp") {
        extensions.configure<KspExtension> {
            arg("room.schemaLocation", schemaDir)
        }
    }
}
