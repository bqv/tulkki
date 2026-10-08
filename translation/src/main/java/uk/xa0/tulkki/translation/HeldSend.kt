package uk.xa0.tulkki.translation

import java.util.Locale
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message

/**
 * The held send: what an outgoing message needs before it may leave the device, and what to say when
 * it cannot.
 *
 * <p>Nothing is sent untranslated. A message that <em>needs</em> translating is held until its
 * translation succeeds; if the translation cannot happen - no key, no credit, the cap reached, the
 * API unreachable, the conversation's language unknown - it does not send, and the composer says
 * which of those it is. Only a message that needs translating is held, so a conversation that already
 * speaks the app language sends normally, offline included, and so does a link or a number.
 *
 * <p>The row is upstream's own outgoing row, not a parallel store:
 *
 * <ul>
 *   <li>while the message is held, `body` is the draft the owner wrote (in the app language)
 *       and `translation_state` is `NONE` or `FAILED` - nothing was bought;
 *   <li>when the translation lands it is <em>swapped</em>: `body` becomes the text that goes on
 *       the wire, `translated_body` becomes the owner's draft, `translation_lang` is the
 *       language it was translated into and `translation_state` is `DONE`. Everything
 *       that sends - plain, OTR, PGP, OMEMO - reads `body`, so the swap is what makes them all
 *       send the translation, and it is written to the database <em>before</em> the send, so a kill
 *       in between cannot lose it.
 *   <li>the stored pair is also what the interface reads: the owner keeps seeing their own text
 *       (which is why the swap is invisible in the bubble) while the wire carries the translation.
 * </ul>
 *
 * <p>A translation that already succeeded is never bought again: [mayReuse] is checked before
 * the request, so a flaky network or a reconnect costs nothing.
 *
 * <p>The one outbound path with no composer in front of it is the notification's quick reply, and the
 * composer's refusal cannot happen there because there is no screen to show a prompt on.
 * [decide] is the rule for that too: a quick reply in another language is refused, and its words go
 * into the conversation's draft rather than onto the wire ([Action.DRAFT_NOT_SENT],
 * [mergeDraft]). Everything else the pipeline carries - a share, a retry, a reconnect -
 * answers [Source.ELSEWHERE] and keeps the meaning it always had.
 *
 * <p>All of that is inside the interpreter. With it off, [decide] answers [Action.SEND]
 * for every draft and every source before the gate is asked, and [needsTranslation] is
 * therefore false: a plain XMPP client holds nothing, so a draft in another language goes out as
 * typed and a held row already in the database is released by the next re-offer rather than deleted
 * (MIGRATION.md "Design: the interpreter off-switch" §2.4).
 *
 * <p>Pure Kotlin apart from compile-time `Message` constants and the conversation's own message
 * list ([alreadyHeld]), so it is exercised by JVM unit tests without a device.
 *
 * <p>The nested `HoldReason`, `Source` and `Action` enums stay nested, and every static member stays
 * `@JvmStatic`, because the Java callers (`TranslationActivityPort`, `FailureCause`, the `:app` host
 * and the `:ui` projections) name them by their Java spellings.
 */
object HeldSend {

    /** Why an outgoing message is not going anywhere. */
    enum class HoldReason {
        /** The conversation has no language yet, so there is nothing to translate into. */
        UNKNOWN_LANGUAGE,
        /** No DeepSeek key is configured. */
        NO_KEY,
        /** The day's token cap is used up. */
        CAP_REACHED,
        /** The DeepSeek account has no balance left. */
        NO_CREDIT,
        /** DeepSeek could not be reached; worth trying again later. */
        UNREACHABLE,
        /** DeepSeek refused to authorise the key itself, so trying again will not help. */
        REJECTED_KEY,
        /** DeepSeek answered, and the answer was not usable. */
        FAILED,
        /**
         * The answer was accepted only on doubt, so the send waits for the owner's tap
         * (docs/MIGRATION.md item 16).
         *
         * <p>Not [FAILED]: nothing failed. An answer came back and the local check accepted it
         * - it is the input handed back under `MIN_ECHO_LENGTH`, or one no reader could vouch
         * for - which is why the composer bar may not say "DeepSeek could not translate this
         * message". It is also not a [FailureCause]: `FailureCause.fromReason` answers
         * `null` for it exactly as it does for [FAILED], so no received row's automatic
         * retry may claim it, and [failureReason] never produces it because it is not a failed
         * call.
         */
        DOUBT
    }

