package uk.xa0.tulkki.ui.projection

import androidx.compose.ui.graphics.ImageBitmap
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.Reaction
import uk.xa0.tulkki.translation.BubbleHalves
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.EnglishRow
import uk.xa0.tulkki.translation.GlossText
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.translation.ReplyQuote
import uk.xa0.tulkki.translation.SecondHalf

/**
 * One conversation's rows, `Design: the Compose UI` §2.1's projector and §2.3 invariant 2's "the only
 * place `SecondHalf.of`, `DisplayedBody.of`, `BubbleHalves.of`, `EnglishRow.of`, `ReplyQuote.of` and
 * `ReviewMarks` are called for Compose".
 *
 * <p>**What it is.** Plain Kotlin, no Composable and no entity: the whole list goes in, the whole list
 * comes out. A row is built from three sources and nothing else - the snapshot's scalars, the rules in
 * `:translation`, and the two seams that are the running app's own answers ([MessageFacts] for the row,
 * [PerProcess] for the list). [MessageProjection] never reads the file, never reads a setting and never
 * reads a clock except the day boundary §4.1's runs are cut on.
 *
 * <p>**The concealment decision is made here, before [UiMessage] exists** (§2.3 invariant 2), which is
 * why the row's bodies are [UiBody] and never a `String`: a concealed half is pixels, a covered body is a
 * placeholder with a reason, and a Composable has no field to receive the original in even by mistake.
 * Three of the rules that decide it are worth stating where they are enforced:
 *
 * <ul>
 *   <li>[BubbleHalves]' invariant: `top` is always the app language's side and `bottom` the
 *       conversation's. Received, `bottom` is somebody else's original and never readable; sent, it is the
 *       wire text and readable unless the owner's own conceal switch hides it;
 *   <li>[SecondHalf] can only ever **remove** a half. It is read after [BubbleHalves] and can answer
 *       nothing/concealed/readable - never "make a received original readable" - and with the interpreter
 *       off it answers nothing for every combination;
 *   <li>with the interpreter off every rule above answers the plain-client answer on its first
 *       statement: one half, no cover, no English row, no second half. The two modes are one code path
 *       with one derived value in front of it, never two paths.
 * </ul>
 *
 * <p>**The five inputs are holders on purpose.** Five adjacent booleans and two adjacent language
 * `String`s at a call site are a swap waiting to happen - a bubble drawn in the wrong language, a
 * concealed original drawn readable - so the switches travel as [ProjectionSettings] and the
 * conversation's own facts as [ConversationFacts], and every call site inside this file reads its
 * arguments by name. `selected` is deliberately **not** an input: §2.2.1 #2 and §2.3(2) make it the
 * screen's transient selection, so the projector answers `false` and slice ②'s state owns the field.
 *
 * <p>**What is named rather than guessed**, each because a snapshot cannot decide it:
 *
 * <ul>
 *   <li>`asking` - "the owner tapped this message and the request is in flight" - has no fact yet, so the
 *       cover is asked with `false` and a tapped row says `TAP` until the screen's own state carries it.
 *       The tree's marker is `MessageAdapter.askingBodies`, process state, and it arrives with the host;
 *   <li>a `TYPE_STATUS` row that is a **pin** renders as a reference to a row (§5's invariant table,
 *       item 16), and §2.2's declaration has no field for a reference: the top is the app-owned words
 *       and the pin waits for its own slice;
 *   <li>a **transfer** row's top is [UiBody.Absent] - the body of a file row is the address or the path,
 *       not prose, and whether it also carries a caption is not decided by any snapshot field - so the
 *       attachment's own cell carries what the row draws ([UiAttachment]); a caption, if the row has
 *       one, still has no field to arrive in and is the named hole this bullet keeps;
 *   <li>a **custom emoji** reaction group is not drawn: [UiReaction] has no image field and the chip's
 *       own thumbnail is a `Cid` the model resolves through a thumbnailer, so the groups are aggregated
 *       over the unicode reactions and the custom ones wait for that cell. They are dropped explicitly
 *       rather than by an exception - `Reaction.aggregated`'s one-argument form dereferences its
 *       thumbnailer the moment an element carries a cid;
 *   <li>the **review**'s marks are offsets into `top`'s own text, and they are paired where the drawn text
 *       is known: the seam's host answers with the pair already resolved (§2.2.1 #3's `ReviewKey.forBubble`
 *       and `ReviewMarks.in` need the stored review and the drawn body, neither of which is a snapshot
 *       column). This is the one clause of §2.3(2) that moved into the seam, and it moved because the
 *       alternative is a second reader of the review store in `:ui`.
 * </ul>
 *
 * <p>The run flags are [MessageRuns]' own and are computed from the whole list, never from the row
 * (§2.2's `run`: "computed from the whole list, not from this row"). One consequence is worth naming:
 * `runFlags` cuts the day on the process's own zone, because §4.2's day separators are drawn by the screen
 * and a caller that draws a different boundary is a caller that passes one.
 */
