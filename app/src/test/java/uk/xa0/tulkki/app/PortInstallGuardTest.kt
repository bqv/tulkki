package uk.xa0.tulkki.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.TreeSet
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test

/**
 * Every port holder that fails loudly must have an install that is really called - and the ones the
 * process owes at start must be lines of {@code TulkkiApplication.onCreate}.
 *
 * <p>This exists because the crash it guards was invisible to every other gate. {@code UiHost} was
 * added with an accessor that throws ("was never installed"), a holder, an error message naming the
 * install, and no {@code UiHost.install(...)} call anywhere in the repository - so the release build
 * died at process start, inside {@code XmppConnectionService.onCreate}, and no JVM test noticed,
 * because no JVM test starts an {@code Application}. The class of defect is one sentence: <em>a
 * holder whose accessor throws is a promise that something installs it; an unkept promise is a crash
 * on the first caller</em>. This test reads the sources and refuses the promise without the call.
 *
 * <p><strong>The sweep this table records</strong> (each row is a decision, not a formality):
 *
 * <ul>
 *   <li>{@code UiHost.install} was the broken one: no call site at all. The measured first reach is
 *       {@code XmppConnectionService.onCreate} &rarr; {@code themePort().applyCustomColors}
 *       ({@code UiTulkkiPorts}, the one {@code :app} class that names {@code :ui} for the island)
 *       &rarr; {@code ThemeHelper.applyCustomColors} &rarr; {@code UiHost.installed()}. The second is
 *       every screen: {@code :ui}'s {@code BaseActivity.onStart} asks the same accessor, and an
 *       activity can be launched with no service in the process at all. Fixed in the composition
 *       root.
 *   <li>{@code EngineHost.install} is called by the {@code XmppTulkkiHost} constructor, which the
 *       ports factory builds from the service's own {@code onCreate}. Every caller of
 *       {@code EngineHost.of}/{@code installed} is behind a service: {@code OutgoingTranslation}'s
 *       no-Context entry point is reached only from {@code MessageAdapter.installHeldTap}, which
 *       binds under an activity that already holds a bound service, and {@code StartupBacklog.run}
 *       is reached from the host itself. Safe.
 *   <li>{@code ViewPorts.install}, {@code PhoneNumberNormalizer.install} and {@code XhtmlBody.install}
 *       were already installed by the composition root, for the reason their comments give: their
 *       callers include static ones that can run before any service exists.
 *   <li>{@code XmppConnectionService.installPortsFactory}/{@code installTrustPort}/
 *       {@code installDataStatics} are the three statics the island's callers reach without a
 *       service; all three are composition-root lines.
 *   <li>{@code FileBackends.install} (from {@code DataStaticsHost.newFileBackend}) and
 *       {@code AvatarReader.install} (from {@code AvatarService}'s constructor) are installed on the
 *       service path on purpose, and are safe because the only way to reach their accessors is
 *       through a service that has already built those objects.
 *   <li>{@code SyncEngine.installSweepSink} takes the same service path, from the host the service
 *       builds (its lazy {@code engine()}); the sink drains that engine's own sweeps, so the service
 *       that built the engine is the only way in.
 *   <li>The port then rewrote six of these holders in Kotlin - {@code EngineHost},
 *       {@code ViewPorts}, {@code PhoneNumberNormalizer}, {@code XhtmlBody}, {@code FileBackends} and
 *       {@code AvatarReader} - with the install and the file each row names unmoved; what changed is
 *       the language the slot and the installer are written in. The recogniser below learned that
 *       spelling in the same commit, because a Java-only one reports every one of those six rows as a
 *       holder the tree no longer declares.
 *   <li>{@code DatabaseReadiness.install} became visible to the recogniser only with the crash fix
 *       ({@code 34cd97ca55}): the Kotlin slot shape is {@code name: Type = ...}, and the file used to
 *       spell {@code lateinit var backendRef: T} (no {@code =}) beside {@code var installed = false}
 *       (no {@code : Type}), so it declared no slot the scan could see. The faithful nullable
 *       {@code DatabaseBackendRef?} slot now matches, and the install is the Java's own
 *       {@code this.databaseBackend = backend} at the head of {@code continueAfterDbInit}
 *       ({@code PostOpenStartup.run}). This one is a <em>tolerant</em> holder: the accessor answers
 *       null before the open rather than throwing, so its "early enough" is not load-bearing.
 * </ul>
 *
 * <p><strong>What this test can and cannot prove.</strong> It proves that every holder installer the
 * tree declares is on the table, and that the file each row names really contains a call to it - so
 * deleting the call, or moving it somewhere the table does not name, fails here. For the rows marked
 * process start it proves more: the call is inside {@code TulkkiApplication.onCreate}'s body, and the
 * platform runs that before any {@code Service}, {@code Activity} or provider callback in the process,
 * which is the ordering argument. What it cannot prove is that a <em>service-path</em> install
 * ({@code EngineHost}, {@code FileBackends}, {@code AvatarReader}) runs before its first caller; that
 * is a runtime property of who builds what and when, and the argument for those three is written
 * above rather than checked here. It is also textual: a call reached through a lambda, reflection, a
 * static import or another spelling is invisible to it, and it reads the declared type's simple name,
 * so a holder renamed without its table row fails loudly instead of silently covering nothing.
 */