    /**
     * Whether this message is already in its conversation's list.
     *
     * <p>True for every held message, because `OutgoingTranslation.hold` writes the row into
     * the conversation - and into the database - before the translation is bought; that is what makes
     * a held message visible straight away and what makes it survive the process being killed.
     * Handing it back to the send path therefore must not insert it a second time: upstream's
     * ordinary send adds the message it is given and creates a database row for it, so a second
     * hand-off of the same row is how one message becomes two entries in the conversation's list:
     * <strong>two bubbles, one database row</strong>.
     *
     * <p>The stored row cannot be duplicated this way (`uuid` is the messages table's primary
     * key, so the second insert is a silent no-op), which is why the duplicate is a model one and not
     * a stored one. The send path asks this anyway, because the insertion it is about to skip is the
     * insertion of the <em>list</em> entry - and the one that draws the second bubble.
     *
     * <p>An edit always answers no, even though its uuid is in the conversation: it is there so that
     * the send <em>updates</em> that row instead of adding one, and answering yes would route it past
     * that update. Everything else that is already in the list answers yes, which is also what a
     * retry of an unsent message needs: the retry must still send the stanza - it is built before this
     * is asked - but it must mark the row it already has rather than add a second one.
     */
    @JvmStatic
    fun alreadyHeld(conversation: Conversation?, message: Message?): Boolean {
        if (conversation == null || message == null || message.edited()) {
            return false
        }
        // `getUuid()` is nullable and `findMessageWithUuid` takes a non-null uuid; the Java this
        // replaces passed the platform type straight in, so the name is required, not guessed.
        val uuid =
                message.getUuid()
                        ?: throw NullPointerException("HeldSend.alreadyHeld: message has no uuid")
        return conversation.findMessageWithUuid(uuid) != null
    }

    /**
     * Where an outgoing draft came from. This is the one fact that changes what a refused draft
     * <em>means</em>, and it is the whole reason the quick reply can be gated without touching a
     * share.
     */
    enum class Source {
        /**
         * The notification's quick reply - upstream's `RemoteInput`: the owner's own words,
         * typed on the notification and handed straight to the send path with no composer in front of
         * them to refuse them. This is the one way a foreign draft reaches the wire, so it is the one
         * source a refusal turns into a draft.
         */
        QUICK_REPLY,
        /**
         * Everywhere else the pipeline is reached without the composer's own gate: a share (which
         * upstream routes through the composer anyway), a retry or a reconnect re-offering an
         * existing row, an edit, an attachment caption. None of those is the owner mid-sentence at a
         * screen they can fix, so today's behaviour stands for them and this decision never refuses
         * one - the pure function says [Action.SEND], and only a real caller may change that.
         */
        ELSEWHERE
    }

    /**
     * What the send path may do with a draft. Note the answer that is deliberately not here:
     * <em>translate it</em>. The quick reply has no composer to hold anything, and a message that
     * needs a translation is still held, never bought by whoever happens to re-offer it.
     */
    enum class Action {
        /** Send it as it stands: no language in it, or the conversation already speaks the app language. */
        SEND,
        /** Hold it: the normal send path translates the draft into the conversation's language. */
        TRANSLATE,
        /** Refuse it: the owner's own foreign draft, put into the conversation's draft instead of sent. */
        DRAFT_NOT_SENT
    }

    /**
     * The whole outbound decision as a value, from four strings, the one fact that is not in the text
     * - where it came from - and the interpreter. No Android types, no state, so it is exercised by
     * JVM unit tests.
     *
     * <p>It is the composer's own verdict ([ComposerGate.verdict]) folded onto three
     * answers, and the fold is the entire rule:
     *
     * <ul>
     *   <li>the gate says the draft has no language, or the conversation already speaks the app
     *       language: [Action.SEND] - offline included, exactly as it always was;
     *   <li>the gate says it needs a translation: [Action.TRANSLATE] - the held send;
     *   <li>the gate says it is confidently a language other than the app language, and the source is
     *       the quick reply: [Action.DRAFT_NOT_SENT]. The composer turns that same verdict into
     *       a prompt; the quick reply has no composer, so the words go to the conversation's draft
     *       and the send is refused. For every other source the verdict keeps the meaning it had
     *       before - "not app language" is not "needs translating", so the message goes out as typed -
     *       which is what leaves shares and upstream's own resends untouched.
     * </ul>
     *
     * <p>With the interpreter off the answer is [Action.SEND] for every draft and every source,
     * before the gate is asked: a plain XMPP client sends what was typed, including a quick reply in
     * another language, and nothing is moved into the conversation's draft
     * (MIGRATION.md "Design: the interpreter off-switch" §2.4).
     *
     * <p>A ping, a link, a number and the conversation's own bare name all reach the first case and
     * send, because [TranslationDecision.hasLanguage] is what the gate asks first - the same
     * rule the receive path blurs by. The bare name needs `conversationName` to be the name the
     * interface shows; with `null` it cannot be recognised.
     *
     * @param conversationName the name the interface shows for the conversation the draft is going to
     *     ([ConversationName]), or `null` when the caller has none
     * @param source where this draft came from - the quick reply is the only source a refusal
     *     refuses
     * @param interpreter whether Tulkki is interpreting at all, required and last so a call site
     *     cannot compile without deciding
     */
    @JvmStatic
    fun decide(
            draft: String?,
            appLanguage: String?,
            conversationLanguage: String?,
            conversationName: String?,
            source: Source,
            interpreter: Interpreter
    ): Action {
        if (!interpreter.enabled()) {
            return Action.SEND
        }
        return when (ComposerGate.verdict(
                draft, appLanguage, conversationLanguage, conversationName, interpreter)) {
            ComposerGate.Verdict.TRANSLATE, ComposerGate.Verdict.UNKNOWN_LANGUAGE -> Action.TRANSLATE
            ComposerGate.Verdict.NOT_APP_LANGUAGE ->
                    if (source == Source.QUICK_REPLY) Action.DRAFT_NOT_SENT else Action.SEND
            ComposerGate.Verdict.SEND -> Action.SEND
            else -> Action.SEND
        }
    }

