package uk.xa0.tulkki.translation

import java.util.ArrayList
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.Message

/**
 * The gap the app translates when it comes back.
 *
 * <p>What a row is in the database is {@link TranslationStore}'s business and the mapping that makes it
 * a candidate is {@link TranslationHooksTest}'s; this pins the decisions on top: which rows the pass
 * may spend on, in what order, what a second pass does with the same rows, and what happens when the
 * read comes back at its ceiling.
 *
 * <p>The rule these tests used to state was a bound of ten. It is not one any more - the owner asked
 * for the whole gap, because a resume that translated ten messages left most of what arrived covered -
 * so what bounds the spending now is the daily cap, and what these tests pin is that nothing else
 * changed: the same rows qualify, each is bought once, and a read at the ceiling walks backwards
 * instead of dropping what it could not fit.
 */
class StartupBacklogTest {

    /**
     * The interpreter on - app Finnish with study German, two different languages. The pass's
     * rules require it, so every {@code everything} below names it.
     */
    private val ON = Interpreter.of("fi", "de")

    /** The off pair: app Finnish with study Finnish, the same language on both sides. */
    private val OFF = Interpreter.of("fi", "fi")

    private val APP_LANGUAGE = "fi"

    /** Ordinary German prose: language, into another language, nothing else attached. */
    private val GERMAN = "Guten Morgen! Wie geht es dir heute?"

    /** The gate's view of a plain received message, built the way {@code TranslationDecisionTest} builds one. */
    private fun candidate(body: String): TranslationDecision.Candidate {
        val candidate = TranslationDecision.Candidate()
        candidate.live(true, false, false)
        candidate.encryption = Message.ENCRYPTION_NONE
        candidate.body = body
        return candidate
    }

    private fun row(uuid: String, timeSent: Long, body: String): StartupBacklog.Row {
        return row(uuid, timeSent, body, Message.TRANSLATION_NONE)
    }

    private fun row(
            uuid: String, timeSent: Long, body: String, translationState: Int): StartupBacklog.Row {
        return StartupBacklog.Row(
                uuid, "conversation", timeSent, translationState, candidate(body))
    }

    private fun uuids(rows: List<StartupBacklog.Row>): List<String?> {
        val uuids = ArrayList<String?>()
        for (row in rows) {
            uuids.add(row.messageUuid)
        }
        return uuids
    }

    /** Twelve spendable rows, one per hour, oldest first. */
    private fun twelve(): MutableList<StartupBacklog.Row> {
        val rows = ArrayList<StartupBacklog.Row>()
        for (i in 1..12) {
            rows.add(row("m" + i, i.toLong(), GERMAN + " " + i))
        }
        return rows
    }
    @Test
    fun theReadCeilingIsUpstreamsOwnCatchupCeiling() {
        // Not a bound on what is bought - it is how many rows one read asks for, and it is 750 because
        // that is the most a single catch-up can have delivered (Config.MAM_MAX_MESSAGES). A gap
        // larger than this is walked back through by later passes, not dropped.
        Assert.assertEquals(750, StartupBacklog.READ_LIMIT)
    }

