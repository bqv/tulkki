package uk.xa0.tulkki.translation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tulkki: the notes on the owner's own words are kept with a held answer, and re-emitted when that
 * answer is finally sent - the coordinator's ruling on item 16's second unmet part.
 *
 * <p>The marks ride the same paid call the translation came from, so a message that is held on doubt
 * and later sent as held would arrive with the owner having paid for notes that never left the
 * device: a silent loss in exactly the place the opposite-taps fix was about. The notes therefore
 * live **with the hold record** - the kind in the settings store under `hold-doubt:<uuid>`, the answer
 * in the message's own persisted `translated_body`, and the notes under `hold-notes:<uuid>` beside the
 * kind - and they are re-emitted through the ordinary store when the held answer goes out, so the
 * underline surface finds them after a kill too. One answer, one message, dropped with the hold: not
 * an outbound cache, not a setting, and not the received side's `failure_cause` vocabulary.
 *
 * <p>The first cell is the kill test, read back through a **new** settings object over the same store,
 * which is what the next process does. The second re-emits the kept notes into a `MemoryCacheStore`
 * and asks the read path's own key - `ReviewKey.of(ownerText, reviewLanguage)` - so the assertion is
 * about where the bubble looks, not about a private field. The third is the source pin: the hold must
 * keep them, the send-as-held road must re-emit and then drop them, so a caller that disappears
 * reddens here.
 */
class HeldReviewNotesTest {

    private val UUID = "message-1"

    private val DRAFT = "Wie geht es dir?"

    private val ON = Interpreter.of("fi", "de")

    private val NOTES = "{\"notes\":[{\"note\":\"a remark\",\"flagged\":[\"geht\"]}]}"

    @Test
    fun aHeldAnswersNotesSurviveTheProcessBeingKilled() {
        val store = TranslationSettings.MapPrefs()
        TranslationSettings(store, store).setHeldNotes(UUID, NOTES)

        assertEquals(
                "the next process reads the notes the hold kept",
                NOTES,
                TranslationSettings(store, store).heldNotes(UUID))
    }

    @Test
    fun reEmittingTheKeptNotesPutsThemWhereTheBubbleReadsThem() {
        val store = TranslationDoubles.MemoryCacheStore()
        val cache = TranslationCache(store)

        ReviewStore.reemit(cache, DRAFT, "fi", NOTES, ON)

        val entry = cache.get(ReviewKey.of(DRAFT, "fi"))
        assertNotNull("the notes are in the cache the bubble reads", entry)
        assertEquals(
                "and they parse back as the one note that was held",
                1,
                ReviewParser.parse(entry!!.translatedBody)!!.items().size)
    }

    @Test
    fun theHoldKeepsTheNotesAndTheSendAsHeldRoadReEmitsThem() {
        val source =
                String(
                                Files.readAllBytes(
                                        projectRoot()
                                                .resolve(
                                                        "translation/src/main/java/uk/xa0/tulkki/"
                                                                + "translation/OutgoingTranslation.kt")),
                                StandardCharsets.UTF_8)
                        .replace(Regex("\\s+"), " ")

        assertTrue(
                "the hold must keep the notes with the answer and the kind",
                source.contains("settings.setHeldNotes(message.getUuid(), ReviewStore.rawOf(review))"))
        assertTrue(
                "sending the held answer must re-emit them",
                source.contains("ReviewStore.reemit("))
        assertTrue(
                "and the hold record must drop them",
                source.contains("settings.setHeldNotes(message.getUuid(), null)"))
    }

    /** The checkout root, found the way the module's other source scans find it: the nearest marker. */
    private fun projectRoot(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            if (directory != null &&
                    (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                            || Files.isRegularFile(directory.resolve("settings.gradle")))) {
                return directory
            }
            directory = directory?.getParent()
        }
        throw AssertionError(
                "could not find settings.gradle.kts above " + System.getProperty("user.dir"))
    }
}