    /**
     * Whether `draft` has to be translated into `conversationLanguage` before it may go
     * out. This is the gate's own verdict rather than a second rule: text that is refused is not
     * held, it is a prompt, and everything else the gate says needs a translation is held.
     *
     * <p>Spelled as the send path's own [Source.ELSEWHERE] case of [decide], so the two
     * can never disagree: a refusal is never "needs translating" on this path either - and with the
     * interpreter off neither is anything else, because a plain client holds nothing.
     *
     * @param conversationName the name the interface shows for the conversation the draft is going
     *     to ([ConversationName]), or `null` when the caller has none - in which case a
     *     draft that is the room's own name is held like any other text
     * @param interpreter whether Tulkki is interpreting at all, required and last
     */
    @JvmStatic
    fun needsTranslation(
            draft: String?,
            appLanguage: String?,
            conversationLanguage: String?,
            conversationName: String?,
            interpreter: Interpreter
    ): Boolean {
        val action =
                decide(
                        draft,
                        appLanguage,
                        conversationLanguage,
                        conversationName,
                        Source.ELSEWHERE,
                        interpreter)
        return action == Action.TRANSLATE
    }

    /**
     * The conversation's composer draft with a refused quick reply added to it, so that neither the
     * words the owner typed on the notification nor a draft already waiting in the composer is lost.
     * A blank line separates the two: they are two things the owner wrote at two different times, and
     * running them together would read as one sentence.
     *
     * <p>Pure, for the same reason [decide] is: the "their text must not simply vanish" half of
     * the rule is testable even though the notification plumbing around it is not. `trim()` is Java's
     * own (`<= ' '`), as in the Java this replaces.
     */
    @JvmStatic
    fun mergeDraft(existing: String?, refused: String?): String {
        if (refused == null || refused.javaTrim().isEmpty()) {
            return existing ?: ""
        }
        if (existing == null || existing.javaTrim().isEmpty()) {
            return refused
        }
        return existing + "\n\n" + refused
    }

    /**
     * The owner's own text. While the message is held that is the body; once the translation has been
     * swapped in it is the stored translation, because the body is then the wire text.
     */
    @JvmStatic
    fun draftOf(body: String?, translationState: Int, translatedBody: String?): String? {
        if (translationState == Message.TRANSLATION_DONE &&
                translatedBody != null &&
                !translatedBody.javaTrim().isEmpty()) {
            return translatedBody
        }
        return body
    }

    /**
     * Whether this persisted row is a <strong>send failure</strong>: the owner's retry was tried,
     * nothing was sent, and the row now says so rather than resting as an ambiguous hold.
     *
     * <p>The state is the row's own, in two columns upstream and Tulkki already write and already
     * persist: [Message.STATUS_SEND_FAILED], which is upstream's "this did not leave the
     * device" and what the conversation draws as a failure, and
     * [Message.TRANSLATION_FAILED], which is this app's "the attempt produced no usable
     * answer". Both survive a restart because both are columns of the message row, which is why the
     * resting bar can find a row it never saw fail.
     *
     * <p>It is deliberately not [HoldReason]: a reason is why one attempt failed, and this is
     * the state the row is in. The reason that belongs to the row is kept beside it
     * (`TranslationSettings.sendFailure`).
     *
     * <p>Pure, so the classification is pinned without a device.
     */
    @JvmStatic
    fun isSendFailure(status: Int, translationState: Int): Boolean =
            status == Message.STATUS_SEND_FAILED &&
                    translationState == Message.TRANSLATION_FAILED