object MessageProjection {

    /** §6.1's shape, fixed: the placeholder is two lines and the body's length never reaches it. */
    private const val PLACEHOLDER_LINES = 2

    /** The first line's width as a fraction of the bubble; §6.1's `LAST_LINE_FRACTION` is the second. */
    private const val FIRST_LINE_FRACTION = 0.75f

    /** §6.1: "the last line reads as a text line, not a full bar". */
    private const val LAST_LINE_FRACTION = 0.5f

    /**
     * The whole list, in the list's own order.
     *
     * @param facts the row-level live facts, [MessageFacts.NONE] when the caller knows none of them.
     * @param settings the five display switches, at their shipped answers when the caller has not read
     *     them: a caller with no settings screen gets exactly what the app did before one existed.
     * @param conversation the facts every row of this conversation shares.
     * @param revealed the rows whose English row the owner has unblurred in this process, by local uuid:
     *     screen state, like `selected`, which is why it is an argument and not a seam member.
     */
    @JvmStatic
    fun of(
        messages: List<MessageSnapshot>,
        facts: MessageFacts = MessageFacts.NONE,
        settings: ProjectionSettings = ProjectionSettings.SHIPPED,
        conversation: ConversationFacts,
        revealed: Set<MessageId> = emptySet(),
        revealedOriginals: Set<MessageId> = emptySet(),
    ): List<UiMessage> {
        val runs =
            MessageRuns.runFlags(
                messages,
                conversation.mergeWindow,
                conversation.group,
                conversation.forceNames,
            )
        return messages.mapIndexed { at, message ->
            row(message, runs[at], facts, settings, conversation, revealed, revealedOriginals)
        }
    }

    /** One row. */
    private fun row(
        message: MessageSnapshot,
        run: UiRunFlags,
        facts: MessageFacts,
        settings: ProjectionSettings,
        conversation: ConversationFacts,
        revealed: Set<MessageId>,
        revealedOriginals: Set<MessageId>,
    ): UiMessage {
        val id = MessageId(message.id)
        val status = status(message)
        val type = type(message)
        val original = message.bodies.original.orEmpty()
        val interpreter = conversation.interpreter
        // The halves see the **fallback-free** body, never the composed `original` above: a sent reply's
        // wire body is the quote it carries plus the owner's own text, and the quote is the referenced
        // row's original - drawn by the `quote` block below and concealed there. Letting the composed
        // body through as well draws that original a second time as readable text under the divider,
        // which is the leak the Java `halves()` closed with `getBody(true)`. Only the host can find the
        // declared fallback span, so it comes through the seam (`MessageFacts.strippedBody`). `displayed`
        // keeps deciding on the composed body, deliberately: whether translation was needed is a question
        // about the message as it arrived.
        val halves =
            BubbleHalves.of(
                facts.strippedBody(message),
                message.bodies.translated,
                message.translationState.toInt(),
                status,
                conversation.conversationName,
                interpreter,
            )
        val displayed =
            DisplayedBody.of(
                original,
                message.bodies.translated,
                message.translationState.toInt(),
                DisplayedBody.needsTranslation(
                    status,
                    original,
                    conversation.conversationName,
                    interpreter,
                ),
                interpreter,
            )
        // One ask, two readers: the cover's reason (a received row whose attempt failed, an outgoing row
        // the queue is holding) and the delivery state below.
        val reason = facts.held(message)
        // The live transfer, asked once and read by both the row's `transfer` field and its cell.
        val transfer = facts.transfer(message)
        val top = top(message, type, original, displayed, reason)
        // Item 17's decision five, computed **here and now**, from the row's own facts and before a
        // single setting is read below: may this row offer its own original? `SecondHalf` still decides
        // whether the half is drawn at all; this only widens what that half's tap may do.
        val isIncoming = incoming(message)
        val offersOriginal =
            OriginalReveal.eligible(
                direction(message),
                displayed.isBlurred(),
                reason,
            )
        // Only a text row divides: a system row is one line of the app's own words, and a call or a
        // transfer is a cell rather than a body.
        val bottom =
            if (type == MessageType.TEXT) {
                bottom(
                    message,
                    SecondHalf.of(
                        halves.bottom(),
                        settings.showSecondHalf,
                        settings.showConcealedOriginal,
                        settings.concealOwnSecondHalf,
                        interpreter,
                    ),
                    halves,
                    facts,
                )
            } else {
                null
            }
        // The reading aid's targets, from the same rule the Java adapter asked (`MessageAdapter
        // .addGlossSpans` over `GlossText.words`). `displayed` decides, so a covered body answers
        // empty and keeps its bubble tap for "translate this one, now"; the interpreter's own off
        // state answers empty for every word, so a plain client never gets a tap target and no
        // second guard is written anywhere.
        val gloss =
            if (type == MessageType.TEXT) {
                GlossText.words(displayed, conversation.studyLanguage, interpreter).map {
                    UiGlossWord(it.word, it.start, it.end)
                }
            } else {
                emptyList()
            }
        return UiMessage(
            id = id,
            conversationId = ConversationId(message.conversationId.orEmpty()),
            direction = direction(message),
            time = message.timeSent ?: 0L,
            type = type,
            top = top,
            bottom = bottom,
            divider = bottom != null,
            quote = quote(message, type, facts, settings, conversation),
            english = english(message, type, facts, settings, conversation, halves, revealed, id),
            original = offeredOriginal(message, original, facts, offersOriginal, revealedOriginals.contains(id)),
            review = facts.review(message),
            transfer = transfer,
            attachment = attachment(message, type, transfer, facts),
            encryption = encryption(message, facts),
            delivery = delivery(message, status, reason),
            reactions = reactions(message),
            gloss = gloss,
            run = run,
            // §2.2.1 #2: the screen's transient selection, which the projector must not decide.
            selected = false,
            canTranslateNow = facts.canTranslateNow(message),
        )
    }