class PortInstallGuardTest {

    @Test
    fun everyInstallHolderIsOnTheReviewedTable() {
        val found = holderInstallers(sources())
        val reviewed = TreeSet<String>()
        for (row in TABLE) {
            reviewed.add(row.holder)
        }
        val declared = TreeSet(found.keys)

        val unreviewed = TreeSet(declared)
        unreviewed.removeAll(reviewed)
        val dead = TreeSet(reviewed)
        dead.removeAll(declared)

        Assert.assertTrue(
            "a port holder is installed by a line nobody reviewed - add it to TABLE with the" +
                " file that installs it and the argument that the call is early enough: " +
                unreviewed,
            unreviewed.isEmpty())
        Assert.assertTrue(
            "TABLE names a holder the tree no longer declares - a rename or a deletion must move" +
                " this row rather than leave it silently covering nothing: " +
                dead,
            dead.isEmpty())
        Assert.assertEquals("TABLE has duplicate rows", reviewed.size, TABLE.size)

        val listing = StringBuilder()
        for (entry in TreeMap(found)) {
            listing.append("\n  ").append(entry.key).append(" <- ").append(entry.value)
        }
        println("port install scan: " + found.size + " holders" + listing)
    }

    /**
     * The assertion that red-lights the defect: each row names the file that installs it, and that
     * file really contains the call. A holder whose accessor throws and whose installer is never
     * called is the crash this test was written for; a call moved to a file the table does not name
     * fails here too, and the fix is to name the new file and say why it is early enough.
     */
    @Test
    fun everyReviewedInstallIsCalledFromTheFileItsRowNames() {
        val allSources = sources()
        val missing = ArrayList<String>()
        for (row in TABLE) {
            if (!sourceEndingWith(allSources, row.installer).shape.contains(row.holder + "(")) {
                missing.add(row.holder + " is not called from " + row.installer)
            }
        }
        Assert.assertTrue(
            "a reviewed port install has no call in the file that is supposed to make it - the" +
                " accessor will throw on its first caller:\n  " +
                missing.joinToString("\n  "),
            missing.isEmpty())
    }

