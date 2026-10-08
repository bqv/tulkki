package uk.xa0.tulkki.ui.composer

import android.content.Context
import android.view.View

import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.LanguageCheck
import uk.xa0.tulkki.translation.OutgoingTranslation
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.R

import java.util.function.Consumer

/**
 * Tulkki: the bar's sentences and buttons for a send that is held or failed.
 *
 * <p>It is the send path's third surface (parity row 10c): what the composer says when nothing left
 * the device. It draws nothing itself - [ComposerBarController] does - and it sends nothing itself:
 * the retry and "send as written" are the fragment's own transport, handed in as callbacks, so the
 * conversation's encryption still applies where it always did.
 *
 * <p>**The reading is here.** Which row is a send failure, which is still held, and which of the two
 * the resting bar must draw are all decided below, from the rows' own persisted columns and from
 * [OutgoingTranslation]'s own questions, so the bar, the bubble's tap and the resting refresh cannot
 * disagree. The Java kept these as nine methods that only ever called each other.
 *
 * <p>**What stays Java, and why it is not this file's.** `sendTranslated` and `translateHeldNow` are
 * the service calls, and `raiseCapDialog` builds a dialog; both are handed in. `holdMessage` is
 * shared with the gate prompt (parity row 10b), so it lives in [HeldSendText] rather than here.
 */
class HeldSendSurfaces(
    private val context: Context,
    private val bar: ComposerBarController,
    private val translateNow: Consumer<Message>,
    private val sendAsWritten: Consumer<Message>,
    private val raiseCap: Runnable,
    private val refreshLanguageBar: Runnable,
) {

    /**
     * The newest outgoing row in this conversation that is a send failure, or `null`.
     *
     * <p>The status and the translation state are the whole test, so this is a cheap read of a row's
     * own persisted columns - which is exactly why it finds one after a restart.
     */
    fun restingSendFailure(conversation: Conversation?): Message? {
        if (conversation == null) {
            return null
        }
        val messages = conversation.messages
        synchronized(messages) {
            for (at in messages.indices.reversed()) {
                val message = messages[at]
                if (isSendFailure(message)) {
                    return message
                }
            }
        }
        return null
    }

    /**
     * The newest outgoing row Tulkki is still holding for its translation, or `null`.
     *
     * <p>Asked through [OutgoingTranslation.isHeldForTranslation] - the same question the bubble's
     * tap asks - so the resting bar and the tap cannot disagree about which rows are held.
     */
    fun restingHeldSend(conversation: Conversation?): Message? {
        if (conversation == null) {
            return null
        }
        val settings = TranslationSettings.get(context)
        val messages = conversation.messages
        synchronized(messages) {
            for (at in messages.indices.reversed()) {
                val message = messages[at]
                if (message.getStatus() != Message.STATUS_WAITING) {
                    continue
                }
                val state = message.getTranslationState()
                if (state == Message.TRANSLATION_DONE || state == Message.TRANSLATION_SAME_LANGUAGE) {
                    continue
                }
                if (OutgoingTranslation.isHeldForTranslation(
                        message, settings.appLanguage(), settings.interpreter())) {
                    return message
                }
            }
        }
        return null
    }

    /**
     * Why a message is not going anywhere, said in the composer rather than left silent.
     *
     * <p>The message comes with the reason because a doubt hold's own sentence depends on the row:
     * see [showDoubtHeld].
     */
    fun showHeldReason(message: Message?, reason: HeldSend.HoldReason?, languageKnown: Boolean) {
        // A row whose retry failed is a send failure, and its own surface offers both the retry and
        // "send as written". The state is checked before the reason, because the state - not this
        // attempt's cause - is what the bar has to draw.
        if (message != null && isSendFailure(message)) {
            showSendFailure(message)
            return
        }
        if (reason == null) {
            return
        }
        if (isUnknownLanguageHeld(reason, languageKnown)) {
            // The owner has set the language since; a message is not held for it any more.
            refreshLanguageBar.run()
            return
        }
        if (reason == HeldSend.HoldReason.DOUBT) {
            showDoubtHeld(message)
            return
        }
        if (reason == HeldSend.HoldReason.CAP_REACHED) {
            bar.show(
                HeldSendText.holdMessage(context, reason),
                context.getString(R.string.tulkki_hold_raise_cap),
                View.OnClickListener { raiseCap.run() },
                null,
                null,
                true,
                false,
                false,
            )
        } else {
            bar.show(HeldSendText.holdMessage(context, reason), null, null, null, null, true, false, false)
        }
    }

    /**
     * The resting bar for a held row - the doubt kinds through their own sentences, the cap through
     * its raise-cap bar, and every other hold through the reason recorded for it or knowable
     * locally, with the retry on the bar in every case but the cap's.
     */
    fun showRestingHeldSend(conversation: Conversation?, message: Message?, languageKnown: Boolean) {
        if (message == null) {
            return
        }
        val settings = TranslationSettings.get(context)
        if (settings.heldDoubt(message.getUuid()) != null) {
            showDoubtHeld(message)
            return
        }
        var reason = settings.activity().reasonFor(message.getUuid())
        if (isUnknownLanguageHeld(reason, languageKnown)) {
            // The owner has set the language since, so that hold is moot; the row is still scanned as
            // held, and the local questions below decide what is true of it now.
            reason = null
        }
        if (reason == null) {
            reason = HeldSend.localReason(
                settings.hasApiKey(),
                OutgoingTranslation.capReached(settings),
                OutgoingTranslation.languageOf(conversation),
            )
        }
        if (reason == HeldSend.HoldReason.CAP_REACHED) {
            // The cap's own bar is the one that offers a way out on the spot.
            showHeldReason(message, reason, languageKnown)
            return
        }
        // Every other hold carries the retry, whatever stopped it - including a refusal whose reason
        // was recorded before a restart.
        bar.show(
            HeldSendText.holdMessage(context, reason),
            retryWording(),
            View.OnClickListener { translateNow.accept(message) },
            null,
            null,
            true,
            false,
            false,
        )
    }

    /**
     * The failure's own surface - the row cannot send, the owner's retry has been tried and failed,
     * and nothing left the device.
     *
     * <p>Both actions live on the bar, not the bubble: the reading aid's gloss span shadows the
     * body's click listener, so for a one-word body the bubble's own tap target is a couple of
     * pixels wide, and the bar is always visible.
     */
    fun showSendFailure(message: Message?) {
        if (message == null) {
            return
        }
        val reason: HeldSend.HoldReason? =
            TranslationSettings.get(context).sendFailure(message.getUuid())
        val sentence: String =
            when (reason) {
                HeldSend.HoldReason.DOUBT -> context.getString(R.string.tulkki_hold_doubt_echo)
                null -> context.getString(R.string.tulkki_hold_failed)
                else -> HeldSendText.holdMessage(context, reason)
            }
        bar.show(
            sentence,
            retryWording(),
            View.OnClickListener { translateNow.accept(message) },
            context.getString(R.string.tulkki_hold_send_as_written),
            View.OnClickListener { sendAsWritten.accept(message) },
            true,
            false,
            false,
        )
    }

    /**
     * A send held on doubt, with the retry on the bar's own button.
     *
     * <p>**The two doubt kinds want opposite taps**: an answer merely in a doubtful language is a
     * real translation the owner accepts by tapping, and the stored answer goes as it stands; an
     * echo is nothing translated, so the stored text is the owner's own words and the tap re-asks
     * instead. The action goes on the bar, not the bubble - the bubble's tap is installed per row by
     * a verdict recomputed where the row is bound, so a row whose verdict no longer says HOLD has no
     * listener at all.
     */
    private fun showDoubtHeld(message: Message?) {
        if (message == null) {
            bar.show(context.getString(R.string.tulkki_hold_doubt), null, null, null, null, true, false, false)
            return
        }
        val kind: LanguageCheck.Doubt? = TranslationSettings.get(context).heldDoubt(message.getUuid())
        val retry = View.OnClickListener { translateNow.accept(message) }
        if (kind == LanguageCheck.Doubt.DOUBTFUL_LANGUAGE) {
            bar.show(
                context.getString(R.string.tulkki_hold_doubt),
                context.getString(R.string.tulkki_hold_doubt_send),
                retry,
                null,
                null,
                true,
                false,
                false,
            )
        } else {
            bar.show(
                context.getString(
                    if (kind == LanguageCheck.Doubt.NOTHING_TRANSLATED) {
                        R.string.tulkki_hold_doubt_echo
                    } else {
                        R.string.tulkki_hold_doubt
                    },
                ),
                retryWording(),
                retry,
                null,
                null,
                true,
                false,
                false,
            )
        }
    }

    /**
     * The retry action's own wording, the owner's edit when they have written one and the shipped
     * wording otherwise. UI copy, read here and nowhere near a prompt's cache identity.
     */
    private fun retryWording(): String = TranslationSettings.get(context).retryWording()

    /** Whether this row is the send failure both actions belong to. */
    private fun isSendFailure(message: Message?): Boolean =
        message != null && HeldSend.isSendFailure(message.getStatus(), message.getTranslationState())

    /**
     * Whether a hold reason is one this conversation no longer has: a message waiting on an unknown
     * language is not waiting on it once the owner has set one. The answer is the fragment's - it
     * owns the conversation's resolved language - and arrives as [languageKnown].
     */
    private fun isUnknownLanguageHeld(reason: HeldSend.HoldReason?, languageKnown: Boolean): Boolean =
        reason == HeldSend.HoldReason.UNKNOWN_LANGUAGE && languageKnown
}