    /**
     * The top half: always the app language's side ([BubbleHalves]' invariant), and never the original of
     * a message that needed translating and did not get one.
     *
     * <p>`TYPE_STATUS` is the one kind that does not go through `DisplayedBody`: a system row's words are
     * the app's own - "Alice left the room", a subject line - so the concealment rules, which are about
     * somebody else's language, must not be asked about them at all. §5's invariant table says as much:
     * those two kinds are "app-owned text (a system message) or a reference to a row (a pin)". The pin
     * half is a named hole above.
     */
    private fun top(
        message: MessageSnapshot,
        type: MessageType,
        original: String,
        displayed: DisplayedBody,
        reason: HeldSend.HoldReason?,
    ): UiBody =
        when (type) {
            MessageType.SYSTEM -> text(original)
            // A call's body is the status payload the preview parses, and a transfer's is an address or a
            // path: neither is drawn as prose.
            MessageType.CALL, MessageType.TRANSFER -> UiBody.Absent
            MessageType.TEXT ->
                if (displayed.isBlurred()) {
                    UiBody.Concealed(
                        UiConcealment.Placeholder(
                            placeholderShape(message.id),
                            // The tree's own line: `DisplayedBody.cover(false, failure, failure != null)`
                            // over a store that only ever hands back this message's reason, and `asking`
                            // is the hole named above.
                            DisplayedBody.cover(false, reason, reason != null),
                        )
                    )
                } else {
                    text(displayed.text())
                }
        }

    /** A body that has words is drawn; an empty one is [UiBody.Absent] rather than a blank line. */
    private fun text(body: String): UiBody = if (body.isEmpty()) UiBody.Absent else UiBody.Visible(body)

    /**
     * The second half, or `null` where there is none - [SecondHalf] decides both at once, which is why
     * the divider is its answer and not a second rule.
     *
     * <p>Asked for a text row only: a system, call or transfer row is one thing and never divides,
     * whatever the row happens to hold.
     */
    private fun bottom(
        message: MessageSnapshot,
        second: SecondHalf,
        halves: BubbleHalves,
        facts: MessageFacts,
    ): UiBody? =
        when (second.kind()) {
            SecondHalf.Kind.NONE -> null
            SecondHalf.Kind.READABLE -> UiBody.Visible(halves.bottomText())
            SecondHalf.Kind.CONCEALED ->
                UiBody.Concealed(
                    concealment(
                        message,
                        facts.smear(message),
                        // The owner's own text may be revealed even while it is concealed; somebody
                        // else's never is *here* - the second half is not where item 17's exception
                        // lives, because a blurred row has no second half at all. `isBottomReadable` is
                        // true only where the half *would* be the owner's own words.
                        revealable = halves.isBottomReadable(),
                    )
                )
        }

    /**
     * The concealed pixel source for one string: the host's smear when it could draw one, and a
     * placeholder that carries the reveal decision when it could not.
     *
     * <p>**The placeholder carries [revealable] because the no-pixels path must not lose it.** Item 17's
     * exception would otherwise be offered only on a host that can render a blur, and whether somebody
     * else's original may be read is not a question about a device's graphics. The decision itself is
     * never made here: it arrives as an argument, from [OriginalReveal] for the offered original and
     * from `BubbleHalves` for the owner's own half.
     */
    private fun concealment(message: MessageSnapshot, image: ImageBitmap?, revealable: Boolean): UiConcealment =
        if (image == null) {
            // A half that has no pixels has no reason to give either: the cover vocabulary is about a
            // translation that did not happen, and this one happened.
            UiConcealment.Placeholder(placeholderShape(message.id), null, revealable)
        } else {
            UiConcealment.Smear(image, revealable)
        }

