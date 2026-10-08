package uk.xa0.tulkki.data.messages

import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.Flow
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.model.IndividualMessage

/**
 * S5-6's read models: the two snapshots, the body carrier, the mapping, and the one rule the design
 * pins about all of them.
 *
 * <p>`docs/MIGRATION.md`, "Design: the data layer" §2.7 states it: the read models are "Immutable,
 * `public` read-model types ... They carry ids, scalars and the two body Strings, because the
 * concealment decision is `MessageProjection.of(snapshot, context, settings, perProcess)`'s and is
 * made in `:ui` - the projector needs the original to be able to smear it. What must not happen is a
 * body appearing in a `Ui*` type ... or in a `snapshot.toString()`; both are pinned by tests, and the
 * snapshot's `toString()` prints ids and shapes only, exactly as `UiMessage` does".
 *
 * <p>The bodies below are sentinel sentences, so "the text did not print" is a measurement rather
 * than a reading: each one is asserted absent from the printed form and present in the carrier.
 * `Compose UI` §2.3 invariant 1(b) is the matching cell over `UiMessage`.
 */
class SnapshotTest {

    private val original = "the concealed original: a sentence no log line may carry"
    private val translated = "the drawn translation: a sentence no log line may carry"

    /** One `messages` row with the two sentinels in place and every other column at a known value. */
    private fun message() =
        MessageEntity(
            uuid = "m-1",
            conversationUuid = "c-1",
            timeSent = 1_700_000_000_000L,
            counterpart = "peer@example.org",
            trueCounterpart = null,
            body = original,
            encryption = 0L,
            status = 0L,
            type = 0L,
            relativeFilePath = null,
            serverMsgId = "s-1",
            axolotlFingerprint = null,
            carbon = 0L,
            edited = null,
            read = 1L,
            oob = 0L,
            errorMsg = null,
            readByMarkers = "[]",
            markable = 0L,
            fileDeleted = 0L,
            deleted = 0L,
            bodyLanguage = "fi",
            retractId = null,
            occupantId = "occ-camel",
            occupantIdSnake = "occ-1",
            reactions = "[]",
            remoteMsgId = null,
            ephemeralTimer = 0L,
            expireAt = 0L,
            translatedBody = translated,
            translationLang = "en",
            translationState = 1L,
            subject = null,
            oobUri = null,
            fileParams = null,
            payloads = null,
            timeReceived = 1_700_000_001_000L,
            notificationDismissed = 0L,
            delivery = 2L,
        )

    /** One conversation row with a name and attributes that must not print either. */
    private fun conversation() =
        ConversationEntity(
            uuid = "c-1",
            name = "A Name No Log Line Needs",
            contactUuid = "ct-1",
            accountUuid = "a-1",
            contactJid = "peer@example.org",
            created = 1_600_000_000_000L,
            status = 0L,
            mode = 0L,
            attributes = """{"muted":false}""",
            detectedLanguage = "fi",
            languageOverride = null,
            doubtHold = null,
        )

    /**
     * The pinned `toString()`: the id, the conversation id, the type and the bodies' **shapes**, and
     * neither sentence. This is the gate's `snapshot.toString()` cell.
     */
    @Test
    fun theMessageSnapshotsToStringIsItsIdsAndShapesAndNeverABody() {
        val snapshot = message().asSnapshot()
        val printed = snapshot.toString()
        Assert.assertEquals(
            "the pinned shape: ids, the type, and whether each half is present",
            "MessageSnapshot(id=m-1, conversationId=c-1, type=0, " +
                "bodies=(ConversationBodies(translated=true, original=true)))",
            printed,
        )
        Assert.assertFalse("the original must not print", printed.contains(original))
        Assert.assertFalse("the translation must not print", printed.contains(translated))
    }

    /** The body carrier prints presence and nothing else, in the same way. */
    @Test
    fun theBodyCarrierPrintsPresenceAndNeverABody() {
        val bodies = ConversationBodies(translated = translated, original = original)
        Assert.assertEquals(
            "presence, twice",
            "ConversationBodies(translated=true, original=true)",
            bodies.toString(),
        )
        Assert.assertFalse(bodies.toString().contains(original))
        Assert.assertFalse(bodies.toString().contains(translated))
        Assert.assertTrue("the carrier still holds both halves for the projector", bodies.present)
        Assert.assertEquals(
            "an empty carrier is a shape and not a body",
            "ConversationBodies(translated=false, original=false)",
            ConversationBodies(translated = null, original = null).toString(),
        )
    }

    /** The conversation snapshot prints ids only: not the name, not the attributes JSON. */
    @Test
    fun theConversationSnapshotsToStringIsItsIdsAndNeverItsNameOrAttributes() {
        val snapshot =
            ConversationRow(
                    row = conversation(),
                    lastMessageId = "m-1",
                    lastMessageAt = 1_700_000_000_000L,
                )
                .asSnapshot()
        val printed = snapshot.toString()
        Assert.assertEquals(
            "the pinned shape",
            "ConversationSnapshot(id=c-1, accountId=a-1, mode=0, lastMessageId=m-1)",
            printed,
        )
        Assert.assertFalse("the name must not print", printed.contains("A Name No Log Line Needs"))
        Assert.assertFalse("the attributes must not print", printed.contains("muted"))
    }