    /**
     * The ordering half, for the rows the process itself owes: the install is a line of the
     * composition root's {@code onCreate}. That is the strongest thing a source scan can say about
     * "early enough" - the platform runs {@code Application.onCreate} before any service, activity or
     * provider callback in the process, so a composition-root line is early by construction rather
     * than by argument.
     */
    @Test
    fun theProcessStartInstallsAreLinesOfTheCompositionRootsOnCreate() {
        val root = sourceEndingWith(sources(), COMPOSITION_ROOT)
        val onCreate =
            methodBody(root.shape, "public void onCreate()", "override fun onCreate()")
        val missing = ArrayList<String>()
        var checked = 0
        for (row in TABLE) {
            if (!row.atProcessStart) {
                continue
            }
            checked++
            if (!onCreate.contains(row.holder + "(")) {
                missing.add(row.holder)
            }
        }
        Assert.assertTrue("no process-start row was checked - the table lost them", checked > 0)
        Assert.assertTrue(
            "a port the process owes at start is not installed from TulkkiApplication.onCreate: " +
                missing,
            missing.isEmpty())
    }

    /**
     * The second half of the promise: the error messages that say "never installed" must name an
     * install the table knows. A new accessor that throws this shape and names an install nobody
     * reviewed - or one that does not exist at all, which is the shape UiHost had before its fix -
     * fails here rather than at the owner's first launch.
     */
    @Test
    fun everyMissingInstallMessageNamesAReviewedInstall() {
        val derived = installTokensNamedInMissingMessages(sources())
        val reviewed = TreeSet<String>()
        for (row in TABLE) {
            reviewed.add(row.holder)
        }
        Assert.assertFalse(
            "no \"never installed\" message was found - the marker or the scan is wrong",
            derived.isEmpty())
        val unknown = TreeSet(derived)
        unknown.removeAll(reviewed)
        Assert.assertTrue(
            "an accessor says a port was never installed and names an install no row covers: " +
                unknown,
            unknown.isEmpty())
    }
}

/**
 * One holder, the file whose line installs it, and whether that line must be a process-start line.
 *
 * <p>{@code holder} is {@code Type.method} - the declared type's simple name and the installer's
 * name - because that is exactly the text a call site reads. {@code installer} is a path suffix
 * rather than a repository path, so the same table resolves in the monolith layout and in the
 * split one the boundary harness builds.
 */
private data class Row(val holder: String, val installer: String, val atProcessStart: Boolean)

private const val COMPOSITION_ROOT = "uk/xa0/tulkki/app/TulkkiApplication.java"

private val TABLE: List<Row> =
    listOf(
        // Broken until this commit: nothing called it anywhere. The two reaches are in
        // the class comment; both are ordinary, and the service one was measured.
        Row("UiHost.install", COMPOSITION_ROOT, true),
        // Built together with the engine's host, which needs a service to adapt.
        Row("EngineHost.install", "uk/xa0/tulkki/app/XmppTulkkiHost.java", false),
        Row("ViewPorts.install", COMPOSITION_ROOT, true),
        Row("PhoneNumberNormalizer.install", COMPOSITION_ROOT, true),
        Row("XhtmlBody.install", COMPOSITION_ROOT, true),
        Row("XmppConnectionService.installPortsFactory", COMPOSITION_ROOT, true),
        Row("XmppConnectionService.installTrustPort", COMPOSITION_ROOT, true),
        Row("XmppConnectionService.installDataStatics", COMPOSITION_ROOT, true),
        // Entered the scan with the crash fix (`34cd97ca55`). The recogniser's Kotlin slot shape is
        // `name: Type = ...`; before the fix this file declared `lateinit var backendRef: T` (no
        // `=`) and `var installed = false` (no `: Type`), so its installer wrote no slot the scan
        // could see. The faithful nullable slot now matches. The install is the Java's own
        // `this.databaseBackend = backend`, the first line of `continueAfterDbInit` -
        // `PostOpenStartup.run` here - and it is a *tolerant* holder: `backend()` answers null
        // before the open rather than throwing, so "early enough" is not load-bearing the way it is
        // for a port whose accessor throws.
        Row("DatabaseReadiness.install", "uk/xa0/tulkki/xmpp/services/PostOpenStartup.kt", false),
        // The one file backed by the service's own object graph.
        Row("FileBackends.install", "uk/xa0/tulkki/app/DataStaticsHost.java", false),
        Row("AvatarReader.install", "uk/xa0/tulkki/app/services/AvatarService.java", false),
        // Found by the Kotlin-wide recogniser in the commit that added it: the sink is
        // built on the service path, from the host, and reached only by the engine that
        // same host builds.
        Row("SyncEngine.installSweepSink", "uk/xa0/tulkki/app/XmppTulkkiHost.java", false))