    /**
     * Item 17's decision five, as a row: the offered original, or nothing.
     *
     * <p>`revealedOriginals` is the screen's own set - a tap on the strip, held for the process exactly
     * as the English row's reveal is - and it is the **only** thing that makes this row
     * [UiOriginalRow.Visible]. The gate is [OriginalReveal]'s, computed in [row] from the row's own
     * facts before any setting is read, and it is the only path by which somebody else's original
     * becomes readable text.
     */
    private fun offeredOriginal(
        message: MessageSnapshot,
        original: String,
        facts: MessageFacts,
        offers: Boolean,
        revealedHere: Boolean,
    ): UiOriginalRow =
        when {
            !offers -> UiOriginalRow.Absent
            revealedHere -> UiOriginalRow.Visible(original)
            else -> UiOriginalRow.Concealed(concealment(message, facts.smear(message), revealable = true))
        }

    /**
     * The quote, when the reply answers a row the host could resolve.
     *
     * <p>It goes through the same two rules a bubble goes through - [ReplyQuote] on the referenced row's
     * stored halves, with its own nested reply fallback stripped the way the bubble's halves are, and
     * [SecondHalf] for whether its second half is drawn and whether it may be read - so a quote can never
     * show what the conversation would not have shown for that message. Following `displayReplyQuote`, it
     * exists only on a text row.
     *
     * <p>A covered quote is a placeholder with the plain cover: the referenced row needed a translation and
     * has none, so there is nothing to draw on top and no second half to divide. Its tap is not this
     * bubble's tap - a tap on the reply means "translate this reply" - which is why the cover is `TAP` and
     * not `TRANSLATING`.
     *
     * <p>**A reply whose target cannot be resolved is still a quote.** `MessageAdapter.replyReference`
     * answering nothing sent the Java down `ReplyQuote.unresolved(interpreter, replyFallback(message))`,
     * and this follows it: the seam's [MessageFacts.replyFallback] is asked only here, it answers `null`
     * for a row that is not a reply, and anything else reaches [unresolved]. Reading the fallback rather
     * than drawing nothing is what keeps the interpreter-off client showing the reply's own copy, exactly
     * as the tree did.
     */
    private fun quote(
        message: MessageSnapshot,
        type: MessageType,
        facts: MessageFacts,
        settings: ProjectionSettings,
        conversation: ConversationFacts,
    ): UiQuote? {
        if (type != MessageType.TEXT) {
            return null
        }
        val interpreter = conversation.interpreter
        val referenced = facts.quoted(message)
        if (referenced == null) {
            // `replyReference` answered nothing, and the Java drew
            // `ReplyQuote.unresolved(interpreter, replyFallback(message))` rather than no quote at
            // all. The fallback's declared span is in the payload tree and no snapshot column
            // carries it, so it is the seam's second read; a `null` from that read means "this row
            // is not a reply", and then there is nothing to draw.
            val fallback = facts.replyFallback(message) ?: return null
            return unresolved(ReplyQuote.unresolved(interpreter, fallback), message)
        }
        val resolved =
            ReplyQuote.of(
                status(message),
                // The referenced row's body without its *own* nested reply fallback, exactly as the
                // bubble's halves are built: `ReplyQuote.of`'s own contract says this parameter is
                // `Message#getBody(true)`'s answer, and the reader is the same seam member.
                facts.strippedBody(referenced),
                referenced.bodies.translated,
                referenced.translationState.toInt(),
                status(referenced),
                conversation.conversationName,
                interpreter,
            )
        val id = MessageId(referenced.id)
        if (resolved.isCovered()) {
            return UiQuote(
                id,
                UiBody.Concealed(
                    UiConcealment.Placeholder(
                        placeholderShape(referenced.id),
                        DisplayedBody.Cover.TAP,
                    )
                ),
                null,
                false,
            )
        }
        val second =
            SecondHalf.of(
                resolved.bottom(),
                settings.showSecondHalf,
                settings.showConcealedOriginal,
                settings.concealOwnSecondHalf,
                interpreter,
            )
        val bottom =
            when (second.kind()) {
                SecondHalf.Kind.NONE -> null
                SecondHalf.Kind.READABLE -> text(resolved.bottomText())
                SecondHalf.Kind.CONCEALED ->
                    UiBody.Concealed(
                        concealment(
                            referenced,
                            facts.smear(referenced),
                            revealable = resolved.isBottomReadable(),
                        )
                    )
            }
        return UiQuote(id, text(resolved.top()), bottom, bottom != null)
    }

    /**
     * The quote of a reply whose referenced row cannot be resolved locally, from `ReplyQuote`'s own
     * answer - never a second concealment rule.
     *
     * <p>On, `ReplyQuote.unresolved` is covered: the placeholder carries the plain `TAP` cover and
     * there is no second half, the shape seeded from this reply's own id because there is no target to
     * seed it from. Off, the reply's own fallback copy is the one half a plain client draws. Either
     * way the row is [UiQuote.messageId]-less: there is no referenced row to point at, and a tap that
     * acted on the reply would be a different purchase.
     */
    private fun unresolved(quote: ReplyQuote, message: MessageSnapshot): UiQuote {
        if (quote.isCovered()) {
            return UiQuote(
                null,
                UiBody.Concealed(
                    UiConcealment.Placeholder(
                        placeholderShape(message.id),
                        DisplayedBody.Cover.TAP,
                    )
                ),
                null,
                false,
            )
        }
        return UiQuote(null, text(quote.top()), null, false)
    }

