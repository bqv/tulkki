package uk.xa0.tulkki.ui.projection

import androidx.compose.ui.graphics.ImageBitmap
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.translation.HeldSend

/**
 * The facts a conversation row cannot read from a [MessageSnapshot] - the second seam, and the wider one,
 * after `PerProcess`'s. `Design: the Compose UI` §2.1's `MessageProjection` is the row's *rules*; this is
 * what only the running app knows.
 *
 * <p>**Why it exists at all.** `MessageSnapshot` is the file's columns, and the row's own facts are not
 * columns: the reply's target row, the live encryption phase, the reason the row is not translated, the
 * bought notes, the live transfer and the attachment's own name/media-type/pixels, the tap's predicate,
 * the English the owner has bought, and the pixels a concealed row is drawn as - which is the one of
 * these that is not a stored fact but a rendering.
 * [strippedBody] and [replyFallback] are the other two that are not row facts at all: the stored body
 * with the reply fallback taken out, and that fallback itself, both declared by the entity's payload
 * tree and carried by no snapshot column.
 * Reading any of them in `:ui` would mean naming the island's service - the `ui-reaches-island` ratchet, a
 * floor that only goes down - so, exactly as `PerProcess` does for the list, the host that already reads the
 * service answers here and the projection keeps the rules.
 *
 * <p>**`NONE` is the honest answer of a caller that knows none of them**, and it is what `:ui`'s own cells
 * use, so a cell never has to spell a live state it is not about. It is the discipline `PerProcess.NONE`
 * follows, so the two seams read alike; the implementation lives beside that one, on the host that already
 * reads the service, and it is ①b-ii's.
 *
 * <p>**Two of §2.2's row fields are deliberately absent from this seam.** `selected` is transient screen
 * selection - the projection must not decide it, and slice ②'s state owns it - and §2.3(8)'s `revision` is
 * the section contradiction `UiMessage`'s own KDoc records. The bitmap is *in* the seam rather than absent
 * from it ([smear], and [EnglishInHand.blurred] for the English row): a pure projection cannot make pixels,
 * so the host draws them and the projection decides only what may be revealed.
 */
interface MessageFacts {

    /**
     * The row the replied-to message points at, or `null`. The reply fallback is text inside
     * `bodies.original`, so the target is **not** in any snapshot: the host resolves it, the second read of
     * the kind the list already does for its pointer.
     */
    fun quoted(message: MessageSnapshot): MessageSnapshot?

    /**
     * How far the row's encryption has got *now* - the snapshot's single `encryption` column says which
     * scheme, never whether it is still being decrypted.
     */
    fun crypto(message: MessageSnapshot): CryptoPhase

    /**
     * Why this row is not translated, or `null` when it is not held back at all: the queue's own hold on
     * an outgoing row, and on a received row the last attempt's reason - the same
     * [HeldSend.HoldReason] vocabulary, read out of the same per-row store the tree reads
     * (`MessageAdapter.coverOf`'s `activity().reasonFor(uuid)`).
     *
     * <p>**Asked per row, so the answer is this row's by construction**, which is what the cover rule
     * needs and why it is one member rather than two: `DisplayedBody.cover(false, reason, reason != null)`
     * is the tree's own line, and a reason another message earned can never be shown on this bubble.
     */
    fun held(message: MessageSnapshot): HeldSend.HoldReason?

    /** The notes and marks the owner has bought for this row, or `null` when there are none to draw. */
    fun review(message: MessageSnapshot): UiReview?

    /** The row's live transfer, or [UiTransferState.None] when it has none. */
    fun transfer(message: MessageSnapshot): UiTransferState

    /**
     * The attachment's own facts the snapshot cannot carry, or `null` from a caller that has none: the
     * file's name, which lives in the `urn:xmpp:sims:1` payload tree and is dropped by the serialised
     * `fileParams` column the snapshot holds, and the pixels of a ready image, which a pure projection
     * cannot build for the reason [smear] gives.
     *
     * <p>**The name is the payload tree's, never the body's.** A file row's stored body is frequently the
     * filename or the OOB address, and drawing it would be the raw-text leak the design forbids; the
     * payload's `name` element is the file's own name, which is metadata and not a message. A host that
     * cannot read the tree answers `null` and the cell draws the kind's generic word instead.
     *
     * <p>It answers `null` by default rather than forcing every implementor to spell a state it is not
     * about - the same discipline [MessageFacts.NONE] follows - so a host that has not been given the
     * port yet still compiles and still draws a named cell.
     */
    fun attachment(message: MessageSnapshot): AttachmentInHand? = null