/** The declared package plus the file's simple name, or {@code null} when there is none. */
private val DECLARED_PACKAGE: Pattern = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)")

/**
 * A static method whose name starts with {@code install}. The wildcard before the name spans the
 * return type, which is why it is lazy: the declaration {@code public static XmppConnectionService
 * .TulkkiPorts install(} and the plain {@code static void install(} have to match the same shape.
 */
private val STATIC_INSTALL: Pattern =
    Pattern.compile(
        "(?m)^[ \\t]*(?:public |protected |private )?static\\s+(?:final\\s+)?" +
            "[\\w.<>\\[\\], ?]*?\\b(install\\w*)\\s*\\(")

/**
 * A static mutable slot. Every holder in this tree is the same shape - one {@code static volatile}
 * field and one installer that writes it - which is what makes the holder recognizable from the
 * sources without a list of class names.
 */
private val STATIC_SLOT: Pattern =
    Pattern.compile(
        "(?m)^[ \\t]*(?:public |protected |private )?static\\s+volatile\\s+" +
            "[^;=]*?\\b(\\w+)\\s*;")

/**
 * The Kotlin spelling of the same two shapes, which the port made the common one: a holder is now
 * an {@code object} - or an interface's {@code companion object} - with one {@code var} slot and
 * one {@code @JvmStatic fun install*} that writes it. Neither keyword the Java pair keys on
 * survives that move ({@code static volatile} does not exist in Kotlin), so without these two the
 * recogniser sees no Kotlin holder at all: every reviewed row for one is reported as "the tree no
 * longer declares it", and a new Kotlin holder - {@code SyncEngine.installSweepSink} was one -
 * enters the tree without ever appearing as unreviewed. That is a blind spot in the recogniser,
 * not a row that has moved or gone: the install is still there and still called.
 *
 * <p>{@code @JvmStatic} is the Kotlin spelling of {@code static} and the reason this is safe to
 * look for: the annotation is only legal on a member of an {@code object} or a {@code companion
 * object}, which is exactly the one process-wide slot the Java shape meant. An instance
 * {@code fun install()} - there are two in the tree - cannot carry it and so cannot enter the
 * scan. The slot is any in-file {@code var}/{@code val} assignment of that shape; the write is
 * what makes it a holder, the same test the Java pair applies, which is also what keeps
 * {@code XmppTulkkiHost.install} out.
 */
private val KOTLIN_SLOT: Pattern =
    Pattern.compile(
        "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?\\s+)*" +
            "(?:private |internal |protected |public )?(?:var|val)\\s+" +
            "\\b(\\w+)\\s*(?::[^=\\n]*)=")

private val KOTLIN_INSTALL: Pattern =
    Pattern.compile(
        "(?m)^[ \\t]*@JvmStatic\\s+(?:actual\\s+|open\\s+|override\\s+)?" +
            "fun\\s+(install\\w*)\\s*\\(")

/** A dotted install call named inside an error message: the table keys are its last two parts. */
private val INSTALL_TOKEN: Pattern =
    Pattern.compile("([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\.(install\\w*)")

/**
 * The two ways this tree writes "the slot is empty": {@code never installed} (was/were) and
 * {@code has no implementation}. Both are statements about a build fault, and both name - or are
 * meant to name - the install that fixes them.
 */
private val MISSING_MARKERS = arrayOf("never installed", "has no implementation")

/** One source file: its declared type, its repository path, and its code with comments gone. */
private class Source(val type: String, val path: String, val code: String) {
    /** Comments removed and literals blanked, so braces can be counted and a comment cannot hit. */
    val shape: String = blankLiterals(code)

