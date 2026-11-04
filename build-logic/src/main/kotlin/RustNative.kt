import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import java.io.File
import java.util.Properties
import javax.inject.Inject

/**
 * Shared build service that caps concurrent cargoBuild executions to 1.
 * This prevents parallel invocations of `rustup toolchain install` triggered
 * implicitly by cargo when rust-toolchain.toml pins 1.97.0, which is not
 * concurrency-safe and produces "Directory not empty (os error 39)",
 * "cargo not applicable", "failed to install component bin/rust-gdb" errors.
 */
abstract class RustToolchainLock : BuildService<BuildServiceParameters.None>

/**
 * Maximum lines per Rust source file, mirroring the Kotlin `FileLength`
 * rule (which cannot see Rust — Android lint only visits Kotlin/Java).
 * Long files hide the same brace-imbalance breakage and grab-bag rot;
 * splitting along module lines keeps each file reviewable.
 *
 * Enforced as a build failure, like `ToastUsage`: files already over the
 * limit must be split before the owning module builds.
 */
const val RUST_FILE_LENGTH_LIMIT = 500

/**
 * Exemptions by filename: long by design, not by accretion — same rationale
 * as `LENGTH_EXEMPT_FILES` in the Kotlin rule. Test fixtures and examples
 * are long by nature; generated files are not hand-written.
 */
private val RUST_LENGTH_EXEMPT_FILES = setOf(
    "route_diff.rs", // maps example harness with inline fixtures
)

private fun isRustLengthExempt(path: String): Boolean =
    path.contains("/tests/") || path.contains("/test_fixtures/") ||
        path.contains("/examples/") || path.contains("/generated/") ||
        path.contains("/third_party/")

/**
 * An Android ABI paired with the Rust target triple and the NDK clang-wrapper prefix
 * used to cross-compile for it.
 *
 * @param clangPrefix deliberately separate from [triple]: for armeabi-v7a the NDK ships
 *   `armv7a-linux-androideabi<api>-clang` (note the `a`) while the Rust triple is
 *   `armv7-linux-androideabi`, so deriving one from the other silently misses the wrapper.
 */
data class RustAbi(val abiDir: String, val triple: String, val clangPrefix: String)

/**
 * Compiles GLSL entry points to `<name>.spv` with the NDK's glslc. Crates load the output with
 * `include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../build/spirv/<name>.spv"))`,
 * so standalone cargo (./check, ./test) needs this task to have run first.
 */
abstract class GlslcCompile @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    /** Whole shader dir, so `#include`d files invalidate the task too. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shaderDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val glslc: Property<String>

    @get:Input
    abstract val glslcArgs: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val compiler = File(glslc.get())
        if (!compiler.isFile) {
            throw GradleException("glslc not found at $compiler. Install NDK $NDK_VERSION.")
        }
        val shaders = sources.files.sortedBy { it.name }
        if (shaders.isEmpty()) throw GradleException("No shaders matched in ${shaderDir.get()}")
        val out = outputDir.get().asFile
        // Wipe first so a deleted shader can't leave a stale .spv behind for include_bytes!.
        out.deleteRecursively()
        out.mkdirs()
        for (src in shaders) {
            val spv = File(out, "${src.name}.spv")
            execOps.exec {
                commandLine(compiler.path, *glslcArgs.get().toTypedArray(), "-o", spv.path, src.path)
            }
        }
    }
}

private val KNOWN_RUST_ABIS = listOf(
    RustAbi(ABI_ARM64, "aarch64-linux-android", "aarch64-linux-android"),
    RustAbi(ABI_ARMV7, "armv7-linux-androideabi", "armv7a-linux-androideabi"),
    // Emulator-only; see [ABI_X86_64]. Never packaged by default.
    RustAbi(ABI_X86_64, "x86_64-linux-android", "x86_64-linux-android"),
)

/**
 * A `path = "..."` or workspace-inherited reference found in one manifest's
 * dependency tables.
 */
private data class CargoDepRefs(val paths: List<String>, val workspaceNames: List<String>)