    /**
     * The composer's reply preview, from the referenced row as the **live** fragment has it.
     *
     * <p>It asks the row projection's own two questions and invents no third one: [ReplyQuote] has
     * already decided whether the quote is covered, and this only types the answer. A covered quote is
     * a placeholder with the same `TAP` reason the row's covered quote carries - what the cover means
     * ("translation was needed and did not happen") does not change with the surface it is drawn on.
     *
     * <p>**The second half is not merely hidden, it is absent.** The composer is always outgoing and
     * its preview shows the app-language half, which is why [UiQuote.bottom] is `null` here and the
     * preview never reads it. That is stronger than concealment, not weaker: there is no field for the
     * referenced row's original to reach.
     *
     * <p>[uuid] is the referenced row's local uuid. The composer's quote has no tap and the host does
     * nothing with it, but the field is carried so the placeholder shape is seeded from the row it
     * stands for, as §6.1 requires, and so the composer's quote stays the one [UiQuote] type rather
     * than a second shape beside it.
     */
    @JvmStatic
    fun replyPreview(quote: ReplyQuote, uuid: String): UiQuote =
        if (quote.isCovered()) {
            UiQuote(
                MessageId(uuid),
                UiBody.Concealed(
                    UiConcealment.Placeholder(
                        placeholderShape(uuid),
                        DisplayedBody.Cover.TAP,
                    )
                ),
                null,
                false,
            )
        } else {
            UiQuote(MessageId(uuid), UiBody.Visible(quote.top()), null, false)
        }

    /**
     * The English row, in its four states.
     *
     * <p>[EnglishRow] decides which one - the master switch, whether the bubble has a translation to
     * divide, the app language, the conversation's language for the *placement*, and the language the
     * original was in - and this is only what the answer is made of. Nothing here can spend anything: a
     * bare bar is a bar, and the words come from [MessageFacts.english] or from the row's own original.
     *
     * <p>Both the text and the pixels come from the one bought English, and where the original *was* the
     * English there was nothing to buy: the text is then the row's own body and the pixels are the ones
     * the concealed second half already drew - the same string, so the same image, which is why that
     * fallback is not a second ask.
     *
     * <p>It exists on a text row only, because the tree drew it from `displayTextMessage` and nowhere else:
     * a bar under an attachment would offer to buy the translation of a URL.
     */
    private fun english(
        message: MessageSnapshot,
        type: MessageType,
        facts: MessageFacts,
        settings: ProjectionSettings,
        conversation: ConversationFacts,
        halves: BubbleHalves,
        revealed: Set<MessageId>,
        id: MessageId,
    ): UiEnglishRow {
        if (type != MessageType.TEXT) {
            return UiEnglishRow.Absent
        }
        val inHand = facts.english(message)
        val originalIsEnglish = EnglishRow.isEnglish(message.translationLang)
        val flag = revealed.contains(id)
        val received = status(message) == Message.STATUS_RECEIVED
        val row =
            if (received) {
                EnglishRow.of(
                    settings.showEnglishRow,
                    halves.isDivided(),
                    conversation.appLanguage,
                    conversation.conversationLanguage,
                    message.translationLang,
                    inHand != null || originalIsEnglish,
                    flag,
                    conversation.interpreter,
                )
            } else {
                // The owner's own bubble: what an English retranslation of the wire could add, which is
                // nothing at all in a room that already speaks English - `ofSent`'s own rule.
                EnglishRow.ofSent(
                    settings.showSentEnglishRow,
                    conversation.conversationLanguage,
                    inHand != null,
                    flag,
                    conversation.interpreter,
                )
            }
        return when {
            !row.isShown -> UiEnglishRow.Absent
            row.isReadable ->
                UiEnglishRow.Visible(inHand?.text ?: message.bodies.original.orEmpty())
            row.isBlurred ->
                UiEnglishRow.Concealed(
                    concealment(
                        message,
                        // Always revealable: the blur is the owner's own answer to this row, whoever
                        // wrote the words - which is what makes it the mirror of the concealed half.
                        inHand?.blurred ?: facts.smear(message),
                        revealable = true,
                    )
                )
            else -> UiEnglishRow.Pending
        }
    }

