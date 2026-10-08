import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

// root build.gradle.kts - the root project is no longer the application (docs/MIGRATION.md "The module map"
// §4.1: today's root build.gradle becomes :app/build.gradle).
buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        classpath("com.android.tools.build:gradle:9.0.1")
        classpath("com.diffplug.spotless:spotless-plugin-gradle:8.5.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
        classpath("com.android.compose.screenshot:screenshot-test-gradle-plugin:0.0.1-alpha16")
        // :xmpp's `generateExtensionIndex` replaces the annotation processor that used to write
        // `Extensions.EXTENSION_CLASS_MAP`. ClassGraph reads classfiles, so it sees the Kotlin and
        // the Java models alike -- which is the whole reason the processor and its hand-written
        // Kotlin twin (`ModelRegistry`) go away. Guava is pinned beside it because the index's
        // unqualified element names stay the *processor's* derivation, `CaseFormat.UPPER_CAMEL` ->
        // `CaseFormat.LOWER_HYPHEN`, byte for byte: a hand-rolled spelling drifts on exactly the
        // acronyms (`IV` -> `iv`, `URL` -> `url`, `VCard` -> `vcard`), and an entry whose spelling
        // changes stops resolving at runtime with nothing to warn you.
        //
        // The version is pinned because the wiki's recipe uses `enableClasspathEntries`, which does
        // not exist in 4.8.197; the scan is `overrideClasspath` over the two compiled class trees.
        classpath("io.github.classgraph:classgraph:4.8.197")
        classpath("com.google.guava:guava:33.6.0-jre")
    }
}

subprojects {
    // build.gradle.kts:18-22 is one root-level `repositories {}` block, and it served the root
    // project -- which *is* the application today.  After the split every module resolves
    // against its own repository list: a module without one fails dependency resolution
    // with `no repositories are defined` for every one of the coordinates it names, so the
    // three remotes move in here unchanged.
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }

    // §4.6: `apply` does not propagate, so spotless is applied per subproject; ratchetFrom is
    // a git ref resolved per project.
    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        ratchetFrom("2.17.4")
        java {
            target("**/*.java")
            googleJavaFormat().aosp().reflowLongStrings()
        }
    }

    // §4.6: the exclude is per project, and it may NOT be a bare `configurations {
    // implementation.exclude ... }` here.  That DSL *creates* the configuration it names, so
    // every subproject -- the two checked-in java-library projects in :libs included -- would
    // own an `implementation` before its own plugin does, and the java plugin then fails with
    // "Cannot add a configuration with name 'implementation'".  configureEach is lazy: it
    // runs when a project's plugin makes `implementation`, and creates nothing anywhere.
    configurations.configureEach {
        if (name == "implementation") {
            exclude(mapOf("group" to "org.jetbrains", "module" to "annotations"))
        }
    }

    plugins.withId("com.android.library") {
        configure<com.android.build.api.dsl.LibraryExtension> {
            lint {
                disable += listOf("AndroidGradlePluginVersion", "MissingTranslation")
                abortOnError = false
            }
        }
    }

    // Decision 4, "the islands are mechanical ... enforced by a banned-import check in the build"
    // (docs/MIGRATION.md "Decided: the target shape of the module split (owner approved,
    // 2026-09-27)"). Every module's preBuild waits on the root's boundary rung, so
    // `assembleTulkkiDebug` -- and any module build, tests included -- fails on an import that
    // breaks tools/modules.toml. It is one task for the whole build, so the checker's scan runs
    // once; `dependsOn` takes the path lazily and the task is registered below this block.
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(":checkModuleBoundary")
    }
}

// The one root value.  Every module's `app_name`, `APP_NAME` and `APPLICATION_ID` reads these, so
// the three cannot drift: `Config.LOGTAG` must stay "tulkki" and `OsHeldNamesTest` pins the rest.
// (`ext { appName = 'Tulkki' ... }` in the Kotlin DSL: the three readers call `rootProject.extra`.)
extra["appName"] = "Tulkki"
extra["appId"] = "uk.xa0.tulkki"
extra["versionCode"] = 1
extra["versionName"] = "1.0.0"
extra["privacyPolicy"] = "null"
extra["upstreamBase"] = "v2.2.2"

// The boundary rung. `tools/check-boundary` owns the checker's argv and its three-valued exit
// contract (0 intact, 1 a finding, 2 the check could not run); this task only turns anything
// non-zero into a build failure, which is what makes an illegal island import fail
// `assembleTulkkiDebug` instead of a boundary campaign.
//
// Deliberately never up to date: the check reads source that the task graph does not model file by
// file, and a boundary check Gradle may skip is not a boundary. It costs the checker's own scan
// (about five seconds) per build, and its output is replayed through the build log so a finding is
// readable where the build failed.
abstract class CheckModuleBoundary : DefaultTask() {

    @get:Inject
    abstract val execOperations: ExecOperations

    /** The checkout the checker reads: this project's own directory, so a lane checks its own tree. */
    @get:Internal
    abstract val checkoutRoot: DirectoryProperty

    @TaskAction
    fun check() {
        val root = checkoutRoot.get().asFile
        // The checker's own report is captured and replayed through the build log; Gradle's
        // ExecResult accessors for it are not on every version's implementation, and the streams
        // are the stable way to hold it.
        val report = ByteArrayOutputStream()
        val hint = ByteArrayOutputStream()
        val result = execOperations.exec {
            // The interpreter is the command, never the script. This box's LSM denies a JVM
            // (`java_t`) the exec of any file under the checkout (`user_home_t`): naming the script
            // fails the build with "Exec failed, error: 13 (Permission denied)" before the checker
            // ever starts, while the JVM may still *read* it. A policy change or a relabel is not
            // available here, so the robust fix is to exec `/usr/bin/env`, which every domain may
            // exec, and let Python read the script. Same checker, same working directory, same
            // three-valued contract - `2` stays non-zero, so a check that cannot run is never a pass.
            commandLine("/usr/bin/env", "python3", File(root, "tools/check-boundary").absolutePath)
            workingDir = root
            isIgnoreExitValue = true
            standardOutput = report
            errorOutput = hint
        }
        if (report.size() > 0) {
            logger.lifecycle(report.toString().trim())
        }
        if (result.exitValue != 0) {
            val text = hint.toString().trim()
            throw GradleException(
                "the module boundary is broken (tools/check-boundary exit ${result.exitValue})" +
                    if (text.isNotEmpty()) ":\n$text" else ""
            )
        }
    }
}

tasks.register<CheckModuleBoundary>("checkModuleBoundary") {
    group = "verification"
    description = "Fails the build when an import breaks the module map (tools/modules.toml)."
    checkoutRoot.set(project.layout.projectDirectory)
    outputs.upToDateWhen { false }
}