    @Test
    fun theWholeGapIsChosenInReadingOrder() {
        val selection =
                StartupBacklog.everything(twelve(), APP_LANGUAGE, ON)
        Assert.assertEquals(
                "everything the catch-up carried and nobody has translated is bought - in the order a"
                        + " person reads it, so a gap cut short by the cap is missing its end rather"
                        + " than the messages at the top of the conversation",
                listOf(
                        "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "m10", "m11", "m12"),
                uuids(selection))
    }

    @Test
    fun inputOrderDoesNotDecideTheChosenOrder() {
        val rows = twelve()
        rows.reverse()
        val selection =
                StartupBacklog.everything(rows, APP_LANGUAGE, ON)
        Assert.assertEquals(
                "ordered by the message's own time, not by the order the query handed the rows over",
                "m1",
                selection.get(0).messageUuid)
        Assert.assertEquals("m12", selection.get(11).messageUuid)
    }

    @Test
    fun unspendableRowsDoNotHideTheOnesBehindThem() {
        // A link, a ping and a message already in the app language cost nothing to decide against.
        // They are looked at but they are not bought and they do not hide the prose behind them.
        val rows = twelve()
        rows.add(row("link", 13, "https://example.org/somewhere"))
        rows.add(row("ping", 14, "Matti: "))
        rows.add(row("finnish", 15, "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle."))

        val selection =
                StartupBacklog.everything(rows, APP_LANGUAGE, ON)

        Assert.assertEquals(12, selection.size)
        Assert.assertEquals("m1", selection.get(0).messageUuid)
        Assert.assertEquals("m12", selection.get(11).messageUuid)
        Assert.assertFalse(uuids(selection).contains("link"))
        Assert.assertFalse(uuids(selection).contains("ping"))
        Assert.assertFalse(uuids(selection).contains("finnish"))
    }

    @Test
    fun aMessageThatNeedsNoLanguageIsNeverChosen() {
        val rows =
                listOf(
                        row("link", 1, "https://example.org/a/b"),
                        row("code", 1, "123456"),
                        row("emoji", 1, "\u2764\uFE0F\u2764\uFE0F"),
                        row("ping", 1, "Matti: "),
                        row("empty", 1, "   "))
        Assert.assertTrue(
                "nothing here is prose, so nothing here is bought",
                StartupBacklog.everything(rows, APP_LANGUAGE, ON).isEmpty())
    }

    @Test
    fun theConversationsOwnNameIsNeverChosen() {
        val named = candidate("Helsinki")
        named.conversationName = "Helsinki"
        val row =
                StartupBacklog.Row("name", "c", 1L, Message.TRANSLATION_NONE, named)
        Assert.assertTrue(
                "a body that is exactly the name the interface shows is a bare name, not prose",
                StartupBacklog.everything(listOf(row), APP_LANGUAGE, ON).isEmpty())
    }

    @Test
    fun aMessageAlreadyInTheAppLanguageIsNeverChosen() {
        val finnish =
                row("finnish", 1, "Moi! Mennäänkö huomenna kahville, minulla on asiaa sinulle.")
        Assert.assertTrue(
                "the queue would resolve this for free, so it is never bought",
                StartupBacklog.everything(listOf(finnish), APP_LANGUAGE, ON).isEmpty())
    }

    @Test
    fun nothingButAPlainReceivedBodyIsChosen() {
        val sent = candidate(GERMAN)
        sent.received = false
        val deleted = candidate(GERMAN)
        deleted.deleted = true
        val file = candidate(GERMAN)
        file.hasFileParams = true
        val ciphertext = candidate("-----BEGIN PGP MESSAGE-----")
        ciphertext.encryption = Message.ENCRYPTION_PGP

        val rows =
                listOf(
                        StartupBacklog.Row("sent", "c", 1L, Message.TRANSLATION_NONE, sent),
                        StartupBacklog.Row("deleted", "c", 1L, Message.TRANSLATION_NONE, deleted),
                        StartupBacklog.Row("file", "c", 1L, Message.TRANSLATION_NONE, file),
                        StartupBacklog.Row(
                                "ciphertext", "c", 1L, Message.TRANSLATION_NONE, ciphertext))

        Assert.assertTrue(
                "the pass steps around the archive clauses and nothing else: our own messages,"
                        + " deleted rows, files and ciphertext are still refused",
                StartupBacklog.everything(rows, APP_LANGUAGE, ON).isEmpty())
    }

    @Test
    fun aRowThatWasAlreadyAnsweredIsNeverChosenAgain() {
        for (state in intArrayOf(
                Message.TRANSLATION_DONE,
                Message.TRANSLATION_SAME_LANGUAGE,
                Message.TRANSLATION_FAILED)) {
            val answered = row("answered", 5, GERMAN, state)
            Assert.assertTrue(
                    "whatever the answer was, the answer stands: state " + state + " is never bought"
                            + " a second time",
                    StartupBacklog.everything(listOf(answered), APP_LANGUAGE, ON)
                            .isEmpty())
        }
    }

    @Test
    fun aSecondPassOverTheSameRowsBuysNothingBecauseTheyWereAnswered() {
        // What makes a second pass free is not a latch - a resume has to be able to run one - but the
        // stored state: the rows the first pass queued come back translated, and translated rows are
        // not candidates.
        val answered = ArrayList<StartupBacklog.Row>()
        for (row in twelve()) {
            answered.add(
                    StartupBacklog.Row(
                            row.messageUuid,
                            row.conversationUuid,
                            row.timeSent,
                            Message.TRANSLATION_DONE,
                            row.candidate))
        }
        val selection =
                StartupBacklog.everything(answered, APP_LANGUAGE, ON)
        Assert.assertTrue(selection.isEmpty())
    }
    /** The interpreter's off states, so the rule cannot be reading a different one from the others. */
    private fun offModes(): Array<Interpreter?> {
        return arrayOf(OFF, Interpreter.of("fi", Interpreter.NONE), null)
    }

    // ---- the interpreter off: the pass does not decide a gap it never looked at ----

    /**
     * The gate's own cell: a row the on-state pass spends on is not chosen off. The row is the
     * ordinary spendable shape - received, plain German prose, never translated - so the only thing
     * that can refuse it is the mode.
     */
    @Test
    fun anOffInterpreterChoosesNothing() {
        Assert.assertEquals(
                "the on-state control: this row is spent on",
                listOf("m1"),
                uuids(
                        StartupBacklog.everything(
                                listOf(row("m1", 1, GERMAN)),
                                APP_LANGUAGE,
                                ON)))

        for (off in offModes()) {
            Assert.assertTrue(
                    "off, nothing is chosen even though everything about these rows is spendable",
                    StartupBacklog.everything(twelve(), APP_LANGUAGE, off).isEmpty())
        }
    }

    /**
     * {@code eligible} answers {@code false} off too, and it does so without a guard of its own: the
     * two rules it asks carry the mode themselves - {@code TranslationDecision.isRequestable}'s first
     * statement is the mode, and {@code classify} never returns {@code TRANSLATE} while off (S4-3) -
     * so a second check here would be the same answer for a value already answered one call down.
     * This cell pins the answer; the falsification measures which guard actually holds it.
     */
    @Test
    fun anOffInterpreterClosesThePassesOwnGate() {
        val spendable = row("m1", 1, GERMAN)
        Assert.assertTrue(StartupBacklog.eligible(spendable, APP_LANGUAGE, ON))

        for (off in offModes()) {
            Assert.assertFalse(StartupBacklog.eligible(spendable, APP_LANGUAGE, off))
        }
    }
}