    /** Whether a tap may buy this row's translation now: the key, the credit, the cap and the hold's own reason. */
    fun canTranslateNow(message: MessageSnapshot): Boolean

    /**
     * The **smeared image** for a row the projection conceals, or `null` from a caller that cannot render
     * one.
     *
     * <p>`UiBody.kt`'s own KDoc carries why it is a bitmap and not a key - §2.3 invariant 1's deliberate
     * deviation: "a key would force the Composable to look the text up ... a bitmap contains no text to look
     * up, cannot be read back by `uiautomator`". So the image is what keeps the concealed original out of the
     * UI model entirely, which means **a pure projection cannot make one** and the host must: the tree
     * already renders it (`SecondHalfView`'s smear), and that is the precedent.
     *
     * <p>**It is the concealed second half's pixels and never the English row's**: a received row with a
     * translation can hide two different strings at once - somebody else's original under the divider and
     * the English row under that - so one image per row could only be one of them, and the English row's
     * own pair travels in [english].
     *
     * <p>`revealable` is **not** here, and that is the split: whether the owner may reveal stays the
     * projection's decision (the failure and language facts), while this seam only draws.
     */
    fun smear(message: MessageSnapshot): ImageBitmap?

    /**
     * The English this process holds for the row, or `null` when there is none in hand; the English row's
     * own two facts, and the reason they are one member rather than two.
     *
     * <p>**Nothing about it is a column.** The English is a *purchase*: it is bought by the receive path or
     * by the owner's tap and memoised for the process (`MessageAdapter.englishTexts`, read back out of
     * `EnglishLookup`'s cache after a restart), never written to the row, which is what keeps it out of the
     * notification, the conversation-list preview and search (§2.3 invariant 4's rule, applied to the one
     * string that is not the original). [EnglishInHand.text] is what a revealed row draws, and it is a
     * `String` the projection may hold because `UiEnglishRow.Visible` is one of the design's own three
     * permitted text carriers (§2.3 invariant 1(a)) and the projection is its only producer.
     * [EnglishInHand.blurred] is the pixels a *blurred* row draws, in the same shape [smear] gives the
     * second half: a row may hide both strings at once, so the two images cannot be one member.
     *
     * <p>The **original-was-English** case answers here too, and it is the owner-approved exception: when
     * the row's own original was already English there was nothing to buy, so the host answers with that
     * original's words and a blur of it, and the projection never has to reach past the seam for either.
     */
    fun english(message: MessageSnapshot): EnglishInHand?

    /**
     * The row's conversation-language side with its reply fallback removed - `Message#getBody(true)`'s
     * strip - or `null` when the row holds no text at all.
     *
     * <p>`BubbleHalves` and `ReplyQuote` must be handed **this**, never [MessageSnapshot.bodies]'s own
     * original: a reply's stored body is the quote it carries plus the text that is its own, and the
     * quote is somebody else's original - drawn from the referenced row by `ReplyQuote` and concealed
     * there. Letting the composed body through a second time prints the quoted original as ordinary
     * readable text under a sent bubble's divider, which is the leak `docs/MIGRATION.md`'s audit closed
     * with `getBody(true)` and the Compose swap reopened by reading the snapshot.
     *
     * <p>It is the host's because the declared fallback span - the `<body start end/>` inside the
     * `urn:xmpp:reply:0` payload - lives in the entity's payload tree, and no column of a
     * [MessageSnapshot] carries it. The state decision above the halves (`DisplayedBody`'s needsTranslation)
     * still sees the composed body, deliberately: whether translation was needed is a question about the
     * message as it arrived. A caller with no live row cannot find the span, so [NONE] answers the composed
     * body and the two do not pretend to differ.
     */
    fun strippedBody(message: MessageSnapshot): String?

    /**
     * The reply's own copy of the text it quotes, for the one case where the referenced row cannot be
     * resolved: the Java `MessageAdapter.replyFallback`, read through the declared
     * `urn:xmpp:reply:0` span rather than scanned out of the body.
     *
     * <p>It is the **second** read `MessageProjection.quote` makes, and it is the only one that can
     * tell a row that is not a reply from a reply whose target is gone: [quoted] answers a row or
     * nothing, so a `null` here is "this row is not a reply" and the projection draws no quote rather
     * than an empty one. A reply whose fallback span cannot be placed answers `""`, which
     * `ReplyQuote.unresolved` still covers while the interpreter is on and draws as nothing off it.
     *
     * <p>`null` from a caller with no live row is the honest nothing - it has no payload tree to read
     * the declared span from - and drawing nothing is the safe direction.
     */
    fun replyFallback(message: MessageSnapshot): String?

