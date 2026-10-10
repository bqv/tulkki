package uk.xa0.tulkki.translation

import android.content.Context
import android.util.Log
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message

/**
 * The send path's half of Tulkki: holding an outgoing message until it is translated, and sending it
 * once it is.
 *
 * The owner's own sends go through [submit], or through [sendHeldNow] when the owner
 * taps an unsent message that is still waiting, and there the decision is made off the main thread:
 *
 * - it needs no translation (text with no language, or a room that already speaks the app
 *   language): it is marked as such and sent straight away, offline included;
 * - it needs one and already has it: it is sent, and nothing is bought again;
 * - it needs one: it is translated, swapped and sent;
 * - it cannot be translated: it stays in the conversation, unsent, and the composer says why.
 *
 * When upstream sends one on its own - a share, a retry and above all the reconnect, which
 * re-offers every queued `STATUS_WAITING` row to the send path - it passes [holdBack],
 * the one choke point all of them share. That path refuses and never buys: a row that still needs a
 * translation is held and left waiting. Nothing re-attempts it, so a socket coming back, time passing,
 * the conversation opening again or the cap being raised cannot spend a token or put a message on the
 * wire; the retry is the owner's, and it is a tap on the unsent message's own bubble -
 * [isHeldForTranslation] is the question that tap asks.
 *
 * The one retry that is not the owner's happens *inside* the decision the owner already made: a
 * request that failed for a reason a retry could fix is asked again after a couple of seconds
 * ([SendRetry], [requestForSendWithRetry]), because a phone's radio dropping a socket for a moment is
 * not an answer and must not become a failure the owner has to notice and undo. It waits seconds, it
 * starts nothing on its own, and everything above still holds: `holdBack` buys nothing, and a row that
 * ends up held waits for the owner's tap.
 *
 * The notification's quick reply is the exception to that sentence, and it is the reason
 * [refuseQuickReply] exists. It is the owner's own prose reaching the send path with no
 * composer in front of it, and `holdBack` cannot refuse it - a draft confidently in another
 * language reads as "needs no translation", which would send it as typed. So the quick reply is
 * stopped before a message exists and its words are left in the conversation's draft, where the
 * composer's gate takes over. A share is not in that position: upstream opens the conversation and
 * puts the shared text into the composer, so it never reaches this class without the gate having had
 * its say.
 *
 * **Every one of these entry points is handed a `Context`, not a service.**
 * They used to take `uk.xa0.tulkki.xmpp.services.XmppConnectionService`, which is an island type this
 * module may not name. Every caller - the island's send path, the notification's quick reply, and
 * `:ui`'s four call sites, which pass `activity.xmppConnectionService` - is handing over
 * something that already *is* a `Context`, so the widening changed no caller; what the
 * engine needs from that Context beyond the settings and the store it comes from the running service
 * reaches through [EngineHost], resolved from the Context itself.
 *
 * With the interpreter off none of this holds anything: [holdBack] refuses nothing on its
 * first line, [submit] hands the message straight to the caller's send path, [refuseQuickReply] and
 * [suggest] answer before reading anything, and [sendVerdict]
 * never answers `HOLD` - so a row already in the database at `STATUS_WAITING` is
 * released as the owner typed it by the next re-offer or retry, never deleted and never bought for.
 * Nothing re-offers on its own at the moment of the switch, so that release is event-driven
 * (MIGRATION.md "Design: the interpreter off-switch" §2.4).
 *
 * A held message is upstream's own unsent outgoing row - in the conversation immediately, in the
 * database so it survives the process being killed - which is documented in `HeldSend`,
 * together with the swap that keeps the interface showing the owner's own words while the wire
 * carries the translation.
 */
class OutgoingTranslation private constructor() {

    /** Where "not sent, and why" goes. Called on the main thread. */
    interface Listener {
        fun onHeld(reason: HeldSend.HoldReason?)
    }

    /**
     * How the message leaves once it is ready. The interface passes one so that its own encryption
     * path (plain, OTR, PGP) still runs, now on translated text; without one the message is handed
     * straight to the service, which is what upstream's own sends do.
     */
    interface Sender {
        fun send(message: Message)
    }

    /** Where the composer's suggestion goes. Called on the main thread. */
    interface Suggestions {
        fun onSuggestion(text: String?)

        /**
         * The model's app-language version of the draft is the draft itself, so the verdict that
         * refused it was wrong and the gate stands down: the message sends as the owner wrote it.
         * Defaulted so an implementation that does not act on it still compiles.
         */
        fun onConfirmsDraft() {}

        fun onUnavailable(reason: HeldSend.HoldReason?)
    }

    /** What the send path does with a row. Note the answer that is deliberately not here: buy one. */
    enum class SendVerdict {
        /** Send it: the translation is there for this language, or the row was found to need none. */
        SEND,
        /** Send it, and remember that nothing needed translating, so the question is not asked again. */
        SEND_AND_MARK,
        /** Hold it. The owner's tap is the only thing that may change this. */
        HOLD
    }