    /**
     * The reaction chips, decoded here and not in `:data` (§2.2.1 #4: "the projector decodes, not
     * `:data`"; the snapshot carries the column raw by rule and `:data`'s `reactions/` publishes only the
     * two statements over the `String`).
     *
     * <p>The tree's own reader and its own grouping rule, called rather than reimplemented:
     * `Reaction.fromString` parses, `Reaction.aggregated` groups, and the **count is the group's size** -
     * §2.2.1 #4 is explicit that it is not a JSON key. `mine` is the document's `received` negated for one
     * element of the group, which is what the chip filled itself in for, and it needs no address: the
     * document says who reacted.
     */
    private fun reactions(message: MessageSnapshot): List<UiReaction> {
        val document = message.reactions ?: return emptyList()
        // A custom emoji's group key carries a cid and its chip is an image the model resolves through a
        // thumbnailer, which `UiReaction` has no field for. Dropping them here is what keeps
        // `Reaction.aggregated`'s one-argument form from dereferencing its absent thumbnailer.
        val unicode = Reaction.fromString(document).filter { it.cid == null }
        if (unicode.isEmpty()) {
            return emptyList()
        }
        return Reaction.aggregated(unicode).reactions.map { group ->
            UiReaction(
                // The aggregate key, which is the string the chip draws.
                emoji = group.key.unicode,
                count = group.value.size,
                mine = group.value.any { !it.received },
            )
        }
    }

    /**
     * §2.2.1 #1's table, with the live phase on top of the column.
     *
     * <p>The column says which scheme a row carries and its outcome when there is one; no column says
     * whether a PGP row is still being decrypted, so [MessageFacts.crypto] answers that and it is read
     * only where the column is a ciphertext that is not yet known to be readable. A row the column already
     * calls a failure or another device's stays that: the phase cannot un-fail it.
     */
    private fun encryption(message: MessageSnapshot, facts: MessageFacts): UiEncryption {
        val phase = facts.crypto(message)
        return when (message.encryption) {
            // PGP: the armour *is* the stored body, so an unclassified row is drawn as encrypted.
            Message.ENCRYPTION_PGP.toLong() -> phase(phase, UiEncryption.Encrypted)
            // OMEMO arrives in the clear once it is read, so an unclassified row is drawn as decrypted.
            Message.ENCRYPTION_AXOLOTL.toLong() -> phase(phase, UiEncryption.Decrypted)
            Message.ENCRYPTION_DECRYPTED.toLong(),
            Message.ENCRYPTION_OTR.toLong() -> UiEncryption.Decrypted
            Message.ENCRYPTION_DECRYPTION_FAILED.toLong() -> UiEncryption.Failed(EncryptionFailure.PGP)
            Message.ENCRYPTION_AXOLOTL_FAILED.toLong() -> UiEncryption.Failed(EncryptionFailure.OMEMO)
            Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE.toLong() -> UiEncryption.NotForThisDevice
            else -> UiEncryption.None
        }
    }

    /** The live phase, falling back to what the column alone says when the model has not classified it. */
    private fun phase(phase: CryptoPhase, unclassified: UiEncryption): UiEncryption =
        when (phase) {
            CryptoPhase.PENDING -> UiEncryption.Pending
            CryptoPhase.ENCRYPTED -> UiEncryption.Encrypted
            CryptoPhase.DECRYPTED -> UiEncryption.Decrypted
            CryptoPhase.NONE -> unclassified
        }

    /**
     * §2.2.1 #2's table, with the hold read first because the status alone cannot tell Tulkki's held row
     * from upstream's in-flight one.
     *
     * <p>Two answers are named rather than derived. A **received** row has no status line - nobody sent it
     * from this device - and the honest value is `Delivered`: it is here. And `STATUS_SEND_FAILED` maps to
     * `Sending`, because §2.2.1 #2 marks `Failed(reason)` **deferred** (there is no failure-name
     * vocabulary, only the free-form `errorMsg`) and the case does not exist in [UiDeliveryState]. It is
     * deliberately not `Sent`: the projector does not invent a success, and a cell pins it, so the day the
     * vocabulary lands the change is a red cell rather than a silent improvement.
     */
    private fun delivery(
        message: MessageSnapshot,
        status: Int,
        reason: HeldSend.HoldReason?,
    ): UiDeliveryState {
        if (status == Message.STATUS_RECEIVED) {
            return UiDeliveryState.Delivered
        }
        if (reason != null) {
            return UiDeliveryState.Held(reason)
        }
        return when (message.status?.toInt()) {
            Message.STATUS_SEND -> UiDeliveryState.Sent
            Message.STATUS_SEND_RECEIVED -> UiDeliveryState.Delivered
            Message.STATUS_SEND_DISPLAYED -> UiDeliveryState.Displayed
            else -> UiDeliveryState.Sending
        }
    }

    /**
     * §2.2's `Text | System | Transfer | Call` from `Message.TYPE_*`: the three file-bearing types are one
     * kind, a call is its own, and a status row is the app's own words.
     */
    private fun type(message: MessageSnapshot): MessageType =
        when (message.type) {
            Message.TYPE_STATUS.toLong() -> MessageType.SYSTEM
            Message.TYPE_RTP_SESSION.toLong() -> MessageType.CALL
            Message.TYPE_FILE.toLong(),
            Message.TYPE_IMAGE.toLong(),
            Message.TYPE_PRIVATE_FILE.toLong() -> MessageType.TRANSFER
            else -> MessageType.TEXT
        }