    fun simpleName(): String = type.substring(type.lastIndexOf('.') + 1)
}

private fun sources(): List<Source> {
    val root = root()
    val sources = ArrayList<Source>()
    for (base in sourceRoots(root)) {
        Files.walk(base).use { walk ->
            for (path in walk.filter { Files.isRegularFile(it) }.sorted().toList()) {
                val name = path.fileName.toString()
                if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                    continue
                }
                val raw = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                val matcher = DECLARED_PACKAGE.matcher(raw)
                if (!matcher.find()) {
                    continue
                }
                val type = matcher.group(1) + "." + name.substring(0, name.lastIndexOf('.'))
                sources.add(
                    Source(
                        type,
                        root.relativize(path).toString().replace('\\', '/'),
                        stripComments(raw)))
            }
        }
    }
    Assert.assertFalse("no sources were read - the root is wrong", sources.isEmpty())
    return sources
}

/**
 * Every {@code src/main/java}-shaped tree: the root's own (the monolith layout) and every
 * module's, two levels deep for {@code :libs}. Build outputs and hidden directories are skipped,
 * which is also what keeps the other agents' extraction copies under {@code .toolchain/} out of
 * the scan.
 */
private fun sourceRoots(root: Path): List<Path> {
    val roots = ArrayList<Path>()
    val atRoot = root.resolve("src/main/java")
    if (Files.isDirectory(atRoot)) {
        roots.add(atRoot)
    }
    for (module in modules(root)) {
        val candidate = module.resolve("src/main/java")
        if (Files.isDirectory(candidate)) {
            roots.add(candidate)
        }
    }
    return roots
}

