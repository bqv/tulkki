package uk.xa0.tulkki.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList
import java.util.TreeSet
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test

/**
 * The names the operating system holds.
 *
 * <p>One sentence decides every case in the rename's leftovers: <em>a string the compiler resolves is
 * a name and moves; a string the operating system stored on the device is data and does not.</em> The
 * compiler resolves nothing about an {@code AlarmManager} action, a posted notification's
 * {@code PendingIntent} action or extra, or a call's Telecom {@code Bundle} keys: an app update is not
 * an uninstall, so the platform hands them back to the new build still carrying the text they were
 * armed with. These are therefore not ours to tidy - and the rename tidy did tidy them once, which
 * is why they are pinned here rather than left to be remembered (`docs/MIGRATION.md` "The literal
 * audit" F2-F5, `docs/MIGRATION.md`, "The rename's leftovers"). Nine are pinned: the six the first
 * rename wrote the old root over, still carrying their {@code eu.siacs.conversations.*} text, and
 * the three the 3.8 root rename would write over, already carrying {@code uk.xa0.app.*}.
 *
 * <p>The test reads the <em>sources</em> rather than the compiled classes, on purpose. A
 * {@code static final String} with a literal initializer is a compile-time constant, so javac inlines
 * it into the asserting class: {@code assertEquals(<the pinned text>, ACTION_EXPIRE_MESSAGES)} compiles
 * into a comparison of two literals and can never fail - a green gate over exactly the change it exists
 * to refuse. What has to survive is the text the tree ships, and the declaration form with it: each of
 * the nine must still be a compile-time constant ({@code const val} or {@code static final String}),
 * because a value read at run time is one a later edit can move. The rename tools that once held these
 * strings are deleted; the pins are asserted against the tree and this test's own constants.
 *
 * <p>This test converts to Kotlin and the file it is names itself with moves with it: [SELF_NAME] is
 * the Kotlin spelling, or the sweep below would walk its own source and find the forbidden forms it
 * exists to write down.
 */
class OsHeldNamesTest {

    /** One pinned string: where it is declared, the constant that holds it, and the exact text. */
    private data class Pin(val file: String, val constant: String, val value: String)

