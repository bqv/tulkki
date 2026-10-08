package uk.xa0.tulkki.data

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Locating the checkout from a unit test.
 *
 * <p>AGP runs unit tests with the module directory as the working directory, so a test that wants
 * another module's sources - or `proguard-rules.pro` - has to walk up. Two of the tests in
 * this package read files that are not on the classpath (`DatabaseBackend.java`,
 * `HistoryDatabase.kt`, `proguard-rules.pro`), and a test that silently scanned
 * nothing would be the worst possible result: it would look green while proving nothing. So the
 * lookup is a positive search for the checkout root and it throws with the actual working
 * directory rather than returning an empty list.
 */
object RepoFiles {

    /** The checkout root, found from wherever the test task put us. */
    fun root(): Path {
        val start = File("").absoluteFile.toPath()
        var p: Path? = start
        while (p != null) {
            // Either marker: the build scripts are the Kotlin DSL, so the root carries
            // `settings.gradle.kts` and no longer `settings.gradle`.
            if ((Files.isRegularFile(p.resolve("settings.gradle"))
                        || Files.isRegularFile(p.resolve("settings.gradle.kts")))
                    && Files.isDirectory(p.resolve("data/src/main/java"))) {
                return p
            }
            p = p.parent
        }
        throw IllegalStateException("no checkout root above " + start)
    }

    /**
     * A checkout-relative path, with a sibling-source fallback: this makes the shared reader
     * survive a `X.java`/`X.kt` move for every caller.
     */
    fun module(relative: String): Path {
        val direct = root().resolve(relative)
        if (Files.exists(direct)) {
            return direct
        }
        val sibling = siblingSource(relative)
        if (sibling != relative) {
            val siblingPath = root().resolve(sibling)
            if (Files.exists(siblingPath)) {
                return siblingPath
            }
        }
        throw IllegalStateException("missing: " + direct)
    }

    private fun siblingSource(relative: String): String {
        if (relative.endsWith(".java")) {
            return relative.substring(0, relative.length - ".java".length) + ".kt"
        }
        if (relative.endsWith(".kt")) {
            return relative.substring(0, relative.length - ".kt".length) + ".java"
        }
        return relative
    }

    /** Every `.java`/`.kt` file under a checkout-relative directory. */
    fun sourcesUnder(relative: String): List<Path> {
        val base = module(relative)
        val out = mutableListOf<Path>()
        Files.walk(base).use { walk ->
            walk.filter { Files.isRegularFile(it) }
                    .filter { p ->
                        val n = p.fileName.toString()
                        n.endsWith(".java") || n.endsWith(".kt")
                    }
                    .sorted()
                    .forEach { out.add(it) }
        }
        if (out.isEmpty()) {
            throw IllegalStateException("scanned nothing under " + base)
        }
        return out
    }

    fun read(relative: String): String {
        return read(module(relative))
    }

    fun read(p: Path): String {
        return String(Files.readAllBytes(p), StandardCharsets.UTF_8)
    }

    /** A checkout-relative name, for failure messages that name a file rather than a path. */
    fun name(p: Path): String {
        return root().relativize(p).toString()
    }
}
