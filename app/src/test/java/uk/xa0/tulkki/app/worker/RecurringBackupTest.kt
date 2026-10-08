package uk.xa0.tulkki.app.worker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert
import org.junit.Test

/**
 * The one description of the recurring backup, on the JVM.
 *
 * <p>Two things can be pinned here. The request itself is built without a `Context`: a
 * `PeriodicWorkRequest` is a `WorkSpec` with a class name, an interval, constraints and
 * input data, and all four are readable off the built object - so the shape the settings listener
 * used to spell out inline is now pinned as the shape [RecurringBackup.request] produces. And
 * the name is text the device already holds, so it is compared against the source that spells it
 * rather than against the constant (a `static final String` is inlined into this class, which
 * would make the comparison a tautology).
 *
 * <p>What is <em>not</em> here, and is a device check: that WorkManager stores this request and runs
 * `ExportBackupWorker` from it on the owner's phone. That needs a device, and is stated as
 * unverified rather than assumed.
 */
class RecurringBackupTest {

    private companion object {

        /** The interval the preference stores for "daily"; the same number WorkReArmTest parses. */
        const val ONE_DAY_SECONDS = 86400L

        /**
         * The file `relative` names, found in either layout.
         *
         * <p>The path is repository-relative, and after the module split the file it names lives in the
         * module that owns it - `RecurringBackup.kt` stays in `:app`, while
         * a resource file is `:ui`'s. The repository root is found by its
         * `settings.gradle.kts` marker, because the Gradle working directory is the module after the
         * split and a walk that stopped at the first `src/main/java` would stop there; the root is
         * then searched, and each module under it, so one spelling works in both layouts.
         */
        fun read(relative: String): String {
            val path = locate(relative)
            Assert.assertTrue("missing " + relative, Files.exists(path))
            // not Files.readString: the unit tests compile against android.jar's java.nio.file.Files,
            // which does not have it (the same reason OsHeldNamesTest reads bytes).
            return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        }

        /**
         * The file `relative` names, in either layout and under either source extension.
         *
         * <p>A pin names the path its text lives at, never the language it is written in, and the Kotlin
         * port moves a file from `.java` to `.kt` without moving the path - so when the named
         * spelling is absent the sibling spelling is looked up the same way before the assertion fires. A
         * file that is genuinely gone still fails, whichever extension it was asked for; a resource file
         * has no source sibling and is searched once.
         */
        fun locate(relative: String): Path {
            val named = search(relative)
            if (named != null) {
                return named
            }
            val sibling = siblingSource(relative)
            if (sibling != relative) {
                val under = search(sibling)
                if (under != null) {
                    return under
                }
            }
            throw AssertionError("could not locate " + relative + " under " + root())
        }

        /** The same path under the other source extension, or the path itself when it has neither. */
        fun siblingSource(relative: String): String {
            if (relative.endsWith(".java")) {
                return relative.substring(0, relative.length - ".java".length) + ".kt"
            }
            if (relative.endsWith(".kt")) {
                return relative.substring(0, relative.length - ".kt".length) + ".java"
            }
            return relative
        }

        /** Where `relative` is, or `null` when the tree holds no such file. */
        fun search(relative: String): Path? {
            val checkoutRoot = root()
            val direct = checkoutRoot.resolve(relative)
            if (Files.exists(direct)) {
                return direct
            }
            Files.list(checkoutRoot).use { first ->
                for (directory in first.filter { Files.isDirectory(it) }.sorted().toList()) {
                    val name = directory.fileName.toString()
                    if (name.startsWith(".") || name == "build" || name == "src") {
                        continue
                    }
                    val candidate = directory.resolve(relative)
                    if (Files.exists(candidate)) {
                        return candidate
                    }
                    // one level further, because `:libs` lives at libs/annotation
                    Files.list(directory).use { second ->
                        for (nested in second.filter { Files.isDirectory(it) }.sorted().toList()) {
                            val nestedName = nested.fileName.toString()
                            if (nestedName.startsWith(".") || nestedName == "build") {
                                continue
                            }
                            val deeper = nested.resolve(relative)
                            if (Files.exists(deeper)) {
                                return deeper
                            }
                        }
                    }
                }
            }
            return null
        }

        fun root(): Path {
            var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            var level = 0
            while (level < 6 && directory != null) {
                if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                        || Files.isRegularFile(directory.resolve("settings.gradle"))) {
                    return directory
                }
                directory = directory.parent
                level++
            }
            throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
        }
    }

    @Test
    fun theRequestCarriesTheIntervalTheWorkerAndTheFlag() {
        val spec = RecurringBackup.request(ONE_DAY_SECONDS).workSpec
        Assert.assertEquals(
            "the preference is in seconds and the spec is in milliseconds",
            ONE_DAY_SECONDS * 1000L,
            spec.intervalDuration)
        Assert.assertEquals(
            "the request must name the class that exists now, not the one a build once had",
            ExportBackupWorker::class.java.name,
            spec.workerClassName)
        Assert.assertTrue(
            "a backup must not run on a phone that is about to die",
            spec.constraints.requiresBatteryNotLow())
        Assert.assertTrue(
            "nor one with no room to write the file",
            spec.constraints.requiresStorageNotLow())
        Assert.assertTrue(
            "this flag is how the worker tells a recurring export from a one-off",
            spec.input.getBoolean(RecurringBackup.NAME, false))
    }

    @Test
    fun theNameIsTheTextTheDeviceAlreadyHolds() {
        // One text, one site: WorkManager keeps the recurring row under this unique name, and the
        // interval the owner picked is stored under the same string as a preference key. Since the
        // settings tree became Compose there is no second spelling to compare - the row asks the host
        // port and the port answers with this constant - so what must not drift is the port's answer.
        // If it does, the process-start repair reads no interval and the row it exists to rebuild is
        // silently left stale.
        // The path is a *string*, not an import: the 3.8 rename rewrote every dotted name in this
        // tree and left this one behind, which is why the test failed rather than the code.
        val backup = read("src/main/java/uk/xa0/tulkki/app/worker/RecurringBackup.kt")
        Assert.assertTrue(
            "RecurringBackup must still declare the persisted name (and a different one is a" +
                " decision, because rows on the device keep the old spelling):\n" +
                backup,
            backup.contains("NAME = \"recurring_backup\""))
        val host = read("src/main/java/uk/xa0/tulkki/app/UiAppHost.kt")
        Assert.assertTrue(
            "the host port must answer with that same constant, never a second spelling:\n" + host,
            host.contains("recurringBackupName(): String = RecurringBackup.NAME"))
    }
}