    /**
     * §3.6's transfer cell, or `null` for a row that is not a file or an image.
     *
     * <p>**There are four states and one field decides them, because the defect is that three of them
     * drew the same empty row.** [UiTransferState.Ready] is the file on the phone; `Offered`,
     * `Downloading`, `Uploading` and `Checking` are the transfer in flight; [UiTransferState.Failed] and
     * [UiTransferState.Cancelled] are the two terminal outcomes the tree tells apart; and
     * [UiTransferState.None] **inside this cell** is the fourth - a real transfer row with no local file
     * yet. `None` cannot mean "not a transfer", because this function only builds a cell for one.
     *
     * <p>**`Ready` has two sources, and the snapshot's is the durable one.** The host's `transfer` answers
     * `Ready` from the live transferable while it is still held; a completed transfer's object is gone
     * after a restart, so the row's own stored `relativeFilePath` (with `fileDeleted` clear) is the second
     * source and is taken rather than leaving a finished download as an empty cell. A live state wins over
     * the fallback, because it is the row as it stands right now.
     *
     * <p>**The failure fallback is the stored reason, not an invented one.** A transfer row whose own
     * `errorMsg` is set - and whose live transfer is gone - is a transfer that stopped with something said
     * about it, and the tree's own `STATUS_FAILED` outcome is what that means; the cell draws the failure
     * and never the text.
     *
     * @param transfer the row's live transfer, already read by `row` so the seam is asked once
     */
    private fun attachment(
        message: MessageSnapshot,
        type: MessageType,
        transfer: UiTransferState,
        facts: MessageFacts,
    ): UiAttachment? {
        if (type != MessageType.TRANSFER) {
            return null
        }
        val inHand = facts.attachment(message)
        val local =
            (transfer as? UiTransferState.Ready)?.localUri
                ?: message.relativeFilePath?.takeIf { message.fileDeleted != 1L }
        val state =
            when {
                transfer is UiTransferState.Ready -> transfer
                transfer is UiTransferState.Failed ||
                    transfer is UiTransferState.Cancelled ||
                    transfer is UiTransferState.Offered ||
                    transfer is UiTransferState.Downloading ||
                    transfer is UiTransferState.Uploading ||
                    transfer is UiTransferState.Checking -> transfer
                local != null -> UiTransferState.Ready(local)
                message.errorMsg == Message.ERROR_MESSAGE_CANCELLED -> UiTransferState.Cancelled
                message.errorMsg != null -> UiTransferState.Failed
                else -> UiTransferState.None
            }
        val liveSize =
            when (transfer) {
                is UiTransferState.Offered -> transfer.sizeBytes
                is UiTransferState.Downloading -> transfer.sizeBytes
                else -> null
            }
        return UiAttachment(
            kind =
                if (message.type == Message.TYPE_IMAGE.toLong()) {
                    AttachmentKind.IMAGE
                } else {
                    AttachmentKind.FILE
                },
            name = inHand?.name,
            sizeBytes = liveSize ?: attachmentSize(message),
            localUri = local,
            remoteUri = message.oobUri,
            thumbnail = inHand?.thumbnail,
            state = state,
        )
    }

    /**
     * The transfer's own size, from the serialised `fileParams` column through the store's own parser -
     * the same reader `Message.getFileParams()` is built on, so the number the cell draws is the number
     * the queue recorded. `null` is the honest answer for a row whose column carries no size.
     */
    private fun attachmentSize(message: MessageSnapshot): Long? =
        message.fileParams?.let { Message.FileParams(it).size }

    /**
     * The row's status, with the store's own default for a row it has not classified: received. That is
     * `MessagePreview`'s committed reading, and it is the only value that can ask for a translation at
     * all.
     *
     * <p>**It is `internal` because it is the package's one reader of that default, and a second reader
     * is how the two directions came apart.** `MessageRuns` used to compare the raw nullable column
     * instead, so a row the store had not classified read *outgoing* to the run rule and *incoming* to
     * the projection: the row was drawn on the left with a sent run's geometry - the avatar at the run's
     * tail, the run gaps of an own message - which is a live alignment defect and not a porting detail.
     * The default is the Java's own: `Message`'s unannotated `status` is `STATUS_RECEIVED`, a cursor
     * `getInt` on a NULL column answers `0`, and the tree's `getItemViewType` asked
     * `getStatus() <= STATUS_RECEIVED`. The two callers below stay on `==` because the tree's *merge*
     * rule spelled it that way and no persisted row carries a negative status (the only one that is,
     * `STATUS_DUMMY`, is a transient placeholder `FileBackend` names and no read ever draws).
     */
    internal fun status(message: MessageSnapshot): Int =
        (message.status ?: Message.STATUS_RECEIVED.toLong()).toInt()

    /**
     * Whether the row came in, from [status] and nothing else.
     *
     * <p>**The run key and the drawn side must be one answer**, so `MessageRuns` asks this rather than
     * comparing the column itself: a row the store had not classified was drawn incoming and grouped as
     * outgoing, which put a sent run's avatar and gaps on a received-looking bubble.
     */
    internal fun incoming(message: MessageSnapshot): Boolean =
        status(message) == Message.STATUS_RECEIVED