/**
 * The hold sentences, kept apart because the gate prompt (parity row 10b) draws the same words: a
 * caller that needs one string must not construct a whole surface to get it.
 */
object HeldSendText {

    /** [R.string.tulkki_hold_failed] when nothing else names the reason. */
    @JvmStatic
    fun holdMessage(context: Context, reason: HeldSend.HoldReason?): String =
        context.getString(holdMessageRes(reason))

    private fun holdMessageRes(reason: HeldSend.HoldReason?): Int =
        when (reason) {
            HeldSend.HoldReason.UNKNOWN_LANGUAGE -> R.string.tulkki_hold_unknown_language
            HeldSend.HoldReason.NO_KEY -> R.string.tulkki_hold_no_key
            HeldSend.HoldReason.CAP_REACHED -> R.string.tulkki_hold_cap_reached
            HeldSend.HoldReason.NO_CREDIT -> R.string.tulkki_hold_no_credit
            HeldSend.HoldReason.UNREACHABLE -> R.string.tulkki_hold_unreachable
            // Not "DeepSeek answered something we did not like": the key itself is the problem.
            HeldSend.HoldReason.REJECTED_KEY -> R.string.tulkki_hold_rejected_key
            // Nothing failed: the check accepted the answer on doubt and the send waits for the
            // owner's tap, so the bar may not say DeepSeek could not translate it.
            HeldSend.HoldReason.DOUBT -> R.string.tulkki_hold_doubt
            else -> R.string.tulkki_hold_failed
        }
}