    companion object {

        /** The answer of a caller with no live state: nothing quoted, no crypto phase, nothing held. */
        val NONE: MessageFacts =
            object : MessageFacts {
                override fun quoted(message: MessageSnapshot): MessageSnapshot? = null

                override fun crypto(message: MessageSnapshot): CryptoPhase = CryptoPhase.NONE

                override fun held(message: MessageSnapshot): HeldSend.HoldReason? = null

                override fun review(message: MessageSnapshot): UiReview? = null

                override fun transfer(message: MessageSnapshot): UiTransferState = UiTransferState.None

                /** No live row and no payload tree: no name and no pixels to give. */
                override fun attachment(message: MessageSnapshot): AttachmentInHand? = null

                override fun canTranslateNow(message: MessageSnapshot): Boolean = false

                override fun smear(message: MessageSnapshot): ImageBitmap? = null

                override fun english(message: MessageSnapshot): EnglishInHand? = null

                /** No live row, so no declared span to read: the composed body is all a snapshot knows. */
                override fun strippedBody(message: MessageSnapshot): String? = message.bodies.original

                /** And no payload tree either, so no reply fallback is in hand; the row draws no quote. */
                override fun replyFallback(message: MessageSnapshot): String? = null
            }
    }
}

/**
 * The English this process holds for one row: the words, and the pixels a blurred row draws in their
 * place.
 *
 * <p>It is a plain value and not a `Ui*` type, because it is the seam's own answer and never reaches a
 * Composable: the projection reads it, turns it into `UiEnglishRow.Visible` or a `Smear` of [blurred], and
 * drops the rest - §2.3 invariant 3's "build the smear and drop the reference in the same call", with the
 * host doing the building for the reason [MessageFacts.smear] gives.
 *
 * <p>[blurred] may be `null` from a host that cannot render pixels, exactly as [MessageFacts.smear] may:
 * the projection conceals with a placeholder rather than with text, and a JVM cell reaches that branch
 * because there is no graphics backend to build an `ImageBitmap` with.
 */
data class EnglishInHand(val text: String, val blurred: ImageBitmap?) {

    /** The words as a shape and nothing else: a length cannot be restored to the sentence. */
    override fun toString(): String = "EnglishInHand(text=${text.length} chars, blurred=${blurred != null})"
}

/**
 * The attachment's own facts as the host holds them - the half of an attachment cell that is not a
 * snapshot column and not a rule.
 *
 * <p>[name] is the payload tree's `name` element; the serialised `fileParams` string the snapshot stores
 * is the transfer's numeric row (`url|size|width|height|runtime`) and carries no name. [thumbnail] is
 * pixels, which the projection cannot make ([MessageFacts.smear]).
 *
 * <p>It is a plain value and not a `Ui*` type, because it is the seam's own answer: the projection reads
 * it, turns it into the cell and drops it - the same "build it and drop the reference" discipline
 * [EnglishInHand] follows.
 *
 * <p>[thumbnail] may be `null` from a host that cannot render, exactly as [MessageFacts.smear] may: the
 * cell then draws a named plate instead of pixels, and a ready image never becomes an empty bubble.
 * [name] may be `null` from a host that cannot read the payload tree, and the cell draws the kind's
 * generic word rather than the stored body, which is not the file's name to draw.
 */
data class AttachmentInHand(val name: String?, val thumbnail: ImageBitmap?) {

    /** Shapes only: a filename must not reach a log line or a crash report. */
    override fun toString(): String =
        "AttachmentInHand(name=${name != null}, thumbnail=${thumbnail != null})"
}

/**
 * The live half of a row's encryption.
 *
 * <p>The stored `encryption` column says which scheme a row carries and its outcome when there is one -
 * `PGP`, `OTR`, `DECRYPTION_FAILED`, `AXOLOTL_NOT_FOR_THIS_DEVICE`, `AXOLOTL_FAILED` - and the projection
 * reads those from the snapshot. What no column carries is the **phase**: a row waiting on its key, one
 * whose stanza is encrypted but not yet read, and one that has been decrypted look the same in the file
 * until the model has classified them.
 */
enum class CryptoPhase {
    /** Not an encrypted row at all. */
    NONE,

    /** Encrypted, and the key is not in hand yet. */
    PENDING,

    /** Encrypted and still not readable. */
    ENCRYPTED,

    /** Encrypted, and readable. */
    DECRYPTED,
}