    /** The row's own side, as `UiMessage.direction` carries it: [incoming]'s answer, named for the field. */
    internal fun direction(message: MessageSnapshot): Direction =
        if (incoming(message)) Direction.INCOMING else Direction.OUTGOING

    /**
     * §6.1's shape: a **fixed** two lines, a seed from the message's own id, and nothing else. The body's
     * length is deliberately not consulted - a wrap count would be a function of the hidden text, and the
     * placeholder is specified to carry no information about it at all.
     */
    private fun placeholderShape(id: String): PlaceholderShape =
        PlaceholderShape(
            PLACEHOLDER_LINES,
            floatArrayOf(FIRST_LINE_FRACTION, LAST_LINE_FRACTION),
            seed(id),
        )

    /**
     * §6.1's "64-bit hash of the message id", so the same row shimmers the same shape on every bind and a
     * scroll away and back does not flicker a new pattern. FNV-1a, written out rather than taken from
     * `String.hashCode()`: that one is 32 bits, and its collisions would make two rows share a shape.
     */
    private fun seed(id: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (byte in id.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= FNV_PRIME
        }
        return hash
    }

    /** 14695981039346656037, as the signed `Long` the JVM holds. */
    private const val FNV_OFFSET_BASIS = -3750763034362895579L

    /** 1099511628211. */
    private const val FNV_PRIME = 1099511628211L
}

/**
 * The five display switches, in one value so a call site cannot swap two adjacent booleans - which is the
 * one mistake in this layer that draws an original.
 *
 * <p>Each default is the answer the rule class ships (`SecondHalf`'s three and `EnglishRow`'s two), so a
 * caller that has not read the settings draws exactly what the app drew before the switches existed. The
 * three `SecondHalf` switches keep the order `SecondHalf.of` takes them in, so the holder reads as the
 * rule's own argument list and no call site has to count positions.
 */
data class ProjectionSettings(
    /** The global "show the second half": off, every bubble is a single half and the divider goes with it. */
    val showSecondHalf: Boolean = SecondHalf.SHOW_SECOND_HALF_BY_DEFAULT,
    /** The received side's switch, which can only ever remove the concealed strip. */
    val showConcealedOriginal: Boolean = SecondHalf.SHOW_CONCEALED_ORIGINAL_BY_DEFAULT,
    /** The owner's own half, concealed on request; the one conceal a setting may undo. */
    val concealOwnSecondHalf: Boolean = SecondHalf.CONCEAL_OWN_SECOND_HALF_BY_DEFAULT,
    /** The "show a blurred English translation" master switch, off by default. */
    val showEnglishRow: Boolean = EnglishRow.SHOW_BY_DEFAULT,
    /** The sent side's retranslation switch, off by default and bought by a tap only. */
    val showSentEnglishRow: Boolean = EnglishRow.SHOW_SENT_BY_DEFAULT,
) {

    companion object {
        /** The shipped answers, for a caller that has not read the owner's settings yet. */
        val SHIPPED = ProjectionSettings()
    }
}

/**
 * The facts every row of one conversation shares - the ones that are about the conversation, not the row.
 *
 * <p>They are one holder for the same reason [ProjectionSettings] is: two adjacent language `String`s at a
 * call site is how a bubble ends up with its halves swapped, and `conversationName` and
 * `conversationLanguage` are asked in the same breath by the bare-name rule.
 *
 * @param interpreter the interpreter's off switch, as every rule takes it. Required: an off state is
 *     `Interpreter.of(x, x)`, never a missing argument.
 * @param appLanguage the app language, which is what `top` is in.
 * @param studyLanguage the language the reading aid's gloss is written in, or `null` when the caller
 *     has none: it is what tells `GlossText.words` that a word already in the study language is not
 *     worth glossing. It never decides whether the aid exists - that is [interpreter], and off answers
 *     no words whatever this holds.
 * @param conversationLanguage the conversation's own detected-or-overridden language, or `null` when
 *     nobody has read one: it decides where the English row sits, and nothing is invented for it.
 * @param conversationName the name the interface shows for this conversation - the same name the list row
 *     draws - which is what the bare-name rule is asked about; `null` when the caller has none.
 * @param mergeWindow `Config.MESSAGE_MERGE_WINDOW`, in milliseconds: the run rule's own bound, an island
 *     constant that arrives from a file that may name it.
 * @param group whether this conversation is a room, which the run rule and the display name both ask.
 * @param forceNames the owner's "always show names" setting.
 */
data class ConversationFacts(
    val interpreter: Interpreter,
    val appLanguage: String,
    /** The language a gloss is written in, or `null` when the caller has none (`GlossText.words`). */
    val studyLanguage: String?,
    val conversationLanguage: String?,
    val conversationName: String?,
    val mergeWindow: Long,
    val group: Boolean,
    val forceNames: Boolean,
)
