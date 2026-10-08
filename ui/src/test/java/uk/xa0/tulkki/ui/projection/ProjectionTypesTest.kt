package uk.xa0.tulkki.ui.projection

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.DisplayedBody

/**
 * The cells docs/MIGRATION.md "Design: the Compose UI" §2.3 attaches to the read-model types, over the
 * types §2.2 and §2.2.1 ("The missing definitions") determine.
 *
 * <p>§2.3 invariant 1: "the body is a closed type 'so a raw `String` is not merely forbidden but
 * *unavailable* to a composable, a `toString()`, a saved-state bundle or a log line. Cost: type
 * discipline plus a test that `UiMessage` exposes no `String` body. That test is the entire leak
 * defence.'" The design lists the types: "(a) reflection over `UiBody`/`UiConcealment`/`UiPreview`/
 * `UiEnglishRow`/`UiQuote` asserts the only `String`-typed property anywhere in them is
 * `Visible.text`", and "(b) `UiMessage`'s `toString()` on a fixture whose original is a sentence
 * neither contains the sentence nor the translation". Both are below.
 *
 * <p>The scan reads **sources as text** rather than `KClass.memberProperties`, for a measured reason:
 * the ids are `value class`es, so `UiQuote.messageId: MessageId` is erased to a `String` field in the
 * JVM shape and a type-based reflection would read the design's own typed field as a leak. The
 * design's contract is written in the source text, and `:ui`'s Compose rules (§1.8 rule 1) are
 * already enforced by reading sources as text. The cells where the JVM shape *is* the contract - the
 * smear's bitmap, the placeholder's cover enum, the two rows' field lists - use reflection.
 *
 * <p>What no cell here can cover is the projector itself (the parity test §2.3 invariant 2 asks for):
 * `MessageProjection` does not exist until a screen row builds it, and the members §2.2.1 marks
 * deferred (`TransferFailure`, `FileKind`, a reactor's identity, `Failed`'s payloads) are holes the
 * type list records as absent rather than pins as final.
 */
class ProjectionTypesTest {

    @Test
    fun theOnlyStringAComposableCanReachIsTheVisibleText() {
        val declared = mutableListOf<String>()
        val strings = mutableListOf<String>()
        for (file in DESIGNED_BODY_FILES) {
            for (property in PROPERTY.findAll(code(read(PROJECTION + "/" + file)))) {
                val name = property.groupValues[1]
                declared += "$file:$name"
                if (property.groupValues[2].trim() == "String") {
                    strings += "$file:$name"
                }
            }
        }
        // Non-vacuous: the five files declare well over a dozen typed properties between them, of
        // which exactly the three Visible texts are Strings. A regex that matched nothing would pass
        // the second assertion by accident, which is why the first one is here.
        Assert.assertTrue("the scan read no property at all: $declared", declared.size >= 12)
        Assert.assertEquals(
            "the only String-typed property in the typed bodies is Visible.text (§2.3 invariant 1)",
            listOf("UiBody.kt:text", "UiEnglishRow.kt:text", "UiPreview.kt:text"),
            strings,
        )
    }

    @Test
    fun theSmearIsABitmapAndNotAKey() {
        // The Compose compiler adds a synthetic `$stable` field to a class it can treat as stable,
        // so the declared fields are filtered to the real ones (and that field's presence is itself
        // the proof that the Compose compiler plugin runs over this module).
        val fields = instanceFields(UiConcealment.Smear::class.java)
        Assert.assertEquals(setOf("image", "revealable"), fields.keys)
        Assert.assertEquals(ImageBitmap::class.java, fields.getValue("image"))
        Assert.assertEquals(java.lang.Boolean.TYPE, fields.getValue("revealable"))
    }

    @Test
    fun thePlaceholderReasonIsTheTranslationsOwnCoverVocabulary() {
        val reason = UiConcealment.Placeholder::class.java.getDeclaredField("reason")
        Assert.assertEquals(
            "§1.7's CoverCaption row names DisplayedBody.Cover via TranslationText; a second cover "
                + "enum in :ui would be a second answer to the same question",
            DisplayedBody.Cover::class.java,
            reason.type,
        )
        val noReason = UiConcealment.Placeholder(PlaceholderShape(1, floatArrayOf(0.5f), 7L), null)
        Assert.assertNull(noReason.reason)
    }

