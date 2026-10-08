package uk.xa0.tulkki.ui.topup

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream
import org.junit.Assert
import org.junit.Test

/**
 * `ui-6`'s cells over the top-up state and the words its screen draws - the JVM half docs/MIGRATION.md
 * "Design: the Compose UI" §7.4 asks for, beside the pure [uk.xa0.tulkki.ui.TopUpPage] whose own tests
 * this row leaves unchanged.
 *
 * <p>What they hold: the two transitions that are rules rather than assignments. A progress tick only
 * moves a bar that is on screen, and a finished page only hides a bar - a refusal arrives as an HTTP
 * error and then as a finished page, and the second callback must not clear the first. The other two
 * cells pin the starting state's clear and the screen's single string table, which is why a hardcoded
 * sentence cannot appear in place of a resource.
 */
class TopUpStateTest {

    @Test
    fun theBarMovesOnlyWhileItIsOnScreen() {
        Assert.assertEquals(
            "a tick moves the bar it belongs to",
            TopUpState.Page.Loading(40),
            TopUpState.loading(0).progressed(40).page,
        )
        Assert.assertEquals(
            "and a tick while the panel is standing must not take it away",
            TopUpState.Page.Failed(UNREACHABLE),
            TopUpState.loading(0).failed(UNREACHABLE).progressed(90).page,
        )
    }

    @Test
    fun finishingHidesTheBarAndLeavesAStandingFailure() {
        Assert.assertEquals(
            "the bar goes when the page is there",
            TopUpState.Page.Ready,
            TopUpState.loading(0).progressed(70).finished().page,
        )
        Assert.assertEquals(
            "the tree's own rule: a refusal arrives as an error and then as a finished page",
            TopUpState.Page.Failed(HTTP),
            TopUpState.loading(0).failed(HTTP).finished().page,
        )
    }

    @Test
    fun aNoticeDoesNotDisturbThePageItIsAbout() {
        val afterNotice = TopUpState.loading(40).unhandled("wechatpay://pay?id=1")
        Assert.assertEquals("the notice is the address the page asked for", "wechatpay://pay?id=1", afterNotice.notice)
        Assert.assertEquals("and the bar it happened over is still the bar", TopUpState.Page.Loading(40), afterNotice.page)

        val standingFailure = TopUpState.loading(0).failed(UNREACHABLE).unhandled("alipay://pay")
        Assert.assertEquals("the failure the notice came from is still standing", TopUpState.Page.Failed(UNREACHABLE), standingFailure.page)
        Assert.assertEquals("alipay://pay", standingFailure.notice)
    }

    /**
     * A start is a state of its own and not a transition, so what it promises is that nothing of the old
     * page is in it: a retry after a refusal begins at the bar's own zero with nothing said.
     */
    @Test
    fun aStartCarriesNothingOfThePageItReplaces() {
        Assert.assertEquals(TopUpState.Page.Loading(0), TopUpState.loading(0).page)
        Assert.assertNull(TopUpState.loading(0).notice)
        Assert.assertNotEquals(
            "the state a start produces is not the state that was carrying a notice",
            TopUpState.loading(0),
            TopUpState.loading(40).unhandled("wechatpay://pay?id=1"),
        )
    }

    /**
     * The screen draws every word from the string table and every value from the state - the reading is
     * textual because this module hosts no Activity, no Fragment and no Robolectric, so a Composable
     * cannot be run here. A sentence written into the file instead of named from the table reddens it.
     */
    @Test
    fun theScreenNamesEveryStringItDrawsAndTakesEveryValueFromTheState() {
        val screen = read("src/main/java/uk/xa0/tulkki/ui/topup/TopUpScreen.kt")
        for (name in NAMED) {
            Assert.assertTrue("the screen draws nothing through R.string.$name", screen.contains("R.string.$name"))
        }
        for (reading in READ) {
            Assert.assertTrue("the screen does not read $reading from the state it is handed", screen.contains(reading))
        }
    }

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
        val UNREACHABLE =
            TopUpState.Reason.Unreachable("https://platform.deepseek.com/top_up", "net::ERR_CONNECTION_RESET")

        val HTTP = TopUpState.Reason.Http(403, "https://platform.deepseek.com/top_up")

        /** Every string the screen draws, by the name `strings_tulkki.xml` declares it under. */
        val NAMED =
            listOf(
                "tulkki_top_up_error_title",
                "tulkki_top_up_error_failed",
                "tulkki_top_up_error_http",
                "tulkki_top_up_error_hint",
                "tulkki_top_up_retry",
                "tulkki_top_up_unhandled",
                "tulkki_interpreter_off_title",
                "tulkki_interpreter_off_line",
                "tulkki_usage_open_settings",
            )

        /** The readings that must come from the state, never from a constant in the file. */
        val READ = listOf("state.notice", "TopUpState.Page.Loading", "TopUpState.Page.Failed", "page()")
    }
}