    companion object {

        private const val TAG = "Tulkki"

        /**
         * The tag of a diagnostic attempt line: the app's own tag, lowercase, so
         * `adb logcat -s tulkki` finds every attempt. The line carries lengths and language codes
         * and never a word of any message, which is why it can be written at all.
         */
        private const val ATTEMPT_TAG = "tulkki"

        private val HTTP: OkHttpClient = DeepSeekClient.defaultHttp()

        /**
         * The outgoing work's own scope: a few at a time, because one of them may be waiting on
         * DeepSeek for a minute while another message only needs the local check. The work is per
         * message and deduplicated, so there is no ordering to preserve.
         *
         * This was a hand-rolled fixed pool of four daemon threads. The pool is [Dispatchers.IO]
         * narrowed to the same four ([limitedParallelism]), the scope is the process's, and a
         * supervisor keeps one failed decision from cancelling the rest. The bound is kept rather
         * than widened on purpose: an outgoing decision can hold an API call open, so four was the
         * deliberate ceiling on how many go out at once.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val OUTGOING: CoroutineScope =
                CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))

        /** One decision per message at a time, however many callers ask for one. */
        private val IN_FLIGHT: MutableSet<String> =
                Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        @Volatile private var warmedUp = false

        /**
         * Writes one attempt into the in-memory record and one greppable line to logcat.
         *
         * The record is reached through the engine's own port ([EngineHost.activity]), the
         * handle the app already holds, so a surface can read the same facts without new wiring. The
         * line and the record are the same values, and neither can carry the message text.
         */
        private fun remember(host: EngineHost, attempt: OutgoingAttempts.Attempt) {
            host.activity().recordOutgoingAttempt(attempt)
            Log.d(ATTEMPT_TAG, attempt.logLine())
        }

        /** Which question an attempt belongs to, as the diagnostic record names it. */
        private fun requestKind(reAsk: Boolean): OutgoingAttempts.Request =
                if (reAsk) OutgoingAttempts.Request.RE_ASK else OutgoingAttempts.Request.NORMAL

        /** The language to translate into: the owner's override, else what the room was seen writing. */
        @JvmStatic
        fun languageOf(conversation: Conversation?): String? {
            if (conversation == null) {
                return null
            }
            val languageOverride = conversation.getLanguageOverride()
            if (languageOverride != null && languageOverride.trim().isNotEmpty()) {
                return languageOverride.trim()
            }
            val detected = conversation.getDetectedLanguage()
            return if (detected == null || detected.trim().isEmpty()) null else detected.trim()
        }

        /** The gate's verdict for this draft, with the app's own settings filled in. */
        @JvmStatic
        fun verdict(
                context: Context,
                conversation: Conversation?,
                draft: String?
        ): ComposerGate.Verdict {
            val settings = TranslationSettings.get(context)
            return ComposerGate.verdict(
                    draft,
                    settings.appLanguage(),
                    languageOf(conversation),
                    // A draft that is exactly the room's own name is a bare name, not prose: it must not
                    // be held, and the name the interface shows is the only thing it can be compared to.
                    ConversationName.of(conversation),
                    settings.interpreter())
        }

        /**
         * Builds the language detector and opens the settings away from the main thread. Both are far
         * too slow for the first tap on the send button.
         *
         * With the interpreter off there is nothing to warm: no detector is going to be asked, so the
         * work is skipped rather than done for a mode that will not use it. The warm-up is not marked as
         * done, so turning the interpreter on still warms it on the next call.
         */
        @JvmStatic
        fun warmUp(context: Context?) {
            if (warmedUp
                    || context == null
                    || !TranslationSettings.get(context).interpreter().enabled()) {
                return
            }
            warmedUp = true
            OUTGOING.launch {
                TranslationSettings.get(context)
                TextLanguage.detect("warm up the language detector")
            }
        }

        /**
         * The notification's quick reply: the owner's own words, typed on the notification, handed
         * straight to the send path with no composer in front of them. Returns true when the reply was
         * refused and its text was put into the conversation's draft instead, in which case the caller
         * must not send anything.
         *
         * This is the one hole in "writing in anything but the app language does not send". Every other
         * way into the send pipeline either passes the composer - which runs the same gate on its own -
         * or is not the owner mid-sentence: a share is routed through the composer by upstream, a retry
         * and a reconnect re-offer a row that already exists, and an attachment caption is not held at
         * all. The quick reply is the one place where the owner's own prose reaches `holdBack`
         * without a screen to refuse it, and `holdBack` itself cannot refuse it: a refusal means
         * "not app language", which is not "needs translating", so it would send it as typed.
         *
         * It runs before the message is built, so a refused quick reply creates no [Message] at
         * all - no unsent bubble, no database row, no translation state, nothing for a reconnect to
         * re-offer and nothing to buy a translation for. The words exist only as the conversation's draft,
         * which is exactly the state the composer's own gate leaves behind, so the two mechanisms cannot
         * fight: there is never a held bubble *and* a draft for the same text, and the translation
         * of a refused draft is never bought twice because it was never bought once.
         *
         * Detection is local and free, and this runs on the notification executor rather than the main
         * thread, so the refusal costs nothing and blocks no UI.
         *
         * With the interpreter off there is nothing to refuse: the answer is false on the first line
         * after the null check, so the reply sends as the owner typed it and nothing is moved into the
         * conversation's draft - exactly what a plain XMPP client does
         * (MIGRATION.md "Design: the interpreter off-switch" §2.4).
         */
        @JvmStatic
        fun refuseQuickReply(
                context: Context?,
                conversation: Conversation?,
                body: String?
        ): Boolean {
            if (context == null || conversation == null || body == null) {
                return false
            }
            val settings = TranslationSettings.get(context)
            if (!settings.interpreter().enabled()) {
                return false
            }
            val action =
                    HeldSend.decide(
                            body,
                            settings.appLanguage(),
                            languageOf(conversation),
                            ConversationName.of(conversation),
                            HeldSend.Source.QUICK_REPLY,
                            settings.interpreter())
            if (action != HeldSend.Action.DRAFT_NOT_SENT) {
                return false
            }
            keepAsDraft(EngineHost.of(context), conversation, body)
            Log.d(
                    TAG,
                    "refused a quick reply written in another language; it is waiting in the composer's"
                            + " draft and was not sent")
            return true
        }

        /**
         * Puts refused words into the conversation's next-message draft - the mechanism upstream already
         * has for text waiting in the composer, and the one `ConversationFragment` reads when the
         * conversation opens. Nothing new is invented: `setNextMessage` is the same call the
         * composer makes, and the conversation is written to the database so the words survive the
         * process being killed.
         *
         * An existing draft is kept rather than overwritten (`HeldSend.mergeDraft`): the owner
         * may have been part-way through something, and "the words must not vanish" is not a rule that
         * only applies to the newest ones.
         *
         * The notification is deliberately left exactly as it was: nothing was sent, the incoming
         * message is still unanswered, so its notification stands and the owner finds the words in the
         * composer when they open the conversation. No new notification is posted and none is dismissed,
         * which is the owner's chosen option - their text is not on the wire, and the refusal is not
         * announced as a send.
         */
        private fun keepAsDraft(host: EngineHost, conversation: Conversation, body: String) {
            conversation.setNextMessage(HeldSend.mergeDraft(conversation.getNextMessage(), body))
            host.updateConversation(conversation)
            host.updateConversationUi()
        }

        /**
         * The composer's way in: hold this message and let the lane decide what it needs. Nothing is
         * sent from here, so the caller's own send must not happen.
         *
         * With the interpreter off there is no lane to hold anything and nothing may be bought, so the
         * guard hands the message straight to the caller's own send path, as the owner typed it. It is
         * deliberately not a silent return: a plain client sends, and a message dropped here would
         * vanish.
         */
        @JvmStatic
        fun submit(context: Context?, message: Message?, listener: Listener?, sender: Sender?) {
            if (context == null || message == null) {
                return
            }
            if (!TranslationSettings.get(context).interpreter().enabled()) {
                handOver(EngineHost.of(context), message, sender)
                return
            }
            // The owner's own send is the ordinary question: never a re-ask, or the owner's template
            // would stop being the instruction on the normal path.
            schedule(context, message, listener, sender, true, false)
        }

        /**
         * The choke point upstream's own sends pass through. Returns true when the message was held back
         * and must not be sent by the caller; false when it may go out as it stands.
         *
         * This is the reconnect's way in, and it **refuses but never buys**: nothing here
         * calls the API, so a socket coming back, a conversation opening again or the cap being raised
         * cannot translate-and-send a held message on its own initiative. The retry is the owner's, by way
         * of [sendHeldNow] - a tap on the unsent message's own bubble - and this method is what
         * makes that safe: it is the only thing standing between upstream's re-offer and the wire.
         *
         * What it does instead of buying is exactly two things, in this order:
         *
         * - a row whose translation already succeeded for this language - or which was already found
         *   to need none - goes out; that is the `alreadyDecided` check, and it is what keeps a
         *   retry free and what lets a message whose send failed after the swap still leave;
         * - anything else that *needs* no translation is marked as such and goes out too. That
         *   question is local and free (the offline detector, the app language, the conversation's own
         *   language), and answering it here is what keeps the rule honest in the other direction:
         *   only a message that needs translating is held, so a quick reply into a room that already
         *   speaks the app language still sends straight away;
         * - a row that does need one is held - written into the conversation as upstream's own unsent
         *   message and left at `STATUS_WAITING` - and the send is refused.
         *
         * The one length of the decision runs on the caller's thread, which upstream's send path
         * already is; it is the same offline work `submit` does, and it is not a request.
         *
         * With the interpreter off this answers false on the first line, before anything is read: a
         * plain XMPP client does not hold anything, so a row already in the database at
         * `STATUS_WAITING` is *released as the owner typed it* by the next re-offer or retry
         * rather than deleted. Nothing re-offers by itself at the moment the switch is turned, so the
         * release is event-driven - which is the same shape as "the retry is the owner's"
         * (MIGRATION.md "Design: the interpreter off-switch" §2.4).
         */
        @JvmStatic
        fun holdBack(context: Context?, message: Message?): Boolean {
            if (context == null || !TranslationSettings.get(context).interpreter().enabled()) {
                return false
            }
            if (message == null) {
                return false
            }
            val conversation = asConversation(message) ?: return false
            if (!isHoldableOutgoingText(message)) {
                return false
            }
            val host = EngineHost.of(context)
            val settings = TranslationSettings.get(context)
            val target = languageOf(conversation)
            val composed = ReplyFallback.composedBody(message, host)
            return when (sendVerdict(
                    composed.translatable(),
                    message.getTranslationState(),
                    message.getTranslatedBody(),
                    message.getTranslationLang(),
                    settings.appLanguage(),
                    target,
                    ConversationName.of(conversation),
                    settings.interpreter())) {
                SendVerdict.SEND -> {
                    // Already translated for this language, or already found to need none: it goes out,
                    // and nothing is bought again.
                    false
                }
                SendVerdict.SEND_AND_MARK -> {
                    // It never needed one - a link, a ping, the room's own bare name, or a room that
                    // already speaks the app language. Marked so the question is asked once, then sent.
                    markNeedsNoTranslation(host, message, target)
                    false
                }
                SendVerdict.HOLD -> {
                    // Held. Nothing is bought here on purpose: the owner's tap is the retry.
                    hold(context, message)
                    Log.d(
                            TAG,
                            "held an outgoing message that has no translation yet; the retry is the owner's")
                    true
                }
            }
        }

        /**
         * The send path's whole decision, as a value: no service, no request, no Android, and - the point -
         * no answer that buys anything. This is what a reconnect, a conversation coming back and a raised
         * cap are allowed to ask, and the strictest thing any of them can reach is
         * [SendVerdict.HOLD].
         *
         * The owner's own text is a parameter rather than a read of `message.getBody()` for the
         * reason `TranslationHooks.candidate` gives: upstream's `getBody` goes through
         * `android.util.Pair`, which the unit-test runtime stubs out, and a decision the test cannot
         * call is a decision the test cannot pin. A production caller passes
         * `ReplyFallback.composedBody(message, host).translatable()`, which is the owner's own text
         * and never the quote a reply carries: a quote is somebody else's message, it stays on the wire
         * verbatim, and it is never billed for a second time.
         *
         * @param draft the owner's own text, the reply's quote already removed
         * @param storedTarget the language the stored translation is for (`translation_lang`)
         * @param conversationLanguage the send target: the conversation's own language, or the owner's
         *     override for it
         * @param conversationName the name the interface shows for the conversation, so that a body which
         *     is the room's own bare name is a name rather than prose; may be `null`
         * @param interpreter whether Tulkki is interpreting at all, required and last
         */
        @JvmStatic
        fun sendVerdict(
                draft: String?,
                translationState: Int,
                translatedBody: String?,
                storedTarget: String?,
                appLanguage: String?,
                conversationLanguage: String?,
                conversationName: String?,
                interpreter: Interpreter
        ): SendVerdict {
            if (!interpreter.enabled()) {
                // Off: every row is sent as the owner typed it. SEND and not SEND_AND_MARK on purpose -
                // marking a row as needing no translation is state written for a rule that is not
                // running, and a row already STATUS_WAITING in the database is released rather than
                // deleted by the next re-offer or retry (MIGRATION.md "Design: the interpreter
                // off-switch" §2.4).
                return SendVerdict.SEND
            }
            if (HeldSend.alreadyDecided(
                    translatedBody, translationState, storedTarget, conversationLanguage)) {
                return SendVerdict.SEND
            }
            if (!HeldSend.needsTranslation(
                    HeldSend.draftOf(draft, translationState, translatedBody),
                    appLanguage,
                    conversationLanguage,
                    conversationName,
                    interpreter)) {
                return SendVerdict.SEND_AND_MARK
            }
            return SendVerdict.HOLD
        }

        /**
         * Whether this row is an unsent message Tulkki is holding for its translation - the one state in
         * which a tap on a message of the owner's own means "translate this one now and send it".
         *
         * The unsent status is the load-bearing half: it is upstream's and Tulkki's shared "in the
         * conversation, not on the wire yet", it is what [hold] writes and what survives the
         * process being killed, and it is what keeps the tap off every message the owner has already sent
         * - a sent message with no stored translation is history, and a stray tap on it must not re-send
         * it. A row that already has its translation is not waiting on anything either: upstream's own
         * re-offer is what sends that one, and it costs nothing.
         *
         * With the interpreter off this is false for every row, because [sendVerdict] never
         * answers [SendVerdict.HOLD]: there is nothing waiting on a translation, so no tap is
         * offered and a held row is released as typed.
         *
         * Pure, for the same reason [sendVerdict] is: the status comes in as a parameter and the
         * body as text rather than through `getBody()`.
         */
        @JvmStatic
        fun isHeldForTranslation(
                status: Int,
                draft: String?,
                translationState: Int,
                translatedBody: String?,
                storedTarget: String?,
                appLanguage: String?,
                conversationLanguage: String?,
                conversationName: String?,
                interpreter: Interpreter
        ): Boolean {
            val verdict =
                    sendVerdict(
                            draft,
                            translationState,
                            translatedBody,
                            storedTarget,
                            appLanguage,
                            conversationLanguage,
                            conversationName,
                            interpreter)
            return status == Message.STATUS_WAITING && verdict == SendVerdict.HOLD
        }

        /**
         * The same question about a real message, for the bubble that has to draw the tap: Tulkki is
         * holding this row, so a tap on it translates it now and sends it.
         *
         * This is the one entry point with no `Context` to hand - the bubble has a message, the
         * app language and the interpreter and nothing else - so the reply span comes from the process's
         * installed host ([EngineHost.installed]): there is one per process, and the bubble only
         * exists inside it. The interpreter is an argument for the same reason it is everywhere else:
         * the bubble's caller resolves it once and this rule never reads the settings itself.
         */
        @JvmStatic
        fun isHeldForTranslation(
                message: Message?,
                appLanguage: String?,
                interpreter: Interpreter
        ): Boolean {
            if (message == null) {
                return false
            }
            val conversation = asConversation(message) ?: return false
            if (!isHoldableOutgoingText(message)) {
                return false
            }
            return isHeldForTranslation(
                    message.getStatus(),
                    ReplyFallback.composedBody(message, EngineHost.installed()).translatable(),
                    message.getTranslationState(),
                    message.getTranslatedBody(),
                    message.getTranslationLang(),
                    appLanguage,
                    languageOf(conversation),
                    ConversationName.of(conversation),
                    interpreter)
        }

        /**
         * The owner's own retry: they tapped an unsent message that is waiting for its translation, which
         * means "translate this one now and send it" - the outgoing mirror of a tap on a covered received
         * message, and the only retry a held send has.
         *
         * It runs the same decision the composer's own send runs, so a translation that already
         * succeeded is reused rather than bought again, and it leaves by the same route: the caller may
         * pass the interface's own [Sender], which is what keeps this conversation's encryption
         * (plain, OTR, OMEMO) on the message. What it cannot do - no key, an unknown language, the cap
         * reached, the account out of credit, the API unreachable - reaches the listener and, through it,
         * the composer's bar, in the same words as any other held send. It never falls back to sending the
         * message untranslated, and it never shows anybody's original.
         *
         * **What it does differently from the composer's own send is the request.** The
         * translation that put this message in doubt is the one this tap is retrying, so the fresh answer
         * is asked for with the app's own re-ask clause appended to the ordinary question
         * ([requestForSend]) - the same clause the receive path's tap uses - because asking the
         * identical question is how an echoed answer comes back verbatim forever.
         */
        @JvmStatic
        fun sendHeldNow(
                context: Context?,
                message: Message?,
                listener: Listener?,
                sender: Sender?
        ) {
            // Tulkki: item 16's opposite taps, asked before anything is bought. A doubtful *language* is
            // a real translation the owner is accepting by this tap, so the stored answer goes as it
            // stands; an echo or an empty answer is nothing translated, so the stored text is the
            // owner's own words and it is discarded rather than sent, and the pipeline buys a fresh
            // answer - asked again with the app's clause, never in the same words that produced the echo.
            if (sendStoredDoubt(context, message, listener, sender)) {
                return
            }
            release(context, message, listener, sender)
        }

        /**
         * The owner's one exception to "nothing is sent untranslated": they asked for <em>this</em>
         * message to go as they wrote it.
         *
         * <p>It is deliberately not a mode, not a setting and never automatic. It exists only on a
         * row in the send-failure state, so the only way to reach it is the per-message action the
         * failure's own surface offers, and it sends exactly that one message once, untranslated. The
         * row's persisted record says it was sent as written and why ({@code sentAsWritten}), and the
         * step out of the failure state happens before the send, so a second tap is not a second send.
         *
         * <p>This overload is the decision and the record without a {@code Context}, so the
         * exactly-once behaviour is exercised by a JVM cell; the {@code Context} overload below is
         * the production entry that also writes the row and hands it to the send path.
         *
         * @return whether the row was a send failure and was marked sent as written
         */
        @JvmStatic
        fun sendAsWritten(
                message: Message?,
                settings: TranslationSettings?,
                target: String?,
                sender: Sender?
        ): Boolean {
            if (message == null || settings == null) {
                return false
            }
            // Only a send failure offers this, and only once: the first call moves the row out of
            // that state, so a second tap - or a stale listener - sends nothing more.
            if (!HeldSend.isSendFailure(message.getStatus(), message.getTranslationState())) {
                return false
            }
            val uuid = message.getUuid() ?: return false
            val reason = settings.sendFailure(uuid) ?: HeldSend.HoldReason.FAILED
            // The record goes down before the send: a kill in between leaves a row that still says
            // what happened rather than one that looks like an ordinary failure.
            settings.setSendFailure(uuid, null)
            settings.setSentAsWritten(uuid, reason)
            // "Already decided" is what lets the send path release the row without a translation:
            // the wire body is the owner's own text and nothing was bought.
            message.setTranslationState(Message.TRANSLATION_SAME_LANGUAGE)
            message.setTranslationLang(target)
            sender?.send(message)
            return true
        }

        /**
         * The production entry: the decision above, the row written to the database before the send,
         * and the message handed to the interface's own send route (or straight to the host when
         * there is no screen).
         */
        @JvmStatic
        fun sendAsWritten(context: Context?, message: Message?, sender: Sender?) {
            if (context == null || message == null) {
                return
            }
            val conversation = asConversation(message) ?: return
            val settings = TranslationSettings.get(context)
            if (!sendAsWritten(message, settings, languageOf(conversation), sender)) {
                return
            }
            val host = EngineHost.of(context)
            host.databaseBackend().updateMessage(message, false)
            if (sender == null) {
                host.resendMessage(message, false)
            }
        }

        /**
         * The tap's first question (docs/MIGRATION.md item 16): is there a stored answer this tap may send?
         *
         * Only the doubtful-language kind has one. The kind comes from the store rather than from
         * anything live, because the tap happens after a kill as often as not; the answer itself is the
         * message's own stored `translated_body`, which is inert while the state is not `DONE`
         * (`HeldSend.draftOf`) and is overwritten by [swap] when the answer really goes out.
         *
         * @return `true` when the stored answer was sent and nothing was bought
         */
        private fun sendStoredDoubt(
                context: Context?,
                message: Message?,
                listener: Listener?,
                sender: Sender?
        ): Boolean {
            if (context == null || message == null) {
                return false
            }
            val host = EngineHost.of(context)
            val settings = TranslationSettings.get(context)
            val kind = settings.heldDoubt(message.getUuid())
            val stored = message.getTranslatedBody()
            if (!HeldDoubt.sendsStoredAnswer(kind) || stored == null || stored.trim().isEmpty()) {
                forgetStoredDoubt(host, settings, message)
                return false
            }
            val heldNotes = settings.heldNotes(message.getUuid())
            settings.setHeldDoubt(message.getUuid(), null)
            settings.setHeldNotes(message.getUuid(), null)
            // Before the swap, so the bubble that is about to be re-bound finds them in the memo.
            ReviewStore.reemit(
                    context, message.getBody(), ReviewStore.reviewLanguage(context), heldNotes)
            swap(
                    host,
                    message,
                    ReplyFallback.composedBody(message, host),
                    message.getBody(),
                    stored,
                    message.getTranslationLang())
            handOver(host, message, sender)
            return true
        }

        /**
         * Over: the hold's stored answer and kind both go. Called by the tap's re-translate road, by a
         * fresh failure (so a stale answer cannot be sent by a later tap) and by nothing else - the
         * send-as-held road hands the answer to [swap], which owns it from then on.
         */
        private fun forgetStoredDoubt(
                host: EngineHost,
                settings: TranslationSettings?,
                message: Message
        ) {
            if (settings != null) {
                settings.setHeldDoubt(message.getUuid(), null)
                settings.setHeldNotes(message.getUuid(), null)
            }
            if (message.getTranslatedBody() != null) {
                message.setTranslatedBody(null)
                host.databaseBackend().updateMessage(message, false)
            }
        }

        /**
         * Writes the message into the conversation as upstream's own unsent outgoing message: in the
         * list straight away, unsent, and in the database so it survives the process being killed.
         */
        @JvmStatic
        fun hold(context: Context?, message: Message?) {
            if (context == null || message == null) {
                return
            }
            val conversation = asConversation(message) ?: return
            val host = EngineHost.of(context)
            message.setStatus(Message.STATUS_WAITING)
            // A held row is written to the database before it is sent, so it has a uuid: the uuid is the
            // key the row goes in under and the key the owner's tap finds it by. Without one there is
            // nothing to write and nothing to find, so this is an invariant, not a case to handle.
            val uuid = checkNotNull(message.getUuid()) {
                "a held outgoing row is written to the database before it is sent, so it has a uuid"
            }
            // Tulkki: "is this row already in the conversation at all", not "is it still unsent". A
            // send failure is a row the conversation already holds at STATUS_SEND_FAILED, which
            // findUnsentMessageWithUuid does not answer for; asking the unsent question and adding
            // the row again is how one database row becomes two bubbles (the reason HeldSend's own
            // note exists), and upstream's long-press "send again" is a reachable way to re-hold one.
            if (conversation.findMessageWithUuid(uuid) == null) {
                conversation.add(message)
                host.databaseBackend().createMessage(message)
            } else {
                host.databaseBackend().updateMessage(message, true)
            }
            host.updateConversationUi()
        }

        /**
         * Decide what this message needs and send it, or say why it is staying. Always runs on the
         * translation thread: detection, the cap check and the request all belong off the main thread.
         *
         * Private on purpose, and it is the whole shape of the rule: there are exactly two ways to buy
         * a translation for an outgoing message - the owner's own send ([submit]) and the owner's
         * own tap ([sendHeldNow]) - and no third one for a reconnect, a resume or a raised cap to
         * reach.
         *
         * The tap's road is the re-ask: an answer the check refused is not asked for again in the same
         * words, because repeating the question reproduces the refusal (docs/MIGRATION.md item 17, six). The
         * composer's own send asks the ordinary question, which is what keeps the owner's template the
         * instruction on the normal path.
         */
        private fun release(
                context: Context?,
                message: Message?,
                listener: Listener?,
                sender: Sender?
        ) {
            schedule(context, message, listener, sender, false, true)
        }

        private fun schedule(
                context: Context?,
                message: Message?,
                listener: Listener?,
                sender: Sender?,
                holdFirst: Boolean,
                reAsk: Boolean
        ) {
            val conversation = asConversation(message)
            if (context == null || conversation == null || message == null) {
                return
            }
            // One decision per message, keyed by the uuid every held row carries: the same row can
            // reach the lane as a different instance, so the key is the row's, not the object's.
            val uuid = checkNotNull(message.getUuid()) {
                "an outgoing decision is keyed by the uuid every held row carries"
            }
            if (!IN_FLIGHT.add(uuid)) {
                // A decision for this message is already running; it will report.
                return
            }
            OUTGOING.launch {
                try {
                    if (holdFirst) {
                        hold(context, message)
                    }
                    decide(context, conversation, message, listener, sender, reAsk)
                } finally {
                    IN_FLIGHT.remove(uuid)
                }
            }
        }

        private fun decide(
                context: Context,
                conversation: Conversation,
                message: Message,
                listener: Listener?,
                sender: Sender?,
                reAsk: Boolean
        ) {
            val host = EngineHost.of(context)
            val settings = TranslationSettings.get(context)
            val appLanguage = settings.appLanguage()
            val target = languageOf(conversation)
            // Tulkki: a reply's body is the quote it carries plus the owner's own text, and only the
            // owner's own text is translated. The boundary comes from the span the sender declared
            // (ReplyFallback), never from reading the text, so the quote stays on the wire verbatim - in
            // the conversation's language, which is what a peer without XEP-0461 support needs - and is
            // never billed for a second time. The span is read once here and used twice: for the split
            // and for the "could the quote be placed" question the review rides on.
            val span = host.replySpan(message)
            val composed = ReplyFallback.of(message.getBody(), span)
            if (HeldSend.mayReuse(
                    message.getTranslatedBody(),
                    message.getTranslationState(),
                    message.getTranslationLang(),
                    target)) {
                // Already bought, and for this very language: send it, do not pay again.
                handOver(host, message, sender)
                return
            }
            val draft =
                    HeldSend.draftOf(
                            composed.translatable(),
                            message.getTranslationState(),
                            message.getTranslatedBody())
            if (!HeldSend.needsTranslation(
                    draft,
                    appLanguage,
                    target,
                    ConversationName.of(conversation),
                    settings.interpreter())) {
                // A link, a number, a ping, the room's own bare name, or a room that already speaks the
                // app language.
                markNeedsNoTranslation(host, message, target)
                handOver(host, message, sender)
                return
            }
            val local = HeldSend.localReason(settings.hasApiKey(), capReached(settings), target)
            if (local != null) {
                // Held before anything was bought. Nothing came back to check, so the attempt is kept
                // without readers and the hold reason is the whole story.
                remember(
                        host,
                        OutgoingAttempts.notAttempted(
                                message.getUuid(),
                                appLanguage,
                                target,
                                local,
                                requestKind(reAsk),
                                System.currentTimeMillis()))
                notifyHeld(listener, local)
                return
            }
            try {
                // Tulkki: the notes on the owner's own wording ride along in this very request, which
                // already holds the draft. One call, one count, one answer with two parts - the
                // translation, which is the message, and the review, which is commentary on it. They are
                // asked for only when everything the call is given is the owner's own text: a reply whose
                // declared quote could not be placed has somebody else's message inside its translatable
                // part, and a remark about that would be a remark about the received side. A re-ask is its
                // own question (see requestForSend), so this is the normal path's shape and not the
                // request itself.
                val reviewLanguage = settings.studyLanguage()
                val deepSeek = client(settings)
                val result =
                        requestForSendWithRetry(
                                deepSeek,
                                draft,
                                target,
                                reviewLanguage,
                                Review.onlyOwnWords(
                                        ReplyFallback.declared(span), composed.hasCarried()),
                                reAsk,
                                { line -> Log.d(TAG, line) }) { millis -> Thread.sleep(millis) }
                Spend.record(
                        settings,
                        result.usage,
                        System.currentTimeMillis(),
                        ZoneId.systemDefault(),
                        // The ledger's grouping key is the conversation's uuid and nothing else; the name
                        // is resolved from `conversations` when the ledger is read. The review rides along
                        // in this one call, so it belongs to the same room as the translation.
                        conversation.getUuid())
                // Tulkki: the answer has to be a translation. Nothing is sent untranslated, and an answer
                // that is the draft handed back, an empty body, or a language nobody asked for is not a
                // translation - so it is refused exactly as a failed call is, and the message stays held
                // with the reason named. The check is local: it costs no request and no tokens.
                //
                // The inspection keeps every reader's own reading beside the verdict, so the attempt can
                // be written down with codes and confidences instead of being reduced to one word.
                val inspection =
                        LanguageCheck.inspect(
                                draft,
                                result.text,
                                target,
                                appLanguage,
                                result.detectedLanguage)
                val check = inspection.report
                val answerLength = result.text?.length ?: 0
                // Accepted on doubt is still not sent (docs/MIGRATION.md item 16) - unless this conversation's
                // own switch says otherwise, and never when the answer is the draft handed back. That
                // whole rule is one derived setting (DoubtHold.holds), asked once here, and the hold the
                // verdict leads to is decided once beside it so the record and the branch below cannot
                // disagree: a refusal is FAILED, a held doubt is DOUBT, and an answer that goes out is
                // held by nothing.
                //
                // Not a second code path either: when the answer is not held, it takes exactly the path an
                // OK answer takes - the same review record, the same swap, the same hand-over, no second
                // send route.
                val doubted = check.acceptedOnDoubt() && DoubtHold.holds(check.doubt, conversation.getDoubtHold())
                val checkHold = if (check.failed() || doubted) holdReason(check) else null
                remember(
                        host,
                        OutgoingAttempts.of(
                                message.getUuid(),
                                appLanguage,
                                target,
                                answerLength,
                                inspection,
                                checkHold,
                                requestKind(reAsk),
                                System.currentTimeMillis()))
                if (check.failed()) {
                    holdUnusable(context, message, settings, listener, check, reAsk)
                    return
                }
                if (doubted) {
                    holdOnDoubt(
                            context,
                            message,
                            listener,
                            settings,
                            check,
                            result.text,
                            target,
                            result.review,
                            reAsk)
                    return
                }
                // The answer is going out, so any doubt this message was held on is over.
                settings.setHeldDoubt(message.getUuid(), null)
                // Kept before the send, for the same reason the translation is: this is the only moment
                // the notes exist. It cannot fail the send - a review that cannot be stored is a review
                // the bubble will not show, not a message that will not leave.
                ReviewStore.record(context, draft, reviewLanguage, result.review)
                swap(host, message, composed, draft, result.text, target)
                handOver(host, message, sender)
            } catch (e: DeepSeekClient.TranslationException) {
                // A failed translation is not a send: the message stays held - or, when the owner's
                // own retry is the attempt that failed, is recorded and drawn as a send failure - and
                // says why.
                val reason = HeldSend.failureReason(e.retryable, e.message)
                if (reAsk) {
                    failSend(host, settings, message, reason)
                } else {
                    message.setTranslationState(Message.TRANSLATION_FAILED)
                    host.databaseBackend().updateMessage(message, false)
                }
                // No answer came back, so the attempt is kept as one that never reached the check; the
                // line still says which language was asked for and why the send is held.
                remember(
                        host,
                        OutgoingAttempts.notAttempted(
                                message.getUuid(),
                                appLanguage,
                                target,
                                reason,
                                requestKind(reAsk),
                                System.currentTimeMillis()))
                // Tulkki: remembered where the last failure is already kept, so that a send the owner
                // cannot get out is answerable afterwards - the composer says why once, and the failures
                // screen has to be able to say it again. The record keeps one failure, so this is exact
                // for the newest one and the screen says "not kept" for an older one rather than guessing.
                host.activity()
                        .recordFailure(
                                reason,
                                e.message,
                                message.getUuid(),
                                System.currentTimeMillis())
                notifyHeld(listener, reason)
            }
        }

        /**
         * The send path's one request builder, as a value a JVM test can ask without a screen: which
         * question goes out for what the caller knows about this send.
         *
         * **A re-ask asks the ordinary translate question with the app's own clause**
         * ([DeepSeekClient.translate]), exactly the shape the receive
         * path's own tap sends (`TranslationService`'s `client.translate(..., reAsk)`). The
         * owner's template is rendered byte for byte and `DeepSeekClient.RE_ASK_CLAUSE` is appended
         * after it, so the template stays the instruction and the clause is the one thing that differs.
         * The clause is what stops an echoed answer coming back verbatim a second time: the tap that
         * reaches here is the one on a message the check already refused.
         *
         * **The notes are not asked for on a re-ask.** A review is cached under its own
         * prompt's identity (`ReviewKey`), and that identity has no room for the clause - a flag
         * would be a column in `:data` - so notes bought under review-plus-clause would be filed
         * under the plain review key and read back as though they answered the plain question. Rather
         * than confuse the two, the re-ask asks only the translation: the notes are commentary and their
         * loss costs the tap nothing that the send needed.
         *
         * The normal path is unchanged: the notes ride the translation call when every word is the
         * owner's own, and the request is the one the app has always sent.
         *
         * @param notesOnOwnWords whether the call may ask for the notes - true only when every word of
         *     `draft` is the owner's own ([Review.onlyOwnWords])
         * @param reAsk whether this is the owner's tap on a held message, which asks again differently
         */
        @JvmStatic
        @Throws(DeepSeekClient.TranslationException::class)
        fun requestForSend(
                deepSeek: DeepSeekClient,
                draft: String?,
                target: String?,
                reviewLanguage: String?,
                notesOnOwnWords: Boolean,
                reAsk: Boolean
        ): DeepSeekClient.Result {
            if (reAsk) {
                return deepSeek.translate(draft, target, true)
            }
            return if (notesOnOwnWords) {
                deepSeek.translateWithReview(draft, target, reviewLanguage)
            } else {
                deepSeek.translate(draft, target)
            }
        }

        /**
         * The same request, retried in place while the failure is one a retry could fix.
         *
         * **Why this exists.** One send is one purchase, and the owner is standing in front of it:
         * the message is held, the composer has nothing to show yet, and a single `IOException` from a
         * phone's radio - or a 429 - was enough to paint the bar with a failure, write the row as a
         * failed attempt and leave it for a tap. The owner's report is exactly that: the notice
         * appears, and the send then goes through anyway. A blip is not an answer, so a retryable
         * failure is waited out here, inside the one decision, and the row and the bar hear nothing
         * about it.
         *
         * **What this is not.** It is not [TranslationBackoff], and it is not the owner's retry: it
         * waits seconds ([SendRetry] owns the schedule - three waits, about twenty-two seconds in
         * all) and then gives up the way it always did. It starts nothing on its own
         * - no reconnect, no timer, no conversation opening - because it runs only inside a decision a
         * caller already started ([submit] or [sendHeldNow]), and [holdBack] still buys nothing. A
         * failure that is not [DeepSeekClient.TranslationException.retryable] (a rejected key, an empty
         * balance, an answer that is not the contract) is never waited on: repeating it would only ask
         * the same question again.
         *
         * [wait] is the waiting itself, handed in so a JVM cell can drive the loop without a clock;
         * production passes `Thread.sleep`, which this coroutine's [Dispatchers.IO] lane can afford.
         * [log] is the diagnostic line, handed in for the same reason: the module's own tests do not
         * run Android's `Log`, and a retry is exactly the fact a bug report needs.
         */
        @JvmStatic
        @Throws(DeepSeekClient.TranslationException::class)
        fun requestForSendWithRetry(
                deepSeek: DeepSeekClient,
                draft: String?,
                target: String?,
                reviewLanguage: String?,
                notesOnOwnWords: Boolean,
                reAsk: Boolean,
                log: (String) -> Unit,
                wait: (Long) -> Unit
        ): DeepSeekClient.Result {
            var attempts = 0
            while (true) {
                attempts++
                try {
                    return requestForSend(
                            deepSeek, draft, target, reviewLanguage, notesOnOwnWords, reAsk)
                } catch (e: DeepSeekClient.TranslationException) {
                    if (!e.retryable || !SendRetry.retryable(attempts)) {
                        throw e
                    }
                    log(
                            "a send's translation could not be reached; trying again (attempt " +
                                    (attempts + 1) +
                                    " of " +
                                    SendRetry.ATTEMPTS +
                                    ")")
                    wait(SendRetry.delayMillis(attempts))
                }
            }
        }

        /**
         * The reason a hold the check's own outcome caused is filed under: an answer accepted on doubt is
         * [HeldSend.HoldReason.DOUBT] and never `FAILED` - nothing failed - while an answer
         * the check refused is the evidence failure it always was.
         *
         * Kept as a function rather than written at the two call sites so the distinction is
         * testable: `holdOnDoubt` and `holdUnusable` are driven by a `Context` and a
         * screen, but this is the whole of the fact they must not flatten.
         */
        @JvmStatic
        fun holdReason(check: LanguageCheck.Report?): HeldSend.HoldReason =
                if (check != null && check.acceptedOnDoubt()) {
                    HeldSend.HoldReason.DOUBT
                } else {
                    HeldSend.HoldReason.FAILED
                }

        /**
         * Records the row as a <strong>send failure</strong>: the owner's retry was tried, nothing
         * was sent, and the state has to say so - now and after a restart.
         *
         * <p>Three persisted facts, and the row's own columns carry two of them: the upstream status
         * becomes {@code STATUS_SEND_FAILED} (which is what the conversation draws as a failure and
         * what {@code HeldSend.isSendFailure} classifies), the translation state stays
         * {@code TRANSLATION_FAILED}, and the reason is written to the per-message store so the
         * resting bar can name it for <em>this</em> row rather than for whichever failure happened
         * last. The database write comes first, so a kill in between leaves the state rather than
         * losing it.
         *
         * <p>Only a failed attempt reaches here: a local reason that stopped the send before it was
         * tried (no key, no credit, the cap) leaves an ordinary hold, because that row can still send
         * once the owner fixes the cause.
         */
        private fun failSend(
                host: EngineHost,
                settings: TranslationSettings,
                message: Message,
                reason: HeldSend.HoldReason
        ) {
            message.setStatus(Message.STATUS_SEND_FAILED)
            message.setTranslationState(Message.TRANSLATION_FAILED)
            settings.setSendFailure(message.getUuid(), reason)
            host.databaseBackend().updateMessage(message, false)
        }

        /**
         * An answer that is not a translation of the draft, refused exactly as a failed call is: the
         * message stays held, nothing goes on the wire, and the composer is told the answer was not
         * usable. The reason is remembered like any other failure, so the failures screen can say it
         * again - and it names languages and outcomes only, never the draft.
         *
         * <p>When {@code reAsk} is true this attempt was the owner's own retry, so the row stops
         * being an ambiguous hold and becomes a send failure ({@link #failSend}) - it cannot send,
         * and the owner must be able to see that, and to send it as written, after a restart.
         */
        private fun holdUnusable(
                context: Context,
                message: Message,
                settings: TranslationSettings,
                listener: Listener?,
                check: LanguageCheck.Report,
                reAsk: Boolean
        ) {
            val host = EngineHost.of(context)
            // A stale stored answer must not survive its hold's failure: a later tap would otherwise find
            // a doubtful-language kind next to text this attempt has already replaced.
            forgetStoredDoubt(host, settings, message)
            val reason = holdReason(check)
            if (reAsk) {
                failSend(host, settings, message, reason)
            } else {
                message.setTranslationState(Message.TRANSLATION_FAILED)
                host.databaseBackend().updateMessage(message, false)
            }
            host.activity()
                    .recordFailure(
                            reason, check.because, message.getUuid(), System.currentTimeMillis())
            notifyHeld(listener, reason)
        }

        /**
         * An answer the check accepted only on doubt, held for the owner's tap rather than sent
         * (docs/MIGRATION.md item 16). The message stays in the conversation, nothing goes on the wire, and
         * the doubt's own sentence is remembered where the last failure is kept, so the failures screen
         * can say which kind it was.
         *
         * The two doubt kinds want *opposite* taps, and that is a type's business and not this
         * method's: [LanguageCheck.Doubt.tap] says whether the held answer is bought again
         * (nothing was translated) or may be sent as it stands (a language nobody could vouch for). The
         * receive path is not told about any of it - for what is drawn, an accepted-on-doubt answer is
         * the translation it probably is.
         *
         * The hold is filed under [HeldSend.HoldReason.DOUBT], never `FAILED`: nothing
         * failed, and the composer bar's `tulkki_hold_failed` ("DeepSeek could not translate this
         * message") is a lie about doubt. The distinction that matters for the tap is already typed on
         * `Doubt` and kept beside the answer (`HeldDoubt`), and the sentence recorded here is
         * the doubt's own, so a surface can say which kind it was without this method deciding.
         *
         * The reason's own words in the conversation surface's per-reason table are not in this
         * module: `TranslationText.held` and `ConversationFragment.holdMessage` are `:ui`'s, so that half
         * is reported with the commit rather than written here. Until it lands, a doubt hold falls through
         * those tables' `default` and still reads as a failure; the reason itself, the record and the
         * failures screen's phrase are honest already.
         */
        private fun holdOnDoubt(
                context: Context,
                message: Message,
                listener: Listener?,
                settings: TranslationSettings,
                check: LanguageCheck.Report,
                answer: String?,
                target: String?,
                review: Review,
                reAsk: Boolean
        ) {
            val host = EngineHost.of(context)
            // The answer is kept with the message, in the message's own persisted `translated_body`, so
            // that the tap can send it after the app has been killed; the state stays `FAILED`, which is
            // what the display, the failures screen and the held-send check already read, so none of the
            // three has to learn a new state. The kind is kept beside it (`HeldDoubt`), because the two
            // taps differ and the tap happens in another process lifetime.
            message.setTranslatedBody(answer)
            message.setTranslationLang(target)
            settings.setHeldDoubt(message.getUuid(), check.doubt)
            // The notes rode the same paid call as the answer, so they are kept with it: sending the held
            // answer later re-emits them (`ReviewStore.reemit`) instead of losing marks already paid for.
            settings.setHeldNotes(message.getUuid(), ReviewStore.rawOf(review))
            val reason = holdReason(check)
            // Tulkki: an answer that came back unchanged is nothing translated, so the owner's own
            // retry of it cannot send either - the row is a send failure. The doubtful-language kind
            // is the opposite case: the answer is sendable as held, so it stays a hold whose tap
            // accepts it. Nothing was translated is `NOTHING_TRANSLATED` alone; `check.failed()` is
            // the other refusal and never reaches here.
            if (reAsk && check.doubt == LanguageCheck.Doubt.NOTHING_TRANSLATED) {
                failSend(host, settings, message, reason)
            } else {
                message.setTranslationState(Message.TRANSLATION_FAILED)
                host.databaseBackend().updateMessage(message, false)
            }
            host.activity()
                    .recordFailure(
                            reason,
                            check.doubt?.because ?: check.because,
                            message.getUuid(),
                            System.currentTimeMillis())
            notifyHeld(listener, reason)
        }

        /**
         * Translate a foreign draft into the app language, for the composer's suggestion - and decide
         * whether the gate was wrong to refuse it.
         *
         * A suggestion that *is* the draft ([ComposerGate.suggestionIsTheDraft]) means the
         * draft was already the app language, so the listener is told to send it normally instead of
         * being shown a prompt. That is the owner's rule, and the local reading does not get a veto over
         * it: the detector confidently calls a bare name a language ("Matti" is Maltese at 0.96), and a
         * heuristic with a demonstrated failure mode must not make the owner retype their own language.
         *
         * The answer is cached like any other, keyed the same way the receive path keys a translation
         * into the app language, so a repeated draft does not buy the same check twice; a hit is free and
         * a miss is counted against the daily cap by [Spend.record].
         *
         * With the interpreter off the guard returns before the key is read, so a direct caller cannot
         * buy anything: there is no gate to suggest for, and the composer's prompt that was the only
         * caller is unreachable (MIGRATION.md "Design: the interpreter off-switch" §2.4).
         */
        @JvmStatic
        fun suggest(context: Context?, draft: String?, listener: Suggestions?) {
            if (context == null || listener == null || draft == null || draft.trim().isEmpty()) {
                return
            }
            val settings = TranslationSettings.get(context)
            if (!settings.interpreter().enabled()) {
                return
            }
            val appLanguage = settings.appLanguage()
            val local =
                    HeldSend.localReason(settings.hasApiKey(), capReached(settings), appLanguage)
            if (local != null) {
                notifyUnavailable(listener, local)
                return
            }
            OUTGOING.launch {
                try {
                    val cache = TranslationCache(TranslationStore(context))
                    val key = PromptBook.translationKey(draft, appLanguage)
                    val cached = cache.get(key)
                    val answer: String?
                    if (cached != null) {
                        answer = cached.translatedBody
                    } else {
                        // The same in-place retry the send's own request gets: this is the one call
                        // standing between the owner's words and a failure sentence on the composer's
                        // bar, and a blip here is not a refusal either.
                        val result =
                                requestForSendWithRetry(
                                        client(settings),
                                        draft,
                                        appLanguage,
                                        null,
                                        false,
                                        false,
                                        { line -> Log.d(TAG, line) }) { millis ->
                                    Thread.sleep(millis)
                                }
                        Spend.record(
                                settings,
                                result.usage,
                                System.currentTimeMillis(),
                                ZoneId.systemDefault(),
                                // A pre-send suggestion: a draft, bought before any message exists,
                                // and the same draft in two rooms shares one cached answer - so it
                                // is a call tied to no conversation, which is what null says.
                                null)
                        cache.store(
                                key,
                                result.detectedLanguage,
                                result.text,
                                result.totalTokens,
                                System.currentTimeMillis())
                        answer = result.text
                    }
                    if (ComposerGate.suggestionIsTheDraft(draft, answer)) {
                        logStoodDown(draft)
                        notifyConfirmsDraft(listener)
                    } else {
                        notifySuggestion(listener, answer)
                    }
                } catch (e: DeepSeekClient.TranslationException) {
                    notifyUnavailable(
                            listener, HeldSend.failureReason(e.retryable, e.message))
                }
            }
        }

        /**
         * The permanent line for the one case where the model's answer beats the offline detector. It
         * names both sides - what the detector read, and that the model answered with the draft itself -
         * because this is the decision that lets a message the gate wanted to refuse go out as typed.
         * The draft's own words are not logged: they are the owner's, and a log is a channel.
         */
        private fun logStoodDown(draft: String) {
            Log.d(
                    TAG,
                    "the composer gate stood down: the offline detector read "
                            + TextLanguage.detect(draft)
                            + " but the model answered with the draft itself")
        }

        /**
         * Whether this send may spend today: the daily cap's reserve line, not its number. The cap's last
         * tenth is held for received translation (docs/MIGRATION.md item 17, three), so the send side stops at
         * [DailyTokenCounter.sendLine] - it has its own hold and its own deliberate tap, while a message
         * someone said to the owner has no other way to be read. A cap of zero or less is no cap.
         */
        @JvmStatic
        fun capReached(settings: TranslationSettings): Boolean {
            val cap = settings.dailyTokenCap()
            if (cap <= DailyTokenCounter.UNLIMITED) {
                return false
            }
            return DailyTokenCounter.exhausted(
                    todayUsed(settings), cap, DailyTokenCounter.Purpose.SEND)
        }

        @JvmStatic
        fun todayUsed(settings: TranslationSettings): Int =
                settings
                        .tokenCounter()
                        .used(
                                DailyTokenCounter.dayOf(
                                        System.currentTimeMillis(), ZoneId.systemDefault()))

        /** Hands the ready message to the interface when there is one, else straight to the host. */
        private fun handOver(host: EngineHost, message: Message, sender: Sender?) {
            if (sender == null) {
                host.resendMessage(message, false)
                return
            }
            TulkkiMainThread.post { sender.send(message) }
        }

        /**
         * Tulkki: an edit replaced the text this message was translated from, so nothing the translation
         * layer holds for it is about the message any more.
         *
         * The stored pair was made from the previous version, and `HeldSend.mayReuse` cannot tell
         * that apart from a genuine reuse - it compares the target language, not the text - so leaving it
         * would send the edit untranslated while looking like a deliberate saving. The queue item, if
         * there is one, is keyed by text that is gone. Called before the body is replaced and before the
         * uuid moves, so the item's key is still the row's key when it is dropped.
         */
        @JvmStatic
        fun forgetTranslation(context: Context?, message: Message?) {
            if (context == null || message == null) {
                return
            }
            val uuid = message.getUuid()
            if (uuid != null) {
                TranslationStore(context).forget(uuid)
            }
            message.setTranslatedBody(null)
            message.setTranslationLang(null)
            message.setTranslationState(Message.TRANSLATION_NONE)
        }

        /**
         * The moment the translation lands: the text that goes on the wire becomes the body - every
         * encryption path reads it from there - and the owner's draft is kept as the stored translation,
         * which is what the interface shows. Written to the database before the send, so a kill in
         * between cannot lose the translation and buy it again.
         *
         * The wire body is composed here, quote first: the quote is not translated and does not move,
         * so the fallback span the sender declared still describes it after this write. That is also why
         * the stored pair is honest for a reply - `translated_body` is the owner's own text, which
         * is what the bubble shows on top, and the quote is rendered from the referenced row rather than
         * out of this body.
         */
        private fun swap(
                host: EngineHost,
                message: Message,
                composed: ComposedBody,
                draft: String?,
                wire: String?,
                target: String?
        ) {
            // The final write to an outgoing body, and the answer is text: the body may carry the
            // markup the composer wrote, so it is set through the body's markup-aware setter. The
            // plain `setBody(String)` clears a stale alternate - which is right for a received body
            // and wrong here, where it is the styling the composer carried that is being cleared.
            // Nothing in this module reads the syntax; the body's own type does.
            message.setBodyKeepingMarkup(composed.recompose(wire))
            message.setTranslatedBody(draft)
            message.setTranslationLang(target)
            message.setTranslationState(Message.TRANSLATION_DONE)
            host.databaseBackend().updateMessage(message, true)
            // One more message translated today, for the usage screen's count. A send that reused an
            // earlier translation did not translate anything today, so it does not come through here.
            countTranslated(host)
        }

        /** Records one translated message against today, in the one counter every surface reads. */
        private fun countTranslated(host: EngineHost) {
            host.activity()
                    .addTranslated(
                            DailyTokenCounter.dayOf(
                                    System.currentTimeMillis(), ZoneId.systemDefault()),
                            1)
        }

        /**
         * Records that this message was found to need no translation, so that the send path lets it
         * through instead of holding it again - and so that a later change of the conversation's
         * language invalidates that finding.
         */
        private fun markNeedsNoTranslation(host: EngineHost, message: Message, target: String?) {
            message.setTranslationLang(target)
            message.setTranslationState(Message.TRANSLATION_SAME_LANGUAGE)
            host.databaseBackend().updateMessage(message, false)
        }

        private fun client(settings: TranslationSettings): DeepSeekClient =
                DeepSeekClient(
                        HTTP,
                        settings.apiKey(),
                        DeepSeekClient.endpointFor(settings.apiBaseUrl()),
                        DeepSeekClient.DEFAULT_MODEL)

        /**
         * Outgoing plain text only. A file or image message is not held: its caption is a caption, and
         * holding an upload would be a different feature. A PGP message is not held either: by the time
         * it reaches the send path its body is ciphertext, and translating ciphertext is nonsense.
         *
         * An *edit* is holdable, deliberately. It used to be refused here because corrections
         * bypassed this class altogether and a silent refusal would have been worse than sending one
         * untranslated; now the composer sends a correction through the same door as a new message, so an
         * edit that is held is held, and tappable, like anything else. A retraction is still refused - it
         * has no text to translate - and that is what the deleted and retract checks are.
         *
         * The held status is `STATUS_WAITING`, which is upstream's "queued, not sent yet": it
         * shows in the conversation as a pending message, it survives the process being killed, and it is
         * the one status upstream re-offers to the send path on its own. That re-offer is why the status
         * matters here rather than only being cosmetic: it is what brings a held message back to
         * [holdBack], which refuses it again without buying anything - and it is what a tap on the
         * bubble is offered for, so a message held when the process was killed is still reachable
         * afterwards.
         */
        private fun isHoldableOutgoingText(message: Message): Boolean {
            if (message.getType() != Message.TYPE_TEXT
                    && message.getType() != Message.TYPE_PRIVATE) {
                return false
            }
            if (message.isDeleted() || message.getRetractId() != null) {
                return false
            }
            when (message.getEncryption()) {
                Message.ENCRYPTION_NONE,
                Message.ENCRYPTION_OTR,
                Message.ENCRYPTION_AXOLOTL -> {}
                else -> return false
            }
            return message.getStatus() > Message.STATUS_RECEIVED
        }

        private fun asConversation(message: Message?): Conversation? =
                message?.getConversation() as? Conversation

        private fun notifyHeld(listener: Listener?, reason: HeldSend.HoldReason?) {
            if (listener == null) {
                return
            }
            TulkkiMainThread.post { listener.onHeld(reason) }
        }

        private fun notifySuggestion(listener: Suggestions, text: String?) {
            TulkkiMainThread.post { listener.onSuggestion(text) }
        }

        private fun notifyConfirmsDraft(listener: Suggestions) {
            TulkkiMainThread.post { listener.onConfirmsDraft() }
        }

        private fun notifyUnavailable(listener: Suggestions, reason: HeldSend.HoldReason?) {
            TulkkiMainThread.post { listener.onUnavailable(reason) }
        }
    }
}