    /**
     * Whether a translation this message already has may be sent instead of buying a new one. The
     * target has to match: if the conversation's language changed since, the stored translation is
     * for the wrong language and reusing it would send the wrong thing.
     */
    @JvmStatic
    fun mayReuse(
            translatedBody: String?,
            translationState: Int,
            storedTarget: String?,
            conversationLanguage: String?
    ): Boolean =
            translationState == Message.TRANSLATION_DONE &&
                    translatedBody != null &&
                    !translatedBody.javaTrim().isEmpty() &&
                    !ComposerGate.isUnknownLanguage(storedTarget) &&
                    ComposerGate.normalize(storedTarget)
                            .equals(
                                    ComposerGate.normalize(conversationLanguage),
                                    ignoreCase = true)

    /**
     * Whether this row has already been through the hold decision and needs nothing more: it either
     * carries a translation for this very language, or it was found to need none.
     *
     * <p>This is the cheap check - no detection, no request - for callers that must decide on the
     * spot, such as the send path itself.
     */
    @JvmStatic
    fun alreadyDecided(
            translatedBody: String?,
            translationState: Int,
            storedTarget: String?,
            conversationLanguage: String?
    ): Boolean {
        if (mayReuse(translatedBody, translationState, storedTarget, conversationLanguage)) {
            return true
        }
        return translationState == Message.TRANSLATION_SAME_LANGUAGE &&
                ComposerGate.normalize(storedTarget)
                        .equals(
                                ComposerGate.normalize(conversationLanguage),
                                ignoreCase = true)
    }

    /**
     * The reason that is knowable without asking anyone, or `null` when the request may be
     * attempted. The order matters: there is no point naming a language problem to someone who has
     * not configured a key at all, and no point naming the cap while the target is still unknown.
     */
    @JvmStatic
    fun localReason(
            hasApiKey: Boolean,
            capReached: Boolean,
            conversationLanguage: String?
    ): HoldReason? {
        if (!hasApiKey) {
            return HoldReason.NO_KEY
        }
        if (ComposerGate.isUnknownLanguage(conversationLanguage)) {
            return HoldReason.UNKNOWN_LANGUAGE
        }
        if (capReached) {
            return HoldReason.CAP_REACHED
        }
        return null
    }

    /**
     * What a failed call means to the owner. A retryable failure is the network; a rejected balance
     * or a rejected key is not retryable and never will be, so it must not look like one.
     *
     * <p>An exception is never doubt: this fallback is [FAILED] and never [DOUBT],
     * which is reserved for an answer the check accepted on doubt - a call that returned is not a
     * call that failed.
     */
    @JvmStatic
    fun failureReason(retryable: Boolean, message: String?): HoldReason {
        if (retryable) {
            return HoldReason.UNREACHABLE
        }
        if (message != null && message.lowercase(Locale.ROOT).contains("insufficient balance")) {
            return HoldReason.NO_CREDIT
        }
        if (rejectedKey(message)) {
            return HoldReason.REJECTED_KEY
        }
        return HoldReason.FAILED
    }

    /**
     * A reason's stored form - the enum's own name, so the format cannot drift from the type. The
     * per-message send-failure record (`TranslationSettings.sendFailure`) outlives the
     * process, and this is what it writes.
     */
    @JvmStatic
    fun reasonName(reason: HoldReason?): String = reason?.name ?: ""

    /** The reason a stored value names, or `null` when there is none: absent, blank or unknown. */
    @JvmStatic
    fun parseReason(stored: String?): HoldReason? {
        if (stored == null) {
            return null
        }
        // Java's own `trim()` (`<= ' '`), as in the Java this replaces.
        val wanted = stored.javaTrim()
        for (reason in HoldReason.values()) {
            if (reason.name == wanted) {
                return reason
            }
        }
        return null
    }

    /**
     * Whether DeepSeek's own words say the request was not authorised. The status code is in the
     * message [DeepSeekClient] builds ("deepseek returned 401: ..."), and 401 and 403 are the
     * two answers that mean the key itself is the problem.
     */
    private fun rejectedKey(message: String?): Boolean {
        if (message == null) {
            return false
        }
        val lower = message.lowercase(Locale.ROOT)
        return lower.contains("returned 401") || lower.contains("returned 403")
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