private val cargoPathValue = Regex("""\bpath\s*=\s*("([^"]*)"|'([^']*)')""")
private val cargoDottedWorkspace = Regex("""^([A-Za-z0-9_-]+)\.workspace\s*=\s*true\b""")
private val cargoInlineWorkspace =
    Regex("""^[A-Za-z0-9_-]+\s*=\s*\{[^}]*?\bworkspace\s*=\s*true\b""")
private val cargoInlineWorkspaceName = Regex("""^([A-Za-z0-9_-]+)\s*=""")

private fun stripTomlComment(line: String): String {
    var inDouble = false
    var inSingle = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && !inSingle && (i == 0 || line[i - 1] != '\\') -> inDouble = !inDouble
            c == '\'' && !inDouble -> inSingle = !inSingle
            c == '#' && !inDouble && !inSingle -> return line.substring(0, i)
        }
        i++
    }
    return line
}

/**
 * Dependency refs in a crate manifest: inline `path = "..."` entries plus
 * `<name>.workspace = true` / `<name> = { workspace = true ... }` entries.
 * Only dependency tables are scanned (`[dependencies]`,
 * `[dev-dependencies]`, `[build-dependencies]`, `[target.*.dependencies]`,
 * `[workspace.dependencies]`), so `[[bin]]`/`[lib]`/`[[example]]`
 * `path = "src/..."` file entries never surface here — and the directory
 * check in [findRustPathDepDirs] rejects them anyway.
 */