private fun modules(root: Path): List<Path> {
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

private fun skipped(directory: Path): Boolean {
    val name = directory.fileName.toString()
    return name.startsWith(".") || name == "build" || name == "src"
}

/**
 * Every holder installer the tree declares, as {@code Type.method} (the declared type's simple
 * name and the method's name) to the repository path that declares it.
 *
 * <p>The recognizer is the shape rather than a list: an install whose name starts with
 * {@code install} and whose body writes a slot declared in the same file - a Java {@code static}
 * method over a {@code static volatile} field, or its Kotlin spelling, {@code @JvmStatic
 * fun install} over a {@code var} in the same {@code object}/{@code companion object}. The
 * Kotlin shape needs a block body to count a write in; an expression-bodied install writes
 * nothing the scan can see. {@code XmppTulkkiHost.install} is excluded on purpose - it returns a
 * new adapter and writes no slot, so it is the factory, not a holder.
 */
private fun holderInstallers(sources: List<Source>): Map<String, String> {
    val holders = LinkedHashMap<String, String>()
    for (source in sources) {
        val slots = TreeSet<String>()
        for (slotPattern in listOf(STATIC_SLOT, KOTLIN_SLOT)) {
            val slot = slotPattern.matcher(source.shape)
            while (slot.find()) {
                slots.add(slot.group(1))
            }
        }
        if (slots.isEmpty()) {
            continue
        }
        for (installPattern in listOf(STATIC_INSTALL, KOTLIN_INSTALL)) {
            val installer = installPattern.matcher(source.shape)
            while (installer.find()) {
                // A Kotlin declaration can be expression-bodied, and there is nothing to count a
                // write inside; the Kotlin shape is a block body or it is not this tree's holder.
                // {@code XmppTulkkiHost.install} - the factory - is expression-bodied for exactly
                // this reason, and the Java path keeps its own body finder.
                val body =
                    if (installPattern === KOTLIN_INSTALL) {
                        kotlinBlockBodyFrom(source.shape, installer.end() - 1)
                    } else {
                        methodBodyFrom(source.shape, installer.end() - 1)
                    }
                if (body == null) {
                    continue
                }
                var writesSlot = false
                for (slotName in slots) {
                    if (Pattern.compile("\\b" + Pattern.quote(slotName) + "\\s*=[^=]")
                            .matcher(body)
                            .find()) {
                        writesSlot = true
                        break
                    }
                }
                if (writesSlot) {
                    holders[source.simpleName() + "." + installer.group(1)] = source.path
                }
            }
        }
    }
    return holders
}

/**
 * The block body of the Kotlin declaration whose parameter list opens at {@code openParen}, or
 * {@code null} when it is expression-bodied ({@code fun install(...) = ...}) or has no body at
 * all. The matching close paren is found by counting, and the body must be the next significant
 * thing after it - an intervening {@code : Type} return clause is allowed - so the {@code {}} a
 * later declaration owns can never be mistaken for this one's.
 */
private fun kotlinBlockBodyFrom(shape: String, openParen: Int): String? {
    var depth = 0
    var index = openParen
    while (index < shape.length) {
        val current = shape[index]
        if (current == '(') {
            depth++
        } else if (current == ')') {
            depth--
            if (depth == 0) {
                break
            }
        }
        index++
    }
    index++
    while (index < shape.length && Character.isWhitespace(shape[index])) {
        index++
    }
    if (index < shape.length && shape[index] == ':') {
        while (index < shape.length && shape[index] != '{' && shape[index] != '=') {
            index++
        }
    }
    if (index < shape.length && shape[index] == '{') {
        return methodBodyFrom(shape, index)
    }
    return null
}

/**
 * The install tokens named by the statements that complain about an empty slot, normalized to
 * {@code Type.method} so the table keys and the message text can be compared. The statement is
 * found by its marker and bounded by the nearest {@code ;}/{@code {} / {@code }} around it, which
 * is enough because every one of these messages is one string concatenation inside one
 * {@code throw} or {@code return}.
 */
private fun installTokensNamedInMissingMessages(sources: List<Source>): TreeSet<String> {
    val tokens = TreeSet<String>()
    for (source in sources) {
        for (marker in MISSING_MARKERS) {
            var index = 0
            while (true) {
                val at = source.code.indexOf(marker, index)
                if (at < 0) {
                    break
                }
                index = at + 1
                val from =
                    maxOf(
                        maxOf(source.code.lastIndexOf(';', at), source.code.lastIndexOf('{', at)),
                        source.code.lastIndexOf('}', at)) + 1
                val to = source.code.indexOf(';', at)
                val statement =
                    source.code.substring(from, if (to < 0) source.code.length else to)
                val token = INSTALL_TOKEN.matcher(statement)
                while (token.find()) {
                    val parts = token.group(0).split('.')
                    tokens.add(parts[parts.size - 2] + "." + parts[parts.size - 1])
                }
            }
        }
    }
    return tokens
}

/**
 * The source whose repository path ends with {@code suffix}; there must be exactly one.
 *
 * <p>A row names the path its text lives at, never the language it is written in, and the Kotlin
 * port moves a file from {@code .java} to {@code .kt} without moving the path - so when the named
 * spelling is absent the sibling spelling is tried before the failure. A file that is genuinely
 * gone still fails, whichever extension it was asked for.
 */
private fun sourceEndingWith(sources: List<Source>, suffix: String): Source {
    val named = oneEndingWith(sources, suffix)
    if (named != null) {
        return named
    }
    val sibling = siblingSource(suffix)
    if (sibling != suffix) {
        val under = oneEndingWith(sources, sibling)
        if (under != null) {
            return under
        }
    }
    Assert.fail("no source ends with " + suffix)
    throw AssertionError("unreachable")
}

/** One source whose repository path ends with {@code suffix}, or {@code null} when there is none. */
private fun oneEndingWith(sources: List<Source>, suffix: String): Source? {
    var found: Source? = null
    for (source in sources) {
        if (!source.path.endsWith(suffix)) {
            continue
        }
        if (found != null) {
            // Not a lazily-built message: `found` may be null on the first match, so the check
            // comes before anything reads it.
            Assert.fail(
                "two sources end with " + suffix + ": " + found.path + " and " + source.path)
        }
        found = source
    }
    return found
}

/** The same path under the other source extension, or the path itself when it has neither. */
private fun siblingSource(relative: String): String {
    if (relative.endsWith(".java")) {
        return relative.substring(0, relative.length - ".java".length) + ".kt"
    }
    if (relative.endsWith(".kt")) {
        return relative.substring(0, relative.length - ".kt".length) + ".java"
    }
    return relative
}

/**
 * The body of the named method, from its opening brace to the matching closing one. The first of
 * {@code declarations} the shape carries wins: the composition root is the one file this test
 * names that the Kotlin port moves between languages without moving its path, so the Java
 * declaration {@code public void onCreate()} and the Kotlin one {@code override fun onCreate()}
 * are both looked for rather than the test pinning one language's spelling of the same entry
 * point.
 */
private fun methodBody(shape: String, vararg declarations: String): String {
    for (declaration in declarations) {
        val start = shape.indexOf(declaration)
        if (start >= 0) {
            return methodBodyFrom(shape, start)
        }
    }
    Assert.fail("no " + declarations.joinToString(" or ") + " in the composition root")
    throw AssertionError("unreachable")
}

/**
 * The braced body that follows {@code from}, with nesting counted. The text must already have its
 * comments removed and its literals blanked, or a brace inside a message would end the body early.
 */
private fun methodBodyFrom(shape: String, from: Int): String {
    val open = shape.indexOf('{', from)
    Assert.assertTrue("no body follows index " + from, open >= 0)
    var depth = 0
    for (index in open until shape.length) {
        val current = shape[index]
        if (current == '{') {
            depth++
        } else if (current == '}') {
            depth--
            if (depth == 0) {
                return shape.substring(open, index + 1)
            }
        }
    }
    throw AssertionError("unterminated body at index " + open)
}

/**
 * The repository root, found by its marker. {@code user.dir} is the module Gradle runs the test
 * in, and {@code settings.gradle.kts} is above it in either layout.
 */
private fun root(): Path {
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

/** Comments removed, literals left alone. Java and Kotlin share the two comment forms. */
private fun stripComments(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    val length = text.length
    while (index < length) {
        val current = text[index]
        if (current == '"' || current == '\'') {
            // A Kotlin raw string first: its quotes are not escapes and it may span lines.
            if (text.startsWith("\"\"\"", index)) {
                val close = text.indexOf("\"\"\"", index + 3)
                val end = if (close < 0) length else close + 3
                out.append(text, index, end)
                index = end
                continue
            }
            val end = literalEnd(text, index)
            out.append(text, index, end)
            index = end
            continue
        }
        if (text.startsWith("//", index)) {
            val close = text.indexOf('\n', index)
            index = if (close < 0) length else close
            continue
        }
        if (text.startsWith("/*", index)) {
            val close = text.indexOf("*/", index + 2)
            index = if (close < 0) length else close + 2
            continue
        }
        out.append(current)
        index++
    }
    return out.toString()
}

/** The same, with each literal replaced by an empty one of its own kind. */
private fun blankLiterals(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    val length = text.length
    while (index < length) {
        val current = text[index]
        if (current == '"' || current == '\'') {
            if (text.startsWith("\"\"\"", index)) {
                val close = text.indexOf("\"\"\"", index + 3)
                index = if (close < 0) length else close + 3
                out.append("\"\"")
                continue
            }
            val end = literalEnd(text, index)
            out.append(if (current == '"') "\"\"" else "''")
            index = end
            continue
        }
        out.append(current)
        index++
    }
    return out.toString()
}

/** The index just past the literal that starts at {@code start}, escapes honoured. */
private fun literalEnd(text: String, start: Int): Int {
    var cursor = start + 1
    val quote = text[start]
    while (cursor < text.length) {
        val current = text[cursor]
        if (current == '\\') {
            cursor += 2
            continue
        }
        if (current == quote || current == '\n') {
            return cursor + 1
        }
        cursor++
    }
    return text.length
}
