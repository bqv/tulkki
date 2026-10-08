package uk.xa0.tulkki.ui.failures

import java.lang.reflect.ParameterizedType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.TranslationFailures
import uk.xa0.tulkki.translation.TranslationQueue
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TranslationText

/**
 * `ui-5`'s cells over the failures state and the table its rows are rendered through - the JVM half
 * docs/MIGRATION.md "Design: the Compose UI" §7.4 asks for: "**Screen state is a pure reducer**, not a
 * Composable", with plain Kotlin values a test can read without a device.
 *
 * <p>What they hold, in order: that a row is §3.2's reuse of [TranslationFailures.Failure] rather than a
 * wrapper, that the list is the three-valued reading the loading state needs, and that the row's three
 * fields and the blocked line all come from [TranslationText]'s one table - which is the "one table, so
 * there is one answer" rule the cover and the usage screen already obey.
 */
class FailuresStateTest {

    @Test
    fun theFilterNarrowsTheRowsToItsOwnConversation() {
        val mine = failure("c1")
        val other = failure("c2")
        val state = FailuresState(null, 0, listOf(mine, other), conversation = "c1")
        Assert.assertEquals(listOf(mine), state.visible)
    }

    @Test
    fun noFilterDrawsEveryRow() {
        val state = FailuresState(null, 0, listOf(failure("c1"), failure(null)))
        Assert.assertEquals(2, state.visible.size)
    }

    @Test
    fun aRowWhoseConversationIsGoneIsNobodyElsesRow() {
        // A conversation that is no longer here is recognisable in the unfiltered list and is nobody
        // else's row, so a filtered screen must not draw it under another conversation's name.
        val state = FailuresState(null, 0, listOf(failure(null)), conversation = "c1")
        Assert.assertTrue(state.visible.isEmpty())
    }

    @Test
    fun aReadThatHasNotLandedHasNoVisibleRowsEither() {
        Assert.assertTrue(FailuresState(null, 0, null, conversation = "c1").visible.isEmpty())
    }

    /** One failure row, through `:translation`'s own factory: the constructor is internal to it. */
    private fun failure(conversationUuid: String?): TranslationFailures.Failure =
        TranslationFailures.received(
            messageUuid = "m-$conversationUuid",
            conversationUuid = conversationUuid,
            conversationJid = null,
            body = "x",
            state = TranslationQueue.Item.STATE_FAILED,
            attempts = 1,
            createdAt = 1L,
            failedAt = 2L,
            lastError = null,
            recorded = null,
        )

    @Test
    fun aRowIsTheFailuresOwnValueAndNotAWrapper() {
        val fields =
            FailuresState::class.java.declaredFields
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.name.startsWith("$") }
                .associate { it.name to it.type }
        Assert.assertEquals("the blocker is the send path's own reason", HeldSend.HoldReason::class.java, fields.getValue("blocker"))
        Assert.assertEquals(java.lang.Integer.TYPE, fields.getValue("waiting"))
        Assert.assertEquals(List::class.java, fields.getValue("rows"))
        Assert.assertEquals(
            "plus `conversation`, which item 17's decision four added: the failures screen opened from",
            String::class.java,
            fields.getValue("conversation"),
        )