    private companion object {

        const val SELF_NAME = "OsHeldNamesTest.kt"

        /**
         * The identity the platform holds. A different applicationId is a different app - the SQLCipher
         * database, the Keystore-backed DeepSeek key and the OMEMO identity all become invisible - so
         * it is pinned here rather than read out of a tool. The literal is safe in this file now: the
         * rename tool that would have rewritten it is deleted, and the naming ratchet bans the old
         * product words, never our own root.
         */
        const val APPLICATION_ID = "uk.xa0.tulkki"

        /**
         * The roots `proguard-rules.pro` must keep. Each is named by a `-keep`/`-keepclassmembers`
         * line R8 reads; a rule naming a root the tree no longer carries is silently useless, and a
         * rule that goes missing lets shrinking strip the class it was written for. The list is this
         * test's own, so it does not name a table inside a tool that no longer exists.
         */
        val KEPT_ROOTS: List<String> =
            listOf(
                "uk.xa0.**",
                "uk.xa0.tulkki.data.**_Impl",
                "uk.xa0.tulkki.annotation.XmlElement",
                "uk.xa0.tulkki.app.http.services.**")

        const val SERVICE =
            "src/main/java/uk/xa0/tulkki/xmpp/services/XmppConnectionService.java"
        const val ACTIVITY = "src/main/java/uk/xa0/tulkki/ui/ConversationListActivity.java"
        const val TELECOM =
            "src/main/java/uk/xa0/tulkki/app/services/CallIntegrationConnectionService.java"
        const val START_CONVERSATION =
            "src/main/java/uk/xa0/tulkki/ui/StartConversationActivity.java"

        /** An alarm armed before the update is delivered after it, action string and all. */
        val ALARMS: List<Pin> =
            listOf(
                Pin(SERVICE, "ACTION_EXPIRE_MESSAGES", "eu.siacs.conversations.EXPIRE_MESSAGES"),
                Pin(
                    SERVICE,
                    "ACTION_POST_CONNECTIVITY_CHANGE",
                    "eu.siacs.conversations.POST_CONNECTIVITY_CHANGE"))

        /** A posted notification is not cancelled by the update; its tap comes back keyed the old way. */
        val NOTIFICATION: List<Pin> =
            listOf(
                Pin(ACTIVITY, "ACTION_VIEW_CONVERSATION", "eu.siacs.conversations.action.VIEW"),
                Pin(ACTIVITY, "EXTRA_DOWNLOAD_UUID", "eu.siacs.conversations.download_uuid"))

        /** The Bundle keys a call in flight was built with, handed to and read back from Telecom. */
        val TELECOM_KEYS: List<Pin> =
            listOf(
                Pin(TELECOM, "EXTRA_ADDRESS", "eu.siacs.conversations.address"),
                Pin(TELECOM, "EXTRA_SESSION_ID", "eu.siacs.conversations.sid"))

        /**
         * The three the *root* rename would have written over, pinned beside the six for the same reason.
         *
         * <p>They already carry the {@code uk.xa0.app.} spelling the previous pass gave them, and the pass
         * that moves {@code uk.xa0.app} to {@code uk.xa0.tulkki.app} rewrites any string that starts with a
         * package root. {@code ACTION_DESTROY_MUC} is an Intent action a posted notification was armed with,
         * {@code EXTRA_AS_QUOTE} rides share intents, and {@code EXTRA_INVITE_URI} crosses activities in a
         * Bundle the platform re-delivers. All three are in {@code PROTECTED_STRINGS}; the ruling was to
         * protect all three rather than trace each one to its PendingIntent site.
         */
        val ROOT_RENAMED: List<Pin> =
            listOf(
                Pin(ACTIVITY, "ACTION_DESTROY_MUC", "uk.xa0.app.DESTROY_MUC"),
                Pin(ACTIVITY, "EXTRA_AS_QUOTE", "uk.xa0.app.as_quote"),
                Pin(START_CONVERSATION, "EXTRA_INVITE_URI", "uk.xa0.app.invite_uri"))

        /**
         * Every pin, built here and not at the top of the file: the companion's properties initialize in
         * declaration order, so an {@code ALL_OS_HELD = all()} above [ROOT_RENAMED] would read a null list
         * and fail the class with an initializer error rather than failing a test.
         */
        val ALL_OS_HELD: List<Pin> = all()

        /**
         * What the rename actually wrote over each pinned string: it applied its root mapping to these
         * strings as if they were package names. Spelled out here so the sweep below refuses them by name;
         * the sweep skips this file, which is where they are written down.
         *
         * <p>The first six are the previous pass's spelling of the six pinned {@code eu.siacs.conversations.*}
         * values, and the last three are this pass's spelling of [ROOT_RENAMED]: the same hole, one
         * table later. Both tables' entries are here because a protected string has two ways to come back -
         * the old root's rewrite and this one's.
         */
        val RENAMED_FORMS: List<String> =
            listOf(
                "uk.xa0.tulkki.app.EXPIRE_MESSAGES",
                "uk.xa0.tulkki.app.POST_CONNECTIVITY_CHANGE",
                "uk.xa0.tulkki.app.action.VIEW",
                "uk.xa0.tulkki.app.download_uuid",
                "uk.xa0.tulkki.app.address",
                "uk.xa0.tulkki.app.sid",
                "uk.xa0.tulkki.app.DESTROY_MUC",
                "uk.xa0.tulkki.app.as_quote",
                "uk.xa0.tulkki.app.invite_uri")

        /**
         * The tree the app ships: every source root, the manifest, the proguard rules, the build
         * script and the resources. Nothing outside it can resurrect one of these names in the app.
         */
        val RENAME_SCOPE: List<String> =
            listOf(
                "src/main/java",
                "src/tulkki/java",
                "src/test/java",
                "src/main/AndroidManifest.xml",
                "src/tulkki/AndroidManifest.xml",
                "proguard-rules.pro",
                "build.gradle.kts",
                "src/main/res",
                "src/tulkki/res")

        fun all(): List<Pin> {
            val pins = ArrayList(ALARMS)
            pins.addAll(NOTIFICATION)
            pins.addAll(TELECOM_KEYS)
            pins.addAll(ROOT_RENAMED)
            return pins.toList()
        }

        fun assertPinned(pins: List<Pin>) {
            for (pin in pins) {
                Assert.assertEquals(
                    "the OS holds " + pin.constant + " with this text", pin.value, declared(pin))
            }
        }

        /** The literal the named constant is initialized with, read out of the file that declares it. */
        fun declared(pin: Pin): String {
            val source = read(locate(pin.file))
            val matcher =
                Pattern.compile(
                        "\\b" + Pattern.quote(pin.constant) + "\\s*=\\s*\"([^\"]*)\"")
                    .matcher(source)
            Assert.assertTrue(
                "no literal declaration of " + pin.constant + " in " + pin.file,
                matcher.find())
            return matcher.group(1)
        }

        /** Every forbidden needle that occurs in {@code file}, as {@code path: needle} lines. */
        fun hits(file: Path, needles: List<String>): List<String> {
            // Latin-1 on purpose: it maps the bytes 1:1 to chars, so an ASCII needle is found in any
            // file and a resource the JVM cannot decode as UTF-8 cannot fail the sweep.
            val text = String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
            val found = ArrayList<String>()
            for (needle in needles) {
                if (text.contains(needle)) {
                    found.add("" + file + ": " + needle)
                }
            }
            return found
        }

        fun read(file: Path): String = String(Files.readAllBytes(file), StandardCharsets.UTF_8)

        /**
         * Every place a repository-relative path names, in either layout.
         *
         * <p>Before the module split a path like {@code src/main/java} or {@code src/main/res} named one
         * tree at the repository root; after it, the same tree is the union of every module's - and these
         * assertions are about the <em>whole</em> app, so returning only the first match would check one
         * module and pass while the rest of the tree went unread. {@code :libs} is two directories down
         * ({@code libs/annotation}), which is why the walk is two levels deep.
         */
        fun locateAll(relative: String): List<Path> {
            val checkoutRoot = root()
            val found = ArrayList<Path>()
            val direct = checkoutRoot.resolve(relative)
            if (Files.exists(direct)) {
                found.add(direct)
            }
            for (module in modules(checkoutRoot)) {
                val candidate = module.resolve(relative)
                if (Files.exists(candidate)) {
                    found.add(candidate)
                }
            }
            return found
        }

        /**
         * One file a repository-relative path names, wherever the split put it.
         *
         * <p>A pin names the path its text lives at, never the language it is written in, and the Kotlin
         * port moves a file from `.java` to `.kt` without moving the path - so when the named spelling is
         * absent the sibling spelling is tried before the assertion fires. A file that is genuinely gone
         * still fails, whichever extension it was asked for: `locateAll` answers nothing for both.
         */
        fun locate(relative: String): Path {
            // `locateAll` answers a read-only `List`, so the sibling lookup replaces the empty
            // result rather than mutating it; the value the assertion and the caller see is the
            // same one `addAll` used to build.
            val named = locateAll(relative)
            val found = if (named.isEmpty()) locateAll(siblingSource(relative)) else named
            Assert.assertFalse("nothing at " + relative + " under " + root(), found.isEmpty())
            return found[0]
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

        /**
         * Every build file that could declare an identity line: the root's and each module's, because
         * after the split the {@code applicationId} is {@code :app}'s while the root's build file is not
         * an Android one any more. Scanning all of them is also the stronger assertion - a stray
         * {@code applicationId} in any module is a different app there.
         *
         * <p>The file is found under either DSL spelling, the Kotlin DSL first. `45f16c1335` moved
         * every build script to `build.gradle.kts`; a selector still asking only for `build.gradle`
         * answered nothing at all, so the scan this masks covered no file - the same defect as the
         * root locator above, one layer down.
         */
        fun buildFiles(): List<Path> {
            val checkoutRoot = root()
            val files = ArrayList<Path>()
            val atRoot = buildFileIn(checkoutRoot)
            if (atRoot != null) {
                files.add(atRoot)
            }
            for (module in modules(checkoutRoot)) {
                val candidate = buildFileIn(module)
                if (candidate != null) {
                    files.add(candidate)
                }
            }
            return files
        }

        /** The directory's build script under either DSL spelling, the Kotlin DSL first. */
        fun buildFileIn(directory: Path): Path? {
            for (name in listOf("build.gradle.kts", "build.gradle")) {
                val candidate = directory.resolve(name)
                if (Files.isRegularFile(candidate)) {
                    return candidate
                }
            }
            return null
        }

        /**
         * The directories that can hold a module: the root's own children and their children. Build
         * outputs and hidden directories (Gradle's and the harness's caches, {@code .git}) are skipped,
         * and {@code src} is skipped because the root's own tree is added by the caller.
         */
        fun modules(root: Path): List<Path> {
            val out = ArrayList<Path>()
            Files.list(root).use { first ->
                for (directory in first.filter { Files.isDirectory(it) }.sorted().toList()) {
                    if (skipped(directory)) {
                        continue
                    }
                    out.add(directory)
                    Files.list(directory).use { second ->
                        for (nested in second.filter { Files.isDirectory(it) }.sorted().toList()) {
                            if (!skipped(nested)) {
                                out.add(nested)
                            }
                        }
                    }
                }
            }
            return out
        }

        fun skipped(directory: Path): Boolean {
            val name = directory.fileName.toString()
            return name.startsWith(".") || name == "build" || name == "src"
        }

        /**
         * The repository root, found by its <em>marker</em> rather than by a source directory.
         *
         * <p>{@code settings.gradle.kts} is at the repository root in both layouts; {@code src/main/java} is
         * at the root only before the module split and in <em>every module</em> afterwards, so the walk
         * this used to do stopped at the module Gradle runs the test in and resolved every path against
         * that module. The suite runs in both layouts (the boundary harness builds the split tree from
         * this one), so the locator must be right in both.
         */
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
    fun theAlarmActionsAreTheTextThePlatformHolds() {
        assertPinned(ALARMS)
    }

    @Test
    fun theNotificationActionAndExtraAreTheTextThePlatformHolds() {
        assertPinned(NOTIFICATION)
    }

    @Test
    fun theTelecomBundleKeysAreTheTextTelecomHolds() {
        assertPinned(TELECOM_KEYS)
    }

    @Test
    fun theRootRenamedStringsAreTheTextThePlatformHolds() {
        assertPinned(ROOT_RENAMED)
    }

    @Test
    fun everyOsHeldNameIsACompileTimeConstantTheTreeDeclares() {
        // The subject is the tree, not a rename tool. The platform hands these strings back only if
        // the build still declares them byte for byte, and a compile-time constant is what keeps the
        // value from becoming something read at run time; the declaration form and the text are
        // asserted together, out of the file the pin names.
        for (pin in ALL_OS_HELD) {
            val source = read(locate(pin.file))
            val matcher =
                Pattern.compile(
                        "(?m)^[ \\t]*(?:(?:public|private|protected|internal|expect|actual)\\s+)*" +
                            "(?:const\\s+val|static\\s+final\\s+String)\\s+" +
                            Pattern.quote(pin.constant) + "\\s*=\\s*\"([^\"]*)\"")
                    .matcher(source)
            Assert.assertTrue(
                "no compile-time constant declaration of " + pin.constant + " in " + pin.file,
                matcher.find())
            Assert.assertEquals(
                "the OS holds " + pin.constant + " with this text", pin.value, matcher.group(1))
        }
    }

    @Test
    fun noRenamedFormSurvivesAnywhereTheNextPassWouldRead() {
        val problems = ArrayList<String>()
        for (entry in RENAME_SCOPE) {
            for (path in locateAll(entry)) {
                if (Files.isDirectory(path)) {
                    Files.walk(path).use { files ->
                        for (file in files.filter { Files.isRegularFile(it) }.toList()) {
                            if (SELF_NAME == file.fileName.toString()) {
                                // This file is where the forbidden spellings are written down.
                                continue
                            }
                            problems.addAll(hits(file, RENAMED_FORMS))
                        }
                    }
                } else if (Files.isRegularFile(path)) {
                    problems.addAll(hits(path, RENAMED_FORMS))
                }
            }
        }
        Assert.assertTrue(
            "the rename's spelling of an OS-held string is back in the tree:\n  " +
                problems.joinToString("\n  "),
            problems.isEmpty())
    }

    @Test
    fun theApplicationIdIsStillTheOneTheOwnerHasInstalled() {
        // The same kind of string, one level up: a package name the platform holds. A different
        // applicationId is a different app, and the SQLCipher database, the Keystore-backed DeepSeek
        // key and the OMEMO identity all become invisible. The expected value is this test's own
        // constant now that the rename tool that used to supply it is deleted; a literal here is safe
        // because nothing rewrites this tree any more, and the assertion reads the *sources* because a
        // comparison against the compiled constant would javac-inline into a tautology.
        // every build file in either layout: after the split the applicationId is :app's, and the
        // root build file is not an Android one any more
        val declared = TreeSet<String>()
        for (build in buildFiles()) {
            // A *declaration*, not the word: the lookbehind refuses a quoted `"applicationId"` (a
            // `makeResValueKey` argument) and a `variant.applicationId` API call, and the capture
            // cannot cross a line - otherwise the first would swallow everything up to the next
            // quote in the file and put a fragment of the build script in `declared`.
            val matcher =
                Pattern.compile("(?<![\"\\w.])applicationId(?:\\s*=\\s*|\\s+)\"([^\"\\n]*)\"")
                    .matcher(read(build))
            while (matcher.find()) {
                declared.add(matcher.group(1))
            }
        }
        Assert.assertEquals(
            "the build files disagree about the applicationId",
            setOf(APPLICATION_ID),
            declared)
    }

    @Test
    fun theIdentityLineAndTheKeptRootsAreTheOnesTheTreeDeclares() {
        // What the rename tool's masks used to guard, guarded now against the tree that ships. R8
        // reads proguard-rules.pro: a keep rule naming a root the tree no longer carries is silently
        // useless, and a rule that goes missing lets shrinking strip the class it was written for.
        // The roots are this test's own list, not a table inside a deleted tool.
        val proguard = read(locate("proguard-rules.pro"))
        for (kept in KEPT_ROOTS) {
            Assert.assertTrue(
                "proguard-rules.pro keeps nothing under " + kept,
                proguard.split("\n").any { it.trimStart().startsWith("-keep") && it.contains(kept) })
        }
        // And the identity line stays :app's alone: a different module declaring an applicationId is
        // a different app there.
        val appBuild = buildFileIn(root().resolve("app"))
        if (appBuild == null) {
            Assert.fail(":app has no build script to declare the applicationId")
            return
        }
        val declaring =
            buildFiles().filter { build ->
                Pattern.compile("(?m)^[ \\t]*applicationId\\b").matcher(read(build)).find()
            }
        Assert.assertEquals(
            "the applicationId is declared outside :app's build script", listOf(appBuild), declaring)
    }
}