    /**
     * The doubt-hold travels with the row image **as the row holds it**, and its third answer stays
     * distinct: `null` is "this conversation never chose", whose meaning is the shipped default -
     * *on* - and it must not arrive as `0`, which is a conversation that turned the hold off. Nothing
     * here resolves the default; that belongs to the rule that consults the value.
     */
    @Test
    fun theConversationSnapshotCarriesTheDoubtHoldAndResolvesNothing() {
        val neverChose =
            conversation().copy(doubtHold = null).asSnapshot(lastMessageId = null, lastMessageAt = null)
        val on =
            conversation().copy(doubtHold = 1).asSnapshot(lastMessageId = null, lastMessageAt = null)
        val off =
            conversation().copy(doubtHold = 0).asSnapshot(lastMessageId = null, lastMessageAt = null)
        Assert.assertNull("\"never chose\" is not \"off\"", neverChose.doubtHold)
        Assert.assertNotEquals("so the two must not read alike", off.doubtHold, neverChose.doubtHold)
        Assert.assertNotEquals("and neither may disappear", on.doubtHold, neverChose.doubtHold)
        Assert.assertEquals("on is 1", 1, on.doubtHold)
        Assert.assertEquals("off is 0", 0, off.doubtHold)
    }

    /** The mapping carries both halves and folds the two occupant spellings the way `Message` does. */
    @Test
    fun theMappingCarriesBothBodiesAndFoldsTheLegacyOccupantColumn() {
        val snapshot = message().asSnapshot()
        Assert.assertEquals("the translation is the app-language half", translated, snapshot.bodies.translated)
        Assert.assertEquals("the original is the other half", original, snapshot.bodies.original)
        Assert.assertEquals(
            "the legacy `occupant_id` wins when it is set, as Message.java:447-449 reads it",
            "occ-1",
            snapshot.occupantId,
        )
        Assert.assertEquals(1L, snapshot.translationState)
        Assert.assertEquals(
            "the list's joined row carries its pointer through the mapping",
            "m-1",
            ConversationRow(conversation(), "m-1", 1_700_000_000_000L).asSnapshot().lastMessageId,
        )
        Assert.assertNull(
            "a read with no pointer answers null rather than inventing one",
            conversation().asSnapshot(lastMessageId = null, lastMessageAt = null).lastMessageId,
        )
    }

    /**
     * The boundary itself: both read models are `public`, and the repositories hand them out as
     * `Flow`s. That is §2.7's "`Flow`s, never `LiveData`, never a `Cursor`" as a shape a compile
     * cannot silently change.
     */
    @Test
    fun theReadModelsArePublicAndReachTheUiAsFlows() {
        Assert.assertTrue(
            "the read model must cross the module boundary",
            Modifier.isPublic(MessageSnapshot::class.java.modifiers),
        )
        Assert.assertTrue(
            "and so must the conversation's",
            Modifier.isPublic(ConversationSnapshot::class.java.modifiers),
        )
        Assert.assertEquals(
            "the message repository's watch is a Flow",
            Flow::class.java,
            MessageSnapshots::class.java.getMethod("watch", String::class.java).returnType,
        )
        Assert.assertEquals(
            "and so is the conversation's",
            Flow::class.java,
            ConversationSnapshots::class.java.getMethod("watch", String::class.java).returnType,
        )
        Assert.assertEquals(
            "the conversation-list read model is a Flow too",
            Flow::class.java,
            ConversationSnapshots::class.java.getMethod("watchAccount", String::class.java).returnType,
        )
        Assert.assertFalse(
            "`LiveData` is not a read model: no public method here may return one",
            listOf(
                MessageSnapshots::class.java.methods.toList(),
                ConversationSnapshots::class.java.methods.toList(),
            )
                .flatten()
                .any { it.returnType.name.contains("LiveData") },
        )
    }

    /**
     * The search consumer's port (S5-6): the model reads the same row from a read model that its
     * cursor reader read. `IndividualMessage.fromCursor` had exactly one caller - `MessageSearchTask`,
     * through `DatabaseBackend.getMessageSearchCursor` - and this is the reader that replaces it. The
     * two sentinel sentences prove which half it takes: the stored `body`, never the translation.
     *
     * <p><strong>Why [Message.rawBody] and not [Message.body].</strong> `getBody()`'s fallback path
     * returns `android.util.Pair` from `bodyMinusFallbacks`, and `android.util.Pair` is a **stubbed
     * platform class** on the host: its fields read back null, so `getBody()` throws
     * `NullPointerException` in any JVM test whatever row it is handed - measured here, on a row
     * built by the same constructor the cursor reader uses. Nothing about the port is implicated and
     * nothing is weakened: the direct accessor is asserted, and the substance is the same sentence.
     */
    @Test
    fun theSearchMapperReadsTheModelRowFromTheReadModel() {
        val mapped = IndividualMessage.fromSnapshot(message().asSnapshot(), null)
        Assert.assertNotNull("the row maps", mapped)
        Assert.assertEquals(
            "the stored body is what a result is drawn from",
            original,
            mapped!!.getRawBody(),
        )
        Assert.assertNotEquals("and the translation is not it", translated, mapped.getRawBody())
        Assert.assertEquals("the row's conversation id", "c-1", mapped.getConversationUuid())
        Assert.assertEquals("and its instant", 1_700_000_000_000L, mapped.getTimeSent())
        Assert.assertEquals("and its wire id", "s-1", mapped.getServerMsgId())
        Assert.assertNull(
            "the conversation is whatever the caller resolved; a null one is passed straight through",
            mapped.getConversation(),
        )
    }
}
