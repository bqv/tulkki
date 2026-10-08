package uk.xa0.tulkki.leaks

import org.junit.Assert
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayList

/**
 * S5-4's three source scans: the retirements, enforced as a ratchet rather than as a commit message.
 *
 * <p>"Design: the data layer" §5.1-§5.3 each end with a *mechanical proof* - a source scan in the
 * `LeakGuardScanTest` shape - and §6 step 4 requires them to be **added in the same commit as the
 * deletions**, so that their first run is red on the code they exist to remove and green once it has
 * gone. There is no behaviour left to pin for any of them: the watermark is replaced by a cursor, the
 * floor by a gap row, the latch by a persisted `OPEN` region and `anyCatchup` by the ledger, so the
 * property that would catch a reintroduction is "the name is not in our sources any more". Each scan
 * names only what was retired - in particular `anyCatchup` alone, never `catchup`, because three
 * members of `MessageArchiveService`'s catch-up family (`inCatchup`, `isCatchingUp`,
 * `isCatchupInProgress`) stay and a scan for the shorter word would delete working code.
 *
 * <p>The scan reads the tree the same way the leak guard does: from `settings.gradle.kts` upward-resolved
 * root, over every file under a {@code src/main/java} or {@code src/tulkki/java} directory, hidden
 * directories skipped so a lane's own checkout under `.toolchain/` is not read.
 */
class RetiredHeuristicScanTest {

    /** The three retirements, one row per scan, as the tokens that must not reappear. */
    private val WATERMARK =
            listOf(
                    "KEY_HISTORY_WATERMARK",
                    "historyWatermark",
                    "setHistoryWatermark",
                    "newestHistoryCandidates")

    private val FLOOR = listOf("MIN_INTERVAL_MILLIS", "LAST_STARTED")

    /**
     * The latch, its trigger and the run entry point. `anyCatchup` is named alone; `CatchupFinishedHook`
     * is the mechanism the retired `processFin` trigger used, and `StartupBacklog.run` is the pass that
     * trigger called - the seam that replaced it is `SyncEvents.onMamFin`.
     */
    private val LATCH =
            listOf(
                    "anyCatchup",
                    "CatchupFinishedHook",
                    "AtomicBoolean RUNNING",
                    "RUNNING.compareAndSet",
                    "StartupBacklog.run(")

    @Test
    fun theTranslationWatermarkIsGone() {
        assertAbsent(WATERMARK)
    }

    @Test
    fun theFiveSecondFloorIsGone() {
        assertAbsent(FLOOR)
    }

    @Test
    fun theOncePerCatchUpLatchAndItsTriggerAreGone() {
        assertAbsent(LATCH)
    }

    private fun assertAbsent(tokens: List<String>) {
        val sources = mainSources()
        Assert.assertFalse("no sources were read - the root is wrong", sources.isEmpty())
        val hits = StringBuilder()
        var found = 0
        for (source in sources) {
            val text =
                    stripComments(
                            String(
                                    Files.readAllBytes(Path.of(source)),
                                    StandardCharsets.UTF_8))
            val lines = text.split("\n")
            for (at in 0 until lines.size) {
                for (token in tokens) {
                    if (!lines[at].contains(token)) {
                        continue
                    }
                    found++
                    hits.append("  ")
                            .append(source)
                            .append(':')
                            .append(at + 1)
                            .append(": ")
                            .append(lines[at].trim())
                            .append('\n')
                }
            }
        }
        System.out.println(
                "retired-heuristic scan: "
                        + sources.size
                        + " main sources, "
                        + tokens
                        + " -> "
                        + found
                        + " hits")
        Assert.assertEquals(
                "a retired heuristic is back in src/main (docs/MIGRATION.md, \"Design: the data layer\""
                        + " §5; S5-4's scans):\n"
                        + hits,
                0,
                found)
    }

    /**
     * The scan looks at code, not prose, which is the same trade the leak guard makes: these names are
     * *supposed* to appear in the comment that records the retirement, and a scan that failed on its
     * own explanation would be deleted within a week. String literals are kept, so a reintroduction
     * cannot hide inside one; the only thing removed is {@code //} and block comments, and a block
     * comment's newlines are preserved so the line numbers in a failure still point at the source.
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        val length = text.length
        while (index < length) {
            val current = text[index]
            if (current == '"') {
                var cursor = index + 1
                while (cursor < length) {
                    if (text[cursor] == '\\') {
                        cursor += 2
                        continue
                    }
                    if (text[cursor] == '"' || text[cursor] == '\n') {
                        cursor++
                        break
                    }
                    cursor++
                }
                out.append(text, index, Math.min(cursor, length))
                index = Math.min(cursor, length)
                continue
            }
            if (current == '/' && index + 1 < length && text[index + 1] == '/') {
                var cursor = index
                while (cursor < length && text[cursor] != '\n') {
                    cursor++
                }
                out.append('\n')
                index = cursor
                continue
            }
            if (current == '/' && index + 1 < length && text[index + 1] == '*') {
                val close = text.indexOf("*/", index + 2)
                val end = if (close < 0) length else close + 2
                for (at in index until end) {
                    if (text[at] == '\n') {
                        out.append('\n')
                    }
                }
                index = end
                continue
            }
            out.append(current)
            index++
        }
        return out.toString()
    }

    /** Every {@code .java}/{@code .kt} file under a {@code src/main/java} or {@code src/tulkki/java}. */
    private fun mainSources(): List<String> {
        val root = projectRoot()
        val sources = ArrayList<String>()
        val roots = ArrayList<Path>()
        for (tree in arrayOf("src/main/java", "src/tulkki/java")) {
            val atRoot = root.resolve(tree)
            if (Files.isDirectory(atRoot)) {
                roots.add(atRoot)
            }
            for (module in modules(root)) {
                val candidate = module.resolve(tree)
                if (Files.isDirectory(candidate)) {
                    roots.add(candidate)
                }
            }
        }
        for (base in roots) {
            Files.walk(base).use { walk ->
                for (path in walk.filter { Files.isRegularFile(it) }.sorted().toList()) {
                    val name = path.fileName.toString()
                    if (name.endsWith(".java") || name.endsWith(".kt")) {
                        sources.add(path.toString())
                    }
                }
            }
        }
        return sources
    }

    /**
     * The directories that can hold a module: the root's own children and their children. Build
     * outputs and the hidden directories (Gradle's and the harness's caches, {@code .git} and a
     * lane's own checkout under {@code .toolchain/}) are skipped.
     */
    private fun modules(root: Path): List<Path> {
        val modules = ArrayList<Path>()
        Files.list(root).use { children ->
            for (child in children.filter { Files.isDirectory(it) }.sorted().toList()) {
                val name = child.fileName.toString()
                if (name.startsWith(".") || name == "build" || name == "src") {
                    continue
                }
                modules.add(child)
                Files.list(child).use { grandchildren ->
                    for (grandchild in grandchildren.filter { Files.isDirectory(it) }.sorted().toList()) {
                        val inner = grandchild.fileName.toString()
                        if (inner.startsWith(".") || inner == "build") {
                            continue
                        }
                        modules.add(grandchild)
                    }
                }
            }
        }
        return modules
    }

    /** The checkout root, found the way the leak guard finds it: the nearest `settings.gradle.kts`. */
    private fun projectRoot(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            val current = directory ?: break
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                    || Files.isRegularFile(current.resolve("settings.gradle"))) {
                return current
            }
            directory = current.parent
        }
        throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
    }
}
