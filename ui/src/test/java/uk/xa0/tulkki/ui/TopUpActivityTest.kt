package uk.xa0.tulkki.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream
import org.junit.Assert
import org.junit.Test

/**
 * §3.3's "**Must never show**" list, pinned where the WebView actually is: `TopUpActivity`, read as
 * source text.
 *
 * <p>The design's list is five rules - anything pre-filled, any `addJavascriptInterface`, any injected
 * script, any address that is not http(s) put into the page, any touch of the cookie jar - and this is
 * the file that could break every one of them. It is read rather than executed because the WebView's
 * rendering, its cookie jar and its dark mode are only observable on a phone (§7.3); what a JVM test
 * can hold is the absence of the calls that would break the rules and the presence of the settings that
 * keep them, which is the same instrument `TranslationFailuresScreenTest` uses for its screen.
 *
 * <p>The two halves are one cell each on purpose: an absence is what the rules are, and a presence is
 * what the payment page needs to work at all - a screen that dropped `setDomStorageEnabled` would be
 * safe and useless.
 */
class TopUpActivityTest {

    @Test
    fun thePageNeverGetsABridgeAScriptOrALocalFile() {
        val code = code(read(ACTIVITY))
        for (forbidden in FORBIDDEN) {
            Assert.assertFalse(
                "the platform's page must never be handed $forbidden",
                code.contains(forbidden),
            )
        }
    }

    /**
     * The settings that make the page work, and the two `TopUpPage` decisions the screen must keep
     * asking rather than re-deciding: which address stays in the WebView, and which user agent is sent.
     * Every one of these is a line a later edit could drop without the compiler noticing.
     */
    @Test
    fun thePageKeepsTheSettingsThatMakeItWork() {
        val code = code(read(ACTIVITY))
        for (required in REQUIRED) {
            Assert.assertTrue("the screen no longer names $required", code.contains(required))
        }
    }

    /**
     * §3.3's off state: "removed with the ledger - no interpretation, no spend, no top-up row". The row
     * is the settings page's, already removed by its own predicate; what this cell holds is the
     * defensive half - the switch is read before the page is built, so an interpreter that is off opens
     * no payment page at all.
     */
    @Test
    fun thePaymentPageIsBuiltOnlyAfterTheSwitchIsRead() {
        val code = code(read(ACTIVITY))
        val decision = code.indexOf("interpreter().enabled()")
        val built = code.indexOf("WebView(this)")
        Assert.assertTrue("the screen reads the interpreter's switch", decision >= 0)
        Assert.assertTrue("and it builds the page somewhere", built >= 0)
        Assert.assertTrue(
            "the page must be built after the switch is read: off means the WebView never exists",
            built > decision,
        )
    }

    /**
     * The shell the screen replaced: the progress bar, the WebView, the error panel and the notice
     * line were four views in `activity_topup.xml`, found by id and driven by hand, and what was left
     * of the file was a toolbar and the `ComposeView` the screen sat in. The layout is gone now - the
     * bar is the shared chrome and the screen is composed straight into it - so the shell has no file
     * to inflate and names none of the ids the screen took over: an id nothing inflates is exactly
     * the stale copy this cell refuses.
     */
    @Test
    fun theShellComposesTheScreenAndNamesNoneOfTheViewsItReplaced() {
        val shell = code(read(ACTIVITY))
        Assert.assertTrue(
            "the screen must be composed into the chrome",
            shell.contains("setTulkkiContent"),
        )
        Assert.assertFalse("the shell has no layout left to inflate", shell.contains("R.layout."))
        for (gone in GONE) {
            Assert.assertFalse("$gone is still named in the shell, with no reader", shell.contains(gone))
        }
    }

    /**
     * The file's code with its comments taken out. Both halves below are about what the code does, and
     * the class doc says in as many words that there is no bridge in it - a rule read off the prose
     * would fail on the sentence that states the rule.
     */
    private fun code(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\n]*"), "")

    private fun read(relative: String): String = String(Files.readAllBytes(locate(relative)), StandardCharsets.UTF_8)

    /**
     * The file `relative` names, found in either layout: the Gradle working directory is the module,
     * and the repository root is found by its `settings.gradle.kts` marker rather than by walking up to
     * the first `src`, which would stop at the module.
     */
    private fun locate(relative: String): Path {
        val root = root()
        val direct = root.resolve(relative)
        if (Files.exists(direct)) {
            return direct
        }
        val directories =
            Files.list(root).use { stream: Stream<Path> -> stream.filter(Files::isDirectory).sorted().toList() }
        for (directory in directories) {
            val name = directory.fileName.toString()
            if (name.startsWith(".") || name == "build" || name == "src") {
                continue
            }
            val candidate = directory.resolve(relative)
            if (Files.exists(candidate)) {
                return candidate
            }
        }
        throw AssertionError("could not locate $relative under $root")
    }

    private fun root(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            if (directory != null &&
                    (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                            || Files.isRegularFile(directory.resolve("settings.gradle")))) {
                return directory
            }
            directory = directory?.parent
        }
        throw AssertionError("could not find settings.gradle.kts above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ACTIVITY = "src/main/java/uk/xa0/tulkki/ui/TopUpActivity.kt"

        /** The ids the screen took over, none of which may survive in the shell. */
        val GONE =
            listOf(
                "topup_webview",
                "topup_progress",
                "topup_error",
                "topup_notice",
                "topup_retry",
            )

        /**
         * The calls §3.3's list forbids, each named as it would have to be written: a bridge, a script
         * run or injected, a local file or content made reachable to a third-party page, the mixed-content
         * hole, and anything that clears or writes a cookie value.
         */
        val FORBIDDEN =
            listOf(
                "addJavascriptInterface",
                "evaluateJavascript",
                "loadData",
                "javascript:",
                "allowFileAccess = true",
                "allowContentAccess = true",
                "allowFileAccessFromFileURLs = true",
                "allowUniversalAccessFromFileURLs = true",
                "MIXED_CONTENT_ALWAYS_ALLOW",
                "removeAllCookies",
                "removeSessionCookies",
                ".setCookie(",
            )

        /** The settings and the two decisions the screen must keep, taken from the tree's own calls. */
        val REQUIRED =
            listOf(
                "settings.domStorageEnabled = true",
                "settings.allowFileAccess = false",
                "settings.allowContentAccess = false",
                "settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW",
                "TopUpPage.browserUserAgent(settings.userAgentString)",
                "TopUpPage.destinationOf(url) == TopUpPage.Destination.WEBVIEW",
                "cookies.setAcceptThirdPartyCookies(view, true)",
            )
    }
}