private fun parseCargoDepRefs(manifestText: String): CargoDepRefs {
    val paths = mutableListOf<String>()
    val workspaceNames = mutableListOf<String>()
    var inDeps = false
    for (rawLine in manifestText.lines()) {
        val line = stripTomlComment(rawLine).trim()
        if (line.startsWith("[")) {
            inDeps = line.startsWith("[dependencies") ||
                line.startsWith("[dev-dependencies") ||
                line.startsWith("[build-dependencies") ||
                line.startsWith("[target.") ||
                line.replace(" ", "") == "[workspace.dependencies]"
            continue
        }
        if (!inDeps || line.isEmpty()) continue
        cargoPathValue.find(line)?.let {
            paths += it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        cargoDottedWorkspace.find(line)?.let {
            workspaceNames += it.groupValues[1]
        }
        if (cargoInlineWorkspace.containsMatchIn(line)) {
            cargoInlineWorkspaceName.find(line)?.let {
                workspaceNames += it.groupValues[1]
            }
        }
    }
    return CargoDepRefs(paths, workspaceNames)
}

/** `name = { path = "...", ... }` entries of root `[workspace.dependencies]`. */
private fun parseWorkspacePathDeps(rootManifestText: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var inSection = false
    for (rawLine in rootManifestText.lines()) {
        val line = stripTomlComment(rawLine).trim()
        if (line.startsWith("[")) {
            inSection = line.replace(" ", "") == "[workspace.dependencies]"
            continue
        }
        if (!inSection || line.isEmpty()) continue
        if (cargoDottedWorkspace.containsMatchIn(line) ||
            cargoInlineWorkspace.containsMatchIn(line)
        ) {
            // `name.workspace = true` re-export: no path of its own.
            continue
        }
        val name = line.substringBefore("=").trim()
        if (!name.matches(Regex("[A-Za-z0-9_-]+"))) continue
        cargoPathValue.find(line)?.let {
            out[name] = it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
    }
    return out
}

/**
 * Reads a Cargo.toml through the file-contents provider so the configuration
 * cache tracks it: editing any visited manifest invalidates configuration.
 * Manifests outside the repo (defensive fallback) are read untracked.
 */
private fun Project.readCargoManifest(manifestFile: File): String {
    val canonical = manifestFile.canonicalFile
    fun relativeToBase(base: File): String? {
        val prefix = base.canonicalPath.trimEnd(File.separatorChar) + File.separatorChar
        return if (canonical.path.startsWith(prefix)) canonical.path.removePrefix(prefix) else null
    }
    relativeToBase(projectDir)?.let {
        return providers.fileContents(layout.projectDirectory.file(it)).asText.get()
    }
    relativeToBase(rootProject.projectDir)?.let {
        return providers.fileContents(rootProject.layout.projectDirectory.file(it)).asText.get()
    }
    return canonical.readText()
}

/** Stable per-crate input property name from the root-relative crate path. */
private fun pathDepPropertyName(rootDir: File, depDir: File): String {
    val rel = try {
        depDir.relativeTo(rootDir).invariantSeparatorsPath
    } catch (_: IllegalArgumentException) {
        depDir.canonicalPath
    }
    return "pathDep_" + rel.replace(Regex("[^A-Za-z0-9]+"), "_")
}

/**
 * Cross-crate path dependencies of the crate in [crateDir], e.g. euicc ->
 * library/jni-http, camera/measure -> library/vision, maps/library:map ->
 * library/tilecodec. Parsed generically from `path = "..."` entries (resolved
 * relative to the crate dir) plus `<name>.workspace = true` entries (resolved
 * against root `[workspace.dependencies]` paths), recursed with dedupe and a
 * cycle guard. Only paths resolving to a directory containing Cargo.toml are
 * accepted. Returns plain Files; callers build FileCollections from them so
 * only those (never the Project) cross into task actions.
 */
private fun Project.findRustPathDepDirs(crateDir: File): List<File> {
    val rootDir = rootProject.projectDir.canonicalFile
    val workspacePaths =
        parseWorkspacePathDeps(readCargoManifest(rootDir.resolve("Cargo.toml")))
    val canonicalCrate = crateDir.canonicalFile
    val visited = HashSet<File>()
    visited += canonicalCrate
    val found = mutableListOf<File>()
    val queue = ArrayDeque<File>()
    fun enqueue(base: File, rawPath: String) {
        val resolved = File(base, rawPath).canonicalFile
        if (!resolved.isDirectory || !resolved.resolve("Cargo.toml").isFile) return
        if (visited.add(resolved)) {
            found += resolved
            queue += resolved
        }
    }
    fun enqueueWorkspaceDep(name: String) {
        workspacePaths[name]?.let { enqueue(rootDir, it) }
    }
    val seed = parseCargoDepRefs(readCargoManifest(canonicalCrate.resolve("Cargo.toml")))
    seed.paths.forEach { enqueue(canonicalCrate, it) }
    seed.workspaceNames.forEach(::enqueueWorkspaceDep)
    while (queue.isNotEmpty()) {
        val dir = queue.removeFirst()
        val refs = parseCargoDepRefs(readCargoManifest(dir.resolve("Cargo.toml")))
        refs.paths.forEach { enqueue(dir, it) }
        refs.workspaceNames.forEach(::enqueueWorkspaceDep)
    }
    return found
}

/**
 * Registers the per-ABI cargo cross-compile of a Rust cdylib into `build/rustJniLibs`,
 * wired ahead of `preBuild`. This consolidates the ~80 lines of NDK-toolchain + cargo +
 * reproducible-build boilerplate that used to be copy-pasted across every native module
 * (pdf, camera, weather, passwords, e2ee-p2p). The caller still registers
 * `build/rustJniLibs` as a jniLibs source dir in its own `android { }` block.
 *
 * Prereq: `rustup target add` for every triple built (see `rust-toolchain.toml`).
 *
 * @param crate      cargo package lib name → produces `lib<crate>.so`
 * @param remapLabel `--remap-path-prefix` label baked in for reproducible builds
 *                   (defaults to [crate]).
 * @param srcDir     crate directory relative to the module (defaults to
 *                   `src/main/rust`). Namespaces the per-ABI task names by
 *                   [crate] so a module can build several crates.
 * @param extraAbis  ABIs to build in addition to [ABI_ARM64]. Opt-in per module so only
 *                   the modules that need a second ABI pay for the extra cargo build;
 *                   pass [ABI_ARMV7] for libraries consumed by an app that sets
 *                   `nativeAbis { armv7 = true }`.
 * @param features   Cargo features to enable via `--features`. Resolved to a plain
 *                   String list at configuration time so the build stays
 *                   configuration-cache compatible (only the strings cross into
 *                   the task action, never the Project).
 * @param shaders    Glob patterns under `$srcDir/shaders` to compile to SPIR-V into
 *                   `build/spirv` ahead of the cargo build (see [GlslcCompile]). Empty
 *                   means the crate has no Vulkan shaders. Only supported with the
 *                   default [srcDir], since crates locate `build/spirv` relative to it.
 * @param glslcArgs  Extra glslc flags, e.g. `--target-env=vulkan1.1`, `-O`.
 */
fun Project.rustNativeLib(
    crate: String,
    remapLabel: String = crate,
    extraAbis: List<String> = emptyList(),
    features: List<String> = emptyList(),
    srcDir: String = "src/main/rust",
    shaders: List<String> = emptyList(),
    glslcArgs: List<String> = emptyList(),
) {
    // Serialize cargoBuild across all Rust modules to avoid concurrent rustup installs.
    val rustLock = gradle.sharedServices.registerIfAbsent(
        "rustToolchainLock",
        RustToolchainLock::class.java
    ) {
        maxParallelUsages.set(1)
    }

    val androidApiLevel = 31

    // Resolved once per build, not once per native module: every
    // rustNativeLib caller shares the stashed value via the Gradle extra
    // cache. Plain String keeps the configuration cache happy.
    fun resolveSdkDirOnce(): String {
        val extra = gradle.extensions.extraProperties
        if (extra.has("modernAppsSdkDir")) return extra.get("modernAppsSdkDir") as String
        val dir = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
                Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
            }
            ?: error("Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)")
        extra.set("modernAppsSdkDir", dir)
        return dir
    }

    fun resolveSdkDir(): String = resolveSdkDirOnce()

    val cargoBin = "${System.getProperty("user.home")}/.cargo/bin"
    val ndkRoot = "${resolveSdkDir()}/ndk/$NDK_VERSION"
    val hostTag = when {
        OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        OperatingSystem.current().isLinux -> "linux-x86_64"
        OperatingSystem.current().isWindows -> "windows-x86_64"
        else -> error("Unsupported host OS for NDK toolchain")
    }
    val ndkBin = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/bin"
    val ndkSysroot = "$ndkRoot/toolchains/llvm/prebuilt/$hostTag/sysroot"

    // arm64-v8a always; anything else is opt-in via extraAbis.
    val rustAbis = (listOf(ABI_ARM64) + extraAbis).distinct().map { abiDir ->
        KNOWN_RUST_ABIS.firstOrNull { it.abiDir == abiDir }
            ?: error(
                "rustNativeLib($crate): unknown ABI '$abiDir'. " +
                    "Known: ${KNOWN_RUST_ABIS.joinToString { it.abiDir }}"
            )
    }

    // Config-cache compatible: snapshot to plain Strings at configuration time so
    // only the strings (never the Project) cross into the task actions below.
    val cargoFeatureArgs: List<String> =
        if (features.isNotEmpty()) listOf("--features", features.joinToString(",")) else emptyList()

    // Cargo profile chosen once at configuration time (plain String, config-cache
    // safe). Explicit `-Pmodernapps.rustProfile=fast|release` wins; otherwise fail
    // safe: `fast` only when every requested task is a dev/cargo task, else
    // `release` so an ambiguous build never ships a fast-profile .so.
    val devTaskPattern = Regex("(Dev|Debug)(?=[A-Z]|$)")
    val profile: String = providers.gradleProperty("modernapps.rustProfile").orNull?.let { requested ->
        if (requested != "fast" && requested != "release") {
            throw GradleException(
                "Invalid -Pmodernapps.rustProfile=$requested: expected 'fast' or 'release'."
            )
        }
        requested
    } ?: run {
        val taskNames = gradle.startParameter.taskNames
        if (taskNames.isNotEmpty() && taskNames.all { name ->
                val lastSegment = name.substringAfterLast(':')
                lastSegment.startsWith("cargo") || devTaskPattern.containsMatchIn(lastSegment)
            }
        ) "fast" else "release"
    }

    // Cross-crate path deps (euicc -> library/jni-http, camera -> library/vision,
    // maps/library:map -> library/tilecodec, ...), parsed generically from
    // Cargo.toml below. Computed once per crate — identical for every ABI — as
    // plain Files; only the FileCollections built from them cross into tasks.
    val pathDepDirs: List<File> = findRustPathDepDirs(file(srcDir))

    val compileShaders = if (shaders.isEmpty()) null else {
        require(srcDir == "src/main/rust") { "rustNativeLib($crate): shaders need the default srcDir" }
        val glslcExe = if (OperatingSystem.current().isWindows) "glslc.exe" else "glslc"
        tasks.register<GlslcCompile>("compileShaders") {
            description = "Compiles $crate's GLSL to SPIR-V."
            shaderDir.set(file("$srcDir/shaders"))
            sources.from(fileTree("$srcDir/shaders") { include(shaders) })
            glslc.set("$ndkRoot/shader-tools/$hostTag/$glslcExe")
            this.glslcArgs.set(glslcArgs)
            outputDir.set(layout.buildDirectory.dir("spirv"))
        }
    }

    val perAbi = rustAbis.map { abi ->
        val (abiDir, triple) = abi
        tasks.register<Exec>("cargoBuild_${crate}_${abiDir.replace('-', '_')}") {
            usesService(rustLock)
            description = "Cross-compiles lib$crate for $abiDir."
            workingDir = file(srcDir)

            // Windows hosts use the .cmd clang wrappers and .exe tool suffixes; Unix hosts none.
            val isWindows = OperatingSystem.current().isWindows
            val exeExt = if (isWindows) ".exe" else ""
            val clangExt = if (isWindows) ".cmd" else ""
            val clang = "$ndkBin/${abi.clangPrefix}$androidApiLevel-clang$clangExt"
            val clangpp = "$ndkBin/${abi.clangPrefix}$androidApiLevel-clang++$clangExt"
            val linkerVar = "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER"
            // Per-crate target (may exist from older isolated builds) and workspace root target
            // (current scheme since Cargo.toml workspace unified to root).
            val perCrateSoOut = file("$srcDir/target/$triple/$profile/lib$crate.so")
            val workspaceSoOut = rootProject.file("target/$triple/$profile/lib$crate.so")
            val destSo = layout.buildDirectory.file("rustJniLibs/$abiDir/lib$crate.so").get().asFile
            val targetRustflags = "-C link-arg=-Wl,-z,max-page-size=16384"

            // Cacheable + relocatable: every input is RELATIVE-sensitive with a
            // distinct property name, and toolchain facts the env doesn't cover
            // (Exec treats environment/workingDir as @Internal, so env vars are
            // NOT in the cache key) are explicit input properties. A fresh
            // worktree at a different absolute path then restores FROM-CACHE.
            outputs.cacheIf("Rust cdylib build is hermetic given the declared inputs") { true }

            inputs.dir("$srcDir/src").withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("crateSrc")
            inputs.file("$srcDir/Cargo.toml").withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("crateManifest")
            file("$srcDir/build.rs").takeIf { it.isFile }?.let {
                inputs.file("$srcDir/build.rs").withPathSensitivity(PathSensitivity.RELATIVE)
                    .withPropertyName("buildRs")
            }
            // The crate include_bytes!s build/spirv, so a shader edit must re-run cargo;
            // otherwise the task stays up to date and the old SPIR-V silently ships.
            compileShaders?.let {
                inputs.files(it).withPathSensitivity(PathSensitivity.RELATIVE)
                    .withPropertyName("spirv")
            }
            // `:library:ml`'s build.rs also compiles `schema/maml2.fbs` to Rust via
            // flatc and `include!`s the result, so a schema edit changes the .so too.
            // Same staleness hazard as shaders above: without this the task stays up
            // to date and the old bindings ship.
            file("$srcDir/schema").takeIf { it.isDirectory }?.let {
                inputs.dir("$srcDir/schema").withPathSensitivity(PathSensitivity.RELATIVE)
                    .withPropertyName("schema")
            }
            // Whole-crate safety net: track every source under src/main/rust so ANY
            // rust change invalidates cargoBuild - crucially vendored dependency
            // crates under vendor/, which
            // the path-specific inputs above do NOT cover. Before this, editing a
            // vendored crate left the task UP-TO-DATE and shipped a stale .so. Exclude
            // target/ because cargo writes this task's own .so there; folding build
            // output back in as an input means the task can never be up to date. The
            // FileTree is a FileCollection resolved at configuration time, so only the
            // file set (never the Project) crosses into the task action.
            inputs.files(fileTree(srcDir) { exclude("target/**") })
                .withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("rustCrateTree")
            // Cross-crate path deps (euicc -> jni-http, camera -> vision,
            // maps -> tilecodec, ...): an edit there must rebuild this .so.
            // Parsed generically from Cargo.toml `path =` + `workspace = true`
            // entries (see findRustPathDepDirs), one input tree per dep crate.
            for (depDir in pathDepDirs) {
                val relDep = depDir.relativeTo(rootProject.projectDir)
                inputs.files(rootProject.fileTree(relDep) { exclude("target/**") })
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                    .withPropertyName(pathDepPropertyName(rootProject.projectDir, depDir))
            }
            // Root workspace unified (Cargo.toml + Cargo.lock + rust-toolchain.toml)
            inputs.file(rootProject.file("Cargo.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("workspaceManifest")
            inputs.file(rootProject.file("Cargo.lock")).withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("workspaceLock")
            inputs.file(rootProject.file("rust-toolchain.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
                .withPropertyName("rustToolchain")
            // Only rust third_party crates – avoid pulling third_party:nanojson java outputs (was implicit dep failure).
            // jni is a crates.io dependency now, so Cargo.lock above covers it.
            inputs.dir(rootProject.file("third_party/om-file-format-sys/src"))
                .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("omFfiSrc")
            inputs.file(rootProject.file("third_party/om-file-format-sys/Cargo.toml"))
                .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("omFfiManifest")
            inputs.file(rootProject.file("third_party/om-file-format-sys/build.rs"))
                .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("omFfiBuildRs")
            inputs.dir(rootProject.file("third_party/om-file-format-sys/c"))
                .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("omFfiC")
            // BetoCore vendored crates (Apache-2.0) — incremental builds track these like om-file-format-sys.
            inputs.dir(rootProject.file("third_party/betocore"))
                .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("betocore")
            // Toolchain facts that shape the .so bytes but live in env/workingDir
            // (both @Internal on Exec), so they need explicit input properties.
            inputs.property("ndkVersion", NDK_VERSION)
            inputs.property("hostTag", hostTag)
            inputs.property("androidApiLevel", androidApiLevel)
            inputs.property("targetRustflags", targetRustflags)
            outputs.file(destSo)

            val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
            val rustSrc = file(srcDir).absolutePath

            val pathSep = if (isWindows) ";" else ":"
            environment("PATH", "$cargoBin$pathSep${System.getenv("PATH")}")
            // A non-rustup rustc earlier on PATH (e.g. Homebrew's) has no
            // aarch64-linux-android std, so cargo fails with "can't find crate for core".
            // Pin the rustup shim explicitly.
            environment("RUSTC", "$cargoBin/rustc$exeExt")
            // The per-API NDK clang wrapper bakes in --target and the sysroot.
            environment("CC", clang)
            environment("CXX", clangpp)
            environment("AR", "$ndkBin/llvm-ar$exeExt")
            environment("SYSROOT", ndkSysroot)
            environment(linkerVar, clang)
            // Android 15 requires 16 KB page alignment on arm64 (Play blocks
            // 4 KB .so files). Target-scoped so host build-script links on
            // MSVC are unaffected. See analysis/plan-16kb-page-size.md.
            val rustflagsVar = "CARGO_TARGET_${triple.uppercase().replace('-', '_')}_RUSTFLAGS"
            environment(rustflagsVar, targetRustflags)
            // Force the system clang for host-side C on Unix; on Windows let cc locate MinGW gcc.
            if (!isWindows) environment("HOST_CC", "/usr/bin/clang")
            // Pre-generated bindings (armv8-only): weather's om-file-format-sys no longer uses bindgen
            // (bindings_android.rs/host checked in), so BINDGEN_EXTRA_CLANG_ARGS is no longer needed for build.
            // Kept as comment for documentation if future C sys crates added.
            // environment("BINDGEN_EXTRA_CLANG_ARGS", "--target=$triple$androidApiLevel --sysroot=$ndkSysroot")
            // Reproducible builds: remap $HOME-specific paths (cargo registry + crate
            // dir) to fixed constants so different machines produce identical .so bytes.
            environment(
                "RUSTFLAGS",
                "--remap-path-prefix=$cargoHome=/cargo --remap-path-prefix=$rustSrc=/$remapLabel",
            )
            environment("CFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("CXXFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("CPPFLAGS", "-ffile-prefix-map=$cargoHome=/cargo -ffile-prefix-map=$rustSrc=/$remapLabel -Wdate-time -Werror=date-time")
            environment("ZERO_AR_DATE", "1")
            // Reproducible builds: respect SOURCE_DATE_EPOCH if set (exported by the release build)
            // https://reproducible-builds.org/docs/source-date-epoch/
            System.getenv("SOURCE_DATE_EPOCH")?.takeIf { it.isNotBlank() }?.let {
                environment("SOURCE_DATE_EPOCH", it)
            }
            environment("CARGO_INCREMENTAL", "0")

            commandLine(
                "$cargoBin/cargo$exeExt",
                "build",
                "--locked",
                "--profile",
                profile,
                "--target",
                triple,
                *cargoFeatureArgs.toTypedArray(),
            )

            doLast {
                destSo.parentFile.mkdirs()
                val src = when {
                    perCrateSoOut.exists() -> perCrateSoOut
                    workspaceSoOut.exists() -> workspaceSoOut
                    else -> error("Neither per-crate ${perCrateSoOut.path} nor workspace ${workspaceSoOut.path} exists for $crate")
                }
                src.copyTo(destSo, overwrite = true)
            }
        }
    }

    val cargoNdkBuild = tasks.register("cargoNdkBuild_$crate") {
        description = "Builds lib$crate.so for all Android ABIs."
        dependsOn(perAbi)
    }
    // Keep the historic aggregate name for the default crate dir so existing
    // invocations (`./gradlew :mod:cargoNdkBuild`) keep working.
    if (srcDir == "src/main/rust") {
        tasks.register("cargoNdkBuild") {
            description = "Builds lib$crate.so for all Android ABIs."
            dependsOn(cargoNdkBuild)
        }
    }
    // Rust file-length gate: fails the build on any *.rs over
    // RUST_FILE_LENGTH_LIMIT lines, like the Kotlin FileLength rule (which
    // cannot see Rust). Configuration-cache compatible: plain path strings
    // cross the configuration boundary, never the Project object.
    val rustSrcPath: String = file("$srcDir/src").absolutePath
    val rustProjPath: String = projectDir.absolutePath
    val lengthTaskName = if (srcDir == "src/main/rust") "rustFileLength" else "rustFileLength_$crate"
    val rustFileLength = tasks.register(lengthTaskName) {
        description = "Fails on Rust files over $RUST_FILE_LENGTH_LIMIT lines."
        inputs.dir(rustSrcPath)
        doLast {
            val srcDir = java.io.File(rustSrcPath)
            val projDir = java.io.File(rustProjPath)
            val over = srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "rs" }
                .map { it to it.readLines().size }
                .filter { (f, n) ->
                    n > RUST_FILE_LENGTH_LIMIT &&
                        f.name !in RUST_LENGTH_EXEMPT_FILES &&
                        !isRustLengthExempt(f.path)
                }
                .toList()
            if (over.isNotEmpty()) {
                val detail = over.joinToString("\n") { (f, n) ->
                    "  $n ${f.relativeTo(projDir)}"
                }
                throw org.gradle.api.GradleException(
                    "Rust files exceed $RUST_FILE_LENGTH_LIMIT lines (split along module lines):\n$detail"
                )
            }
        }
    }
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(cargoNdkBuild)
        dependsOn(rustFileLength)
    }
}