    @Test
    fun theMessageRowCarriesExactlyTheFieldsTheDesignNames() {
        Assert.assertEquals(
            "§2.2's declaration is the contract, field for field; §2.2.1 #9 confirms its sources and "
                + "records that §2.3(8)'s `revision` is a section contradiction rather than a field. Plus "
                + "`original`, which §2.2 predates: it is item 17's decision five (2026-09-30), the second "
                + "of the two concealment exceptions - `MIGRATION.md`'s `ui-9` row already pins them as "
                + "two, 'the English row and, since 2026-09-30, the genuinely-failed row' - and it cannot "
                + "be `bottom`, because a blurred row has no second half at all (`BubbleHalves` answers "
                + "`Bottom.NONE`). Plus `gloss`, which §2.2 also predates: the reading aid's tappable "
                + "words are a rule (`GlossText`) the projector must ask and a Composable must not (§7.4), "
                + "and their offsets belong to the very string `top` draws. Plus `attachment` (2026-10-08): "
                + "§3.6's transfer cell, which had no field - a file or image row is `top = Absent` by "
                + "construction, so without it a completed transfer drew an empty bubble",
            setOf(
                "id",
                "conversationId",
                "direction",
                "time",
                "type",
                "top",
                "bottom",
                "divider",
                "quote",
                "english",
                "original",
                "review",
                "transfer",
                "attachment",
                "encryption",
                "delivery",
                "reactions",
                "gloss",
                "run",
                "selected",
                "canTranslateNow",
            ),
            instanceFields(UiMessage::class.java).keys,
        )
    }

    @Test
    fun theConversationRowCarriesExactlyTheFieldsTheDesignNames() {
        Assert.assertEquals(
            "§2.2's declaration, field for field: the pointer and its instant, never the text - plus "
                + "`kind` (which §3.5's `Groups` filter and the long-press menu both need, from the "
                + "snapshot's `mode` and its two room attributes), `pinned` (the `pinned_on_top` "
                + "attribute), `withSelf` (a contact fact from the seam), `ongoingCall` (the "
                + "seam's live answer, which the menu's call entry needs), and the six the row draws "
                + "because the deleted `item_conversation.xml` drew them: `time` (its "
                + "`conversation_lastupdate`, worded by `ConversationRowTime`), `presence` (its "
                + "`presence_indicator`, the seam's mapped state), `sender` (its `sender_name`, the "
                + "tree's own three-way rule - a room's counterpart, \"me\", or nothing), `tick` (its "
                + "`message_status`, `ConversationRowMark.tick`'s `@DrawableRes`), `notification` (its "
                + "`notification_status`, `UiNotification`'s five states) and `account` (its `account` "
                + "line, `UiAccountLine`'s rule - an address, not a preference). None of the six is a "
                + "body, and the last four are drawn marks rather than text",
            setOf(
                "id",
                "name",
                "jid",
                "lastMessageId",
                "lastMessageAt",
                "preview",
                "unread",
                "muted",
                "archived",
                "pinned",
                "kind",
                "withSelf",
                "ongoingCall",
                "time",
                "presence",
                "sender",
                "tick",
                "notification",
                "account",
                "language",
                "translation",
            ),
            instanceFields(UiConversation::class.java).keys,
        )
    }

    @Test
    fun aMessageToStringPrintsIdsAndShapesAndNeverABody() {
        val visible = "the drawn translation: a sentence no log line may carry"
        val concealed = "the concealed original: a sentence no log line may carry"
        val message =
            UiMessage(
                id = MessageId("uuid-1"),
                conversationId = ConversationId("uuid-2"),
                direction = Direction.INCOMING,
                time = 1L,
                type = MessageType.TEXT,
                top = UiBody.Visible(visible),
                bottom =
                    UiBody.Concealed(
                        UiConcealment.Placeholder(
                            PlaceholderShape(2, floatArrayOf(0.9f, 0.4f), 7L),
                            DisplayedBody.Cover.TAP,
                        )
                    ),
                divider = true,
                quote = null,
                english = UiEnglishRow.Absent,
                review = null,
                transfer = UiTransferState.None,
                encryption = UiEncryption.Decrypted,
                delivery = UiDeliveryState.Sent,
                reactions = emptyList(),
                gloss = emptyList(),
                run = UiRunFlags(true, true, true, true, false),
                selected = false,
                canTranslateNow = true,
                original = UiOriginalRow.Absent,
            )
        // The concealed sentence stands in for a smear's pixels, which hold no text at all; the drawn
        // one is the leak that matters, because a generated `toString()` would print it.
        val printed = message.toString()
        Assert.assertFalse("the drawn body printed: $printed", printed.contains(visible))
        Assert.assertFalse("the concealed original printed: $printed", printed.contains(concealed))
        Assert.assertTrue(
            "the id must be there, or the line names nothing: $printed",
            printed.contains("uuid-1"),
        )
    }

