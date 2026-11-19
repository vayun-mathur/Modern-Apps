package com.vayunmathur.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile

/**
 * Enforces the closed canonical package structure (§2) plus the closed root-FILE
 * allow-list with strict per-file content contracts (§3.3).
 *
 * Each app's `src/main/java/com/vayunmathur/<app>/` may only contain the
 * closed set of roots; anything else is a violation. The check derives
 * `<app>` from the file's `package` declaration (source of truth, not file
 * path) and inspects the full segment path after `com.vayunmathur.<app>`:
 * the first segment must be an allowed root, descendant segments must name
 * features/capabilities rather than generic buckets, and `widget` is flat.
 *
 * Files whose package is exactly `com.vayunmathur.<app>` (no sub-package) are
 * further gated to the closed file allow-list: MainActivity.kt, Route.kt,
 * Navigation.kt, *Application.kt — each with its exact content contract.
 */
class PackageStructureDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val packageName = node.packageName
                if (packageName.isEmpty()) return
                if (isJniException(node)) return
                if (isExcluded(packageName)) return
                if (!packageName.startsWith("com.vayunmathur.")) return

                val result = deriveAppBaseAndSegments(packageName) ?: return
                val segments = result.second
                val segment = segments.firstOrNull()
                if (segment != null) {
                    if (segment in ALLOWED_ROOTS) {
                        val subpackageViolation = validateSubpackages(packageName, segments)
                        if (subpackageViolation != null) {
                            context.report(
                                ISSUE,
                                node,
                                context.getLocation(node),
                                subpackageViolation,
                            )
                        }
                        return
                    }

                    // Unknown root — report.
                    val suggestion = aliasSuggestion(segment)
                    val allowedList = ALLOWED_ROOTS.sorted().joinToString(", ")
                    val message = buildString {
                        append("Package `")
                        append(packageName)
                        append("` is not in the canonical closed set. ")
                        append("Segment `")
                        append(segment)
                        append("` is not an allowed root. ")
                        append("Allowed roots: [")
                        append(allowedList)
                        append("].")
                        if (suggestion != null) {
                            append(" Did you mean `")
                            append(suggestion)
                            append("`? ")
                        }
                        append(" See docs/package-structure.md §2.")
                        append(" (If this is a JNI FQN exception, add the `// PACKAGE STRUCTURE EXCEPTION (JNI)` marker comment to the file.)")
                    }

                    context.report(
                        ISSUE,
                        node,
                        context.getLocation(node),
                        message,
                    )
                    return
                }

                // Root file (package == com.vayunmathur.<app>) — enforce closed root-file allow-list + contracts.
                val fileName = context.file?.name
                    ?: (node.sourcePsi as? KtFile)?.name
                    ?: node.sourcePsi?.containingFile?.name
                    ?: ""
                if (fileName.isEmpty()) return
                if (fileName == "BuildConfig.java" || fileName == "BuildConfig.kt" || fileName == "R.java" || fileName == "R.kt") return

                val violation = checkRootFileContract(node, fileName)
                if (violation != null) {
                    context.report(
                        ISSUE,
                        node,
                        context.getLocation(node),
                        violation,
                    )
                }
            }
        }

    companion object {

        private val ALLOWED_ROOTS = setOf(
            "ui", "data", "domain", "platform", "network", "intents",
            "service", "provider", "widget", "notifications", "auth", "sync", "telephony"
        )

        // Shared with OneComposablePerFileDetector via [LintPackageExclusions] so
        // both app-structure lints skip the same non-app modules (shared library,
        // e2ee crypto lib, sdk, games, tools, personal). See that file for the
        // per-prefix rationale.
        fun isExcluded(packageName: String): Boolean =
            LintPackageExclusions.isExcluded(packageName)

        // JNI carve-out: a handful of classes have their fully-qualified name frozen
        // because the native side binds to it via RegisterNatives / C++ symbol mangling.
        // Renaming or re-nesting them would break the JNI linkage, so they are exempt
        // from the root-package / root-file contracts.
        //
        // Primary mechanism (self-documenting): any file carrying the marker comment
        // below is skipped, so a future JNI file only needs to add the comment.
        // Belt-and-suspenders: an explicit allow-set of the currently-frozen FQNs acts
        // as a fallback in case the marker is ever accidentally dropped.
        // Internal (not private) so ExceptionMarkerFormatDetector can validate the
        // verbatim shape against the same constant instead of a copy.
        internal const val JNI_MARKER = "// PACKAGE STRUCTURE EXCEPTION (JNI)"

        private val JNI_EXEMPT_FQNS = setOf(
            "com.vayunmathur.euicc.EuiccNative",
            "com.vayunmathur.games.voxels.util.VoxelsNative",
            "com.vayunmathur.measure.domain.MeasureNative",
            "com.vayunmathur.passwords.util.KdbxNative",
            "com.vayunmathur.share.protocol.ShareNative",
            "com.vayunmathur.vpn.util.VpnNative",
            "com.vayunmathur.web.shields.ShieldsNative",
        )

        fun isJniException(node: UFile): Boolean {
            val ktFile = node.sourcePsi as? KtFile
            if (ktFile != null && CommentOnlyMatcher.containsMarkerInComments(ktFile, JNI_MARKER)) return true
            // Fall back to raw text for non-Kotlin sources, where the marker is
            // still a comment but there is no KtFile to walk.
            val text = if (ktFile == null) node.sourcePsi?.text else null
            if (text != null && text.contains(JNI_MARKER)) return true
            val pkg = node.packageName
            return node.classes.any { clazz ->
                val name = clazz.name ?: return@any false
                "$pkg.$name" in JNI_EXEMPT_FQNS
            }
        }

        /**
         * Derives the app base package and every segment after it.
         * Returns null if the package does not start with `com.vayunmathur.`.
         * Returns Pair(base, emptyList()) for root files (no extra segment).
         */
        private fun deriveAppBaseAndSegments(packageName: String): Pair<String, List<String>>? {
            if (!packageName.startsWith("com.vayunmathur.")) return null
            val remainder = packageName.removePrefix("com.vayunmathur.")
            if (remainder.isEmpty()) return null
            // An app module nested a level deeper is still an app, and its own roots are what the
            // closed set applies to. Without this, `com.vayunmathur.cast.tv.platform` would be read
            // as app `cast` with a root segment `tv`, which is not in the allow-list and never
            // could be.
            for (base in NESTED_APP_BASES) {
                if (remainder == base) return Pair("com.vayunmathur.$base", emptyList())
                if (remainder.startsWith("$base.")) {
                    val segments = remainder.removePrefix("$base.").split('.')
                    return Pair("com.vayunmathur.$base", segments)
                }
            }
            val dot = remainder.indexOf('.')
            return if (dot == -1) {
                Pair("com.vayunmathur.$remainder", emptyList())
            } else {
                val app = remainder.substring(0, dot)
                val segments = remainder.substring(dot + 1).split('.')
                Pair("com.vayunmathur.$app", segments)
            }
        }

        private val BANNED_DESCENDANT_SEGMENTS = setOf(
            "api", "apis", "base", "common", "core", "feature", "features",
            "glance", "helper", "helpers", "impl", "manager", "managers",
            "misc", "presentation", "repository", "repositories", "screen",
            "screens", "util", "utils", "view", "views", "viewmodel",
            "viewmodels", "widget", "widgets",
        )

        private fun validateSubpackages(packageName: String, segments: List<String>): String? {
            val root = segments.firstOrNull() ?: return null
            if (root == "widget" && segments.size > 1) {
                return "Package `$packageName` fragments widget code below `widget`. " +
                    "Widget code must be directly in the flat `widget` root; Glance is an " +
                    "implementation detail, not a package boundary. Move the file to " +
                    "`${packageName.substringBefore(".widget")}.widget`. " +
                    "See docs/package-structure.md §3.2."
            }
            val banned = segments.drop(1).firstOrNull { it in BANNED_DESCENDANT_SEGMENTS }
            if (banned != null) {
                return "Package `$packageName` uses banned package segment `$banned` below " +
                    "root `$root`. Package segments name a responsibility, feature, provider, " +
                    "or protocol—not a generic bucket, class type, synonym, or UI toolkit. " +
                    "Move the file under the closest canonical responsibility in " +
                    "docs/package-structure.md §2–§4."
            }
            return null
        }

        /**
         * App modules whose package is two segments deep rather than one.
         *
         * `:cast:tv` is a second app in the `:cast` family - the Android TV receiver the phone casts
         * to - and it is nested because `common-conventions-app` derives the namespace from the
         * Gradle path, so `com.vayunmathur.cast.tv` requires the path `:cast:tv`. It is a full app
         * and holds itself to the same closed root set as any other.
         */
        private val NESTED_APP_BASES = listOf("cast.tv")

        // ---- root-file contracts (closed allow-list) ----

        private fun checkRootFileContract(node: UFile, fileName: String): String? {
            val ktFile = node.sourcePsi as? KtFile
            return when {
                fileName == "MainActivity.kt" -> checkMainActivity(ktFile, node)
                fileName == "Route.kt" -> checkRoute(ktFile, node)
                fileName == "Navigation.kt" -> checkNavigation(ktFile, node)
                fileName.endsWith("Application.kt") -> checkApplication(ktFile, node, fileName)
                else -> {
                    "File `$fileName` in app root package `${node.packageName}` is not in the closed root-file allow-list. " +
                        "Allowed root files: [MainActivity.kt, Route.kt, Navigation.kt, *Application.kt]. " +
                        "Move `$fileName` to a canonical folder (e.g. screens → ui/, ViewModels → platform/, repositories → data/). " +
                        "See plan §3.3, docs/package-structure.md §2."
                }
            }
        }

        private fun checkMainActivity(ktFile: KtFile?, node: UFile): String? {
            if (ktFile != null) {
                val decls = ktFile.declarations
                var hasMainActivity = false
                val extra = mutableListOf<String>()
                for (decl in decls) {
                    when (decl) {
                        is KtClass -> {
                            val name = decl.name ?: "class"
                            if (name == "MainActivity") {
                                if (hasMainActivity) extra.add("class MainActivity (duplicate)")
                                else hasMainActivity = true
                            } else {
                                extra.add("class $name")
                                if (name == "Route") extra.add("(Route belongs in Route.kt)")
                                if (name == "Navigation") extra.add("(Navigation belongs in Navigation.kt)")
                            }
                        }
                        is KtNamedFunction -> {
                            val fname = decl.name ?: "fun"
                            extra.add("fun $fname")
                            if (decl.isComposable()) extra.add("(composable belongs in ui/)")
                        }
                        is KtProperty -> extra.add("val ${decl.name ?: "property"}")
                        is KtTypeAlias -> extra.add("typealias ${decl.name ?: "alias"}")
                        else -> extra.add(decl.javaClass.simpleName)
                    }
                }
                if (!hasMainActivity) {
                    val found = if (decls.isEmpty()) "empty file" else decls.joinToString { (it as? KtClass)?.name ?: (it as? KtNamedFunction)?.name ?: it.javaClass.simpleName }
                    return "MainActivity.kt must contain only the MainActivity class; expected `class MainActivity : ComponentActivity()` but found $found. " +
                        "Move other declarations to a canonical folder (Route belongs in Route.kt, Navigation belongs in Navigation.kt, screens → ui/, ViewModels → platform/). " +
                        "See plan §3.3."
                }
                if (extra.isNotEmpty()) {
                    val offending = extra.joinToString(", ")
                    return "MainActivity.kt must contain only the MainActivity class; move $offending to a canonical folder " +
                        "(Route belongs in Route.kt, Navigation belongs in Navigation.kt; screens → ui/, ViewModels → platform/, data → data/). " +
                        "See plan §3.3."
                }
                return null
            }
            return checkMainActivityUast(node)
        }

        private fun checkMainActivityUast(node: UFile): String? {
            val classes = node.classes
            val classNames = classes.mapNotNull { it.name }
            val hasSynthetic = classNames.any { it.endsWith("Kt") }
            val hasMainActivity = classNames.contains("MainActivity")
            if (!hasMainActivity) {
                return "MainActivity.kt must contain only the MainActivity class; expected `class MainActivity : ComponentActivity()` but found [${classNames.joinToString()}]. " +
                    "Move other declarations to a canonical folder. See plan §3.3."
            }
            val extraClasses = classNames.filter { it != "MainActivity" && !it.endsWith("Kt") }
            val hasExtra = extraClasses.isNotEmpty() || hasSynthetic || hasTopLevelFunctions(node)
            if (hasExtra) {
                val offending = buildList {
                    addAll(extraClasses.map { "class $it" })
                    if (extraClasses.contains("Route")) add("(Route belongs in Route.kt)")
                    if (extraClasses.contains("Navigation")) add("(Navigation belongs in Navigation.kt)")
                    if (hasSynthetic || hasTopLevelFunctions(node)) add("top-level fun/val")
                }.joinToString(", ")
                return "MainActivity.kt must contain only the MainActivity class; move $offending to a canonical folder " +
                    "(Route belongs in Route.kt, Navigation belongs in Navigation.kt). See plan §3.3."
            }
            return null
        }

        private fun checkRoute(ktFile: KtFile?, node: UFile): String? {
            if (ktFile != null) {
                val decls = ktFile.declarations
                if (decls.size != 1) {
                    val names = decls.mapNotNull { ktName(it) }.joinToString(", ")
                    val hasComposable = decls.any { it is KtNamedFunction && it.isComposable() }
                    val hint = if (hasComposable) " Remove @Composable functions (screens → ui/, Navigation → Navigation.kt)." else ""
                    return "Route.kt must contain only `sealed interface Route : NavKey` (+ nested route objects/classes); " +
                        "found ${decls.size} top-level declarations${if (names.isNotEmpty()) " [$names]" else ""}. " +
                        "Move extra declarations to a canonical folder.$hint Route belongs in Route.kt; Navigation belongs in Navigation.kt. See plan §3.3."
                }
                val sole = decls.first()
                if (sole !is KtClass) {
                    return "Route.kt must contain only `sealed interface Route : NavKey` (+ nested route objects/classes); " +
                        "found ${sole.javaClass.simpleName} `${ktName(sole) ?: ""}`. " +
                        "Route belongs in Route.kt; Navigation belongs in Navigation.kt. See plan §3.3."
                }
                val name = sole.name
                if (name != "Route") {
                    return "Route.kt must contain only `sealed interface Route : NavKey`; found `class $name` instead. " +
                        "Route belongs in Route.kt. See plan §3.3."
                }
                val isSealed = sole.text.contains("sealed")
                val isInterface = sole.text.contains("interface")
                val hasNavKey = sole.superTypeListEntries.any { it.text.contains("NavKey") }
                if (!isSealed || !isInterface) {
                    return "Route.kt must contain `sealed interface Route : NavKey` (+ nested routes); " +
                        "found ${if (!isSealed) "non-sealed" else ""} ${if (!isInterface) "non-interface" else "interface"} `Route`. " +
                        "Route belongs in Route.kt. See plan §3.3."
                }
                if (!hasNavKey) {
                    return "Route.kt must declare `sealed interface Route : NavKey`; `Route` does not extend `NavKey`. " +
                        "Route belongs in Route.kt. See plan §3.3."
                }
                return null
            }
            val classes = node.classes
            if (classes.size != 1 || classes.first().name != "Route") {
                val names = classes.mapNotNull { it.name }.joinToString(", ")
                return "Route.kt must contain only `sealed interface Route : NavKey` (+ nested route objects/classes); " +
                    "found [${names}]. Route belongs in Route.kt; Navigation belongs in Navigation.kt. See plan §3.3."
            }
            if (hasTopLevelFunctions(node)) {
                return "Route.kt must contain only `sealed interface Route : NavKey` (+ nested routes); " +
                    "found top-level @Composable/fun declarations. Move composables to ui/ and Navigation to Navigation.kt. " +
                    "Route belongs in Route.kt. See plan §3.3."
            }
            return null
        }

        private fun checkNavigation(ktFile: KtFile?, node: UFile): String? {
            if (ktFile != null) {
                val decls = ktFile.declarations
                if (decls.size != 1) {
                    val names = decls.mapNotNull { ktName(it) }.joinToString(", ")
                    val hasRoute = decls.any { it is KtClass && it.name == "Route" }
                    val routeHint = if (hasRoute) " Route belongs in Route.kt." else ""
                    return "Navigation.kt must contain only the top-level `@Composable fun Navigation(...)` nav graph; " +
                        "found ${decls.size} top-level declarations${if (names.isNotEmpty()) " [$names]" else ""}.$routeHint " +
                        "Move screen composables to ui/, Route to Route.kt, and helpers to domain/platform. Navigation belongs in Navigation.kt. See plan §3.3."
                }
                val sole = decls.first()
                if (sole !is KtNamedFunction) {
                    val found = ktName(sole) ?: sole.javaClass.simpleName
                    val isRoute = sole is KtClass && sole.name == "Route"
                    val hint = if (isRoute) " Route belongs in Route.kt." else ""
                    return "Navigation.kt must contain only `@Composable fun Navigation(...)`; found `$found` instead.$hint " +
                        "Navigation belongs in Navigation.kt; screens belong in ui/. See plan §3.3."
                }
                if (sole.name != "Navigation") {
                    return "Navigation.kt must contain only `@Composable fun Navigation(...)`; found `fun ${sole.name}`. " +
                        "Navigation belongs in Navigation.kt. See plan §3.3."
                }
                if (!sole.isComposable()) {
                    return "Navigation.kt must contain `@Composable fun Navigation(...)`; `fun Navigation` is not annotated with @Composable. " +
                        "See plan §3.3."
                }
                return null
            }
            val hasComposableNav = node.classes.any { clazz ->
                clazz.methods.any { m ->
                    val psi = m.sourcePsi as? KtNamedFunction
                    psi?.name == "Navigation" && psi.isComposable()
                }
            }
            if (!hasComposableNav) {
                val classNames = node.classes.mapNotNull { it.name }.joinToString(", ")
                return "Navigation.kt must contain only `@Composable fun Navigation(...)`; found [${classNames}]. " +
                    "Navigation belongs in Navigation.kt; Route belongs in Route.kt. See plan §3.3."
            }
            if (node.classes.any { it.name == "Route" }) {
                return "Navigation.kt must contain only `@Composable fun Navigation(...)`; found `Route` declaration. " +
                    "Route belongs in Route.kt. See plan §3.3."
            }
            if (node.classes.size > 1 || (hasTopLevelFunctions(node) && node.classes.flatMap { it.methods.toList() }.size > 1)) {
                return "Navigation.kt must contain only `@Composable fun Navigation(...)`; found extra top-level declarations. " +
                    "Move screens to ui/ and helpers to domain/platform. See plan §3.3."
            }
            return null
        }

        private fun checkApplication(ktFile: KtFile?, node: UFile, fileName: String): String? {
            val expectedClass = fileName.removeSuffix(".kt").removeSuffix(".java")
            if (!expectedClass.endsWith("Application")) {
                return "File `$fileName` in app root must be `*Application.kt` with an Application subclass; " +
                    "`$expectedClass` does not end with `Application`. Allowed root files: [MainActivity.kt, Route.kt, Navigation.kt, *Application.kt]. See plan §3.3."
            }
            if (ktFile != null) {
                val decls = ktFile.declarations
                if (decls.size != 1) {
                    val names = decls.mapNotNull { ktName(it) }.joinToString(", ")
                    return "$fileName must contain only `class $expectedClass : Application()`; " +
                        "found ${decls.size} top-level declarations${if (names.isNotEmpty()) " [$names]" else ""}. " +
                        "Move other declarations to a canonical folder. See plan §3.3."
                }
                val sole = decls.first()
                if (sole !is KtClass) {
                    return "$fileName must contain only `class $expectedClass : Application()`; found ${sole.javaClass.simpleName}. See plan §3.3."
                }
                val name = sole.name
                if (name != expectedClass) {
                    return "$fileName must contain `class $expectedClass : Application()`; found `class $name`. File name and class must match. See plan §3.3."
                }
                val extendsApplication = sole.superTypeListEntries.any { it.text.contains("Application") }
                if (!extendsApplication) {
                    return "$fileName must declare `class $expectedClass : Application()`; `$expectedClass` does not extend `Application`. See plan §3.3."
                }
                return null
            }
            val realClasses = node.classes.filter { !(it.name ?: "").endsWith("Kt") }
            if (realClasses.size != 1 || realClasses.first().name != expectedClass) {
                val names = realClasses.mapNotNull { it.name }.joinToString(", ")
                return "$fileName must contain only `class $expectedClass : Application()`; found [${names}]. See plan §3.3."
            }
            if (hasTopLevelFunctions(node)) {
                return "$fileName must contain only `class $expectedClass : Application()`; found top-level fun/val. See plan §3.3."
            }
            return null
        }

        private fun hasTopLevelFunctions(node: UFile): Boolean {
            for (clazz in node.classes) {
                for (method in clazz.methods) {
                    val psi = method.sourcePsi as? KtNamedFunction ?: continue
                    if (psi.isTopLevel()) return true
                }
            }
            return false
        }

        private fun KtNamedFunction.isTopLevel(): Boolean = parent is KtFile

        private fun KtNamedFunction.isComposable(): Boolean =
            annotationEntries.any { it.shortName?.asString() == "Composable" || it.text.contains("Composable") }

        private fun ktName(decl: org.jetbrains.kotlin.psi.KtDeclaration): String? = when (decl) {
            is KtClass -> decl.name
            is KtNamedFunction -> decl.name
            is KtProperty -> decl.name
            is KtTypeAlias -> decl.name
            else -> null
        }

        private val BANNED_ALIAS_MAP: Map<String, String> = mapOf(
            "util" to "domain/ or platform/ (see litmus test in docs/package-structure.md §2.1)",
            "api" to "network/",
            "model" to "data/ (or data/model/ subpackage)",
            "viewmodel" to "platform/",
            "widgets" to "widget/ (singular and flat)",
            "glance" to "widget/ (flat; Glance is an implementation detail, not a package)",
            "composer" to "ui/composer/",
            "shields" to "domain/shields/",
            "syntax" to "domain/syntax/",
            "ime" to "platform/ime/",
            "tts" to "platform/tts/",
            "imap" to "network/imap/",
            "smtp" to "network/smtp/",
            "saf" to "data/saf/",
            "crypto" to "domain/crypto/",
            "format" to "domain/format/",
            "remote" to "network/remote/",
            "sink" to "data/sink/",
        )

        private fun aliasSuggestion(badSegment: String): String? {
            val mapped = BANNED_ALIAS_MAP[badSegment]
            return when {
                mapped != null -> mapped
                else -> "Move under one of [data|network|domain|platform|...] per docs/package-structure.md §2.3"
            }
        }

        val ISSUE: Issue = Issue.create(
            id = "PackageStructure",
            briefDescription = "Package not in canonical closed set",
            explanation = """
                Each app's `src/main/java/com/vayunmathur/<app>/` may only contain the closed \
                set of roots: [ui, data, domain, platform, network, intents, service, provider, \
                widget, notifications, auth, sync, telephony]. Any other top-level segment is a \
                violation. Descendant segments must name a responsibility, feature, provider, \
                or protocol; generic buckets and synonyms (`util`, `common`, `helper`, \
                `manager`, `api`, `viewmodel`, `widgets`, …) are violations. `widget` is \
                singular and flat: `widget.glance` and every other `widget.<child>` are \
                violations because Glance is an implementation detail, not a package boundary. \
                Additionally, files whose package is exactly `com.vayunmathur.<app>` \
                (app root) are restricted to a closed file allow-list: [MainActivity.kt (only \
                class MainActivity), Route.kt (only sealed interface Route : NavKey), \
                Navigation.kt (only @Composable fun Navigation(...)), *Application.kt (only the \
                Application subclass)]. Any other root file, or a root file violating its content \
                contract (e.g. Route inside MainActivity.kt, screens inside Navigation.kt, \
                composables inside Route.kt), is also a violation. Re-nest the code under the \
                closest canonical root per docs/package-structure.md §2.3, or amend the plan to \
                add a new root. Shared library modules (com.vayunmathur.library*) are excluded \
                from this check.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                PackageStructureDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