        val rows = FailuresState::class.java.getDeclaredField("rows")
        val element = (rows.genericType as ParameterizedType).actualTypeArguments[0]
        Assert.assertEquals(
            "the row IS the failure - a second spelling of it would be a second answer to what failed",
            TranslationFailures.Failure::class.java,
            element,
        )
    }

    /**
     * The three values, and the one that is not an empty list. §3.2 draws the live lines at once and the
     * rows when the read lands; an empty list before then would say "No translation has failed" while the
     * read is still in flight, which is the one answer this screen must not give.
     */
    @Test
    fun theRowsAreThreeValuedSoAnUnreadListIsNotAnEmptyOne() {
        val unread = FailuresState(blocker = null, waiting = 0, rows = null)
        val nothingFailed = FailuresState(blocker = null, waiting = 0, rows = emptyList())
        val one = FailuresState(blocker = HeldSend.HoldReason.CAP_REACHED, waiting = 2, rows = listOf(failure()))

        Assert.assertNull("the read is still in flight", unread.rows)
        Assert.assertEquals("the read landed and nothing has failed", 0, nothingFailed.rows!!.size)
        Assert.assertEquals("the read landed and this is what it found", 1, one.rows!!.size)
        Assert.assertNotEquals("an unread list is not an empty one", unread, nothingFailed)
    }

    /**
     * Every word a row gives is [TranslationText]'s - the same table the covered bubble and the usage
     * screen's last-failure line use, so "no DeepSeek key is set" cannot read two ways. The null reason
     * is the failures screen's own question: this row exists because this message failed, so an
     * unreached reason says so rather than borrowing the newest failure's words.
     */
    @Test
    fun theRowVocabularyIsTheOneTranslationTextTable() {
        Assert.assertEquals(R.string.tulkki_failures_when_failed, TranslationText.failuresWhen(TranslationFailures.When.FAILED))
        Assert.assertEquals(R.string.tulkki_failures_when_arrived, TranslationText.failuresWhen(TranslationFailures.When.ARRIVED))
        Assert.assertEquals(R.string.tulkki_failures_when_send_failed, TranslationText.failuresWhen(TranslationFailures.When.SEND_FAILED))
        Assert.assertEquals(R.string.tulkki_failures_when_written, TranslationText.failuresWhen(TranslationFailures.When.WRITTEN))

        Assert.assertEquals(R.string.tulkki_failures_will_retry, TranslationText.failuresNext(TranslationFailures.Next.RETRY))
        Assert.assertEquals(R.string.tulkki_failures_gave_up, TranslationText.failuresNext(TranslationFailures.Next.GAVE_UP))
        Assert.assertEquals("a held send is neither retried by the app nor given up on", R.string.tulkki_failures_held, TranslationText.failuresNext(TranslationFailures.Next.HELD))

        Assert.assertEquals(R.string.tulkki_failures_reason_not_kept, TranslationText.failureOrNotKept(null))
        Assert.assertEquals(
            "a reason the app does have is the cover's own phrase",
            R.string.tulkki_failure_cap_reached,
            TranslationText.failureOrNotKept(HeldSend.HoldReason.CAP_REACHED),
        )

        for (reason in HeldSend.HoldReason.values()) {
            Assert.assertEquals(
                "the blocked line and the covered bubble must agree about $reason",
                TranslationText.failureReason(reason),
                TranslationText.failureOrNotKept(reason),
            )
        }
    }

    /**
     * The screen asks that one table for every word it draws, rather than naming a string of its own -
     * hardcoding one would let the row and the bubble disagree about the same failure without the
     * compiler noticing. The reading is textual because this module hosts no Activity, no Fragment and
     * no Robolectric, so a Composable cannot be run here; it is the same instrument
     * `TranslationFailuresScreenTest` uses for the screen's text.
     */
    @Test
    fun theScreenAsksTheOneTableForEveryWordItDraws() {
        val screen = read("src/main/java/uk/xa0/tulkki/ui/failures/FailuresScreen.kt")
        for (call in
            listOf(
                "TranslationText.failuresWhen(",
                "TranslationText.failuresNext(",
                "TranslationText.failureOrNotKept(",
                "TranslationText.failureReason(",
            )) {
            Assert.assertTrue("the screen does not draw its row through $call", screen.contains(call))
        }
    }

    /** A received failure with the queue's own state, so the state's rows are a real value too. */
    private fun failure(): TranslationFailures.Failure =
        TranslationFailures.send(
            "2a5e6b31-0000-4000-8000-000000000002",
            "b7c4d0f2-0000-4000-8000-0000000000c2",
            "sari@example.net",
            "Kiitos!",
            1_789_001_000_000L,
            null,
        )

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
            Files.list(root).use { stream -> stream.filter(Files::isDirectory).sorted().toList() }
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
}