    @Test
    fun noProjectionSourceHoldsABodyInAMapOrUnderAName() {
        for (file in projectionSources()) {
            val source = code(read(PROJECTION + "/" + file))
            Assert.assertFalse(
                "$file holds a Map<MessageId, String>: §2.3 invariant 3 forbids a map keyed by a "
                    + "message id from holding text",
                MAP_OF_TEXT.containsMatchIn(source),
            )
            for (property in PROPERTY_WITH_A_BODY_NAME.findAll(source)) {
                Assert.fail(
                    "$file declares \"${property.groupValues[1]}\" as a String: §2.3 invariant 3 "
                        + "forbids a String field named *Original* or *Body*"
                )
            }
        }
    }

    @Test
    fun noProjectionSourceNamesAnEntityOrAService() {
        for (file in projectionSources()) {
            for (import in IMPORT_LINE.findAll(code(read(PROJECTION + "/" + file)))) {
                Assert.assertFalse(
                    "$file imports ${import.groupValues[1]}: the projection holds read models and "
                        + "ids, never an entity or a service (§1.8 rule 2, tools/modules.toml's "
                        + "forbid_class_suffixes for :ui)",
                    BANNED_SUFFIX.containsMatchIn(import.groupValues[1]),
                )
            }
        }
    }

    /** Every `.kt` file of the projection package, and never an empty list. */
    private fun projectionSources(): List<String> {
        val dir = File(root(), PROJECTION)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }?.map { it.name }?.sorted()
        Assert.assertFalse("scanned nothing in $dir", files.isNullOrEmpty())
        return files!!
    }

    /** A class's own fields, without the statics and the compiler's synthetic markers. */
    private fun instanceFields(type: Class<*>): Map<String, Class<*>> =
        type.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.name.startsWith("$") }
            .associate { it.name to it.type }

    /** Source with its comments removed, so a KDoc example is not read as a declaration. */
    private fun code(source: String): String =
        source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    /**
     * AGP runs unit tests with the module directory as the working directory, so the checkout is
     * found by a positive search and a miss throws with the directory it started from, rather than
     * scanning nothing and looking green.
     */
    private fun read(relative: String): String {
        val file = File(root(), relative)
        if (!file.isFile) {
            throw AssertionError("missing $file")
        }
        return file.readText()
    }

    private fun root(): File {
        val start = File("").absoluteFile
        var dir: File? = start
        while (dir != null && !File(dir, "settings.gradle.kts").isFile &&
                !File(dir, "settings.gradle").isFile) {
            dir = dir.parentFile
        }
        return dir ?: throw AssertionError("no checkout root above $start")
    }

    private companion object {
        const val PROJECTION = "ui/src/main/java/uk/xa0/tulkki/ui/projection"

        /** §2.3 invariant 1(a)'s list; `UiConcealment` lives in `UiBody.kt`. */
        val DESIGNED_BODY_FILES =
            listOf("UiBody.kt", "UiEnglishRow.kt", "UiMessage.kt", "UiPreview.kt", "UiQuote.kt")

        /** `val name: Type`, in a constructor or a body; the type may carry generics. */
        val PROPERTY = Regex("\\bval\\s+(\\w+)\\s*:\\s*([^,)=\\n]+)")

        /** §2.3 invariant 3's map, whatever the spacing. */
        val MAP_OF_TEXT = Regex("Map\\s*<\\s*MessageId\\s*,\\s*String\\s*>")

        /** §2.3 invariant 3's names, when the type is a String. */
        val PROPERTY_WITH_A_BODY_NAME = Regex("\\bval\\s+(\\w*(?:Original|Body)\\w*)\\s*:\\s*String\\b")

        val IMPORT_LINE = Regex("(?m)^\\s*import\\s+([\\w.]+)")

        /** tools/modules.toml's `:ui` ratchet: the suffixes `check-modules` refuses to see imported. */
        val BANNED_SUFFIX = Regex("\\.(?:Service|Manager|Broker|Receiver|Entity)$")

        val BLOCK_COMMENT = Regex("(?s)/\\*.*?\\*/")
        val LINE_COMMENT = Regex("(?m)//[^\n]*")
    }
}
