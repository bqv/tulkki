package uk.xa0.tulkki.ui.conversation

import androidx.annotation.StringRes
import uk.xa0.tulkki.ui.R

/**
 * The fix a notice offers on the spot, which is the half of item 17's decision one that makes the
 * banner worth drawing: "a conversation-level banner names the reason with the fix inline (add a key,
 * raise the cap, retry now)".
 *
 * <p>The label is the string's own, as `ConversationMenu.label` is for the list's actions: the enum is
 * the set of nouns the host answers to, and the words are the app's.
 */
enum class NoticeAction(@StringRes val label: Int) {
    /** The conversation has no language yet: the fix is the picker, and the tree's label was "Change". */
    CHANGE_LANGUAGE(R.string.tulkki_language_change),

    /** No key, or a refused one: the fix is Tulkki's own settings screen. */
    OPEN_SETTINGS(R.string.tulkki_usage_open_settings),

    /**
     * Item 17's decision four: the failures screen, **filtered to this conversation**. The label is the
     * screen's own name, so the way in reads like the place it goes.
     */
    SEE_FAILURES(R.string.tulkki_failures),
}

/**
 * One line of §3.6's `notices`: a statement about the app *at rest*, and the fix it offers.
 *
 * <p>**A statement at rest, never a report of a send.** The tree wrote this distinction down where the
 * words live (`strings.xml`): "The bar on opening a conversation is a statement about the app, not
 * about a send that happened, so it must not say 'Not sent': nothing has been sent or tried." So the
 * sentences come from `tulkki_state_*` and never from `tulkki_hold_*`, and a cell pins that.
 *
 * <p>[action] is `null` when the sentence itself is the whole answer; a notice with no action is still
 * a notice, and the tree drew the no-key line that way.
 */
data class UiNotice(
    /** The sentence, from the `tulkki_state_*` family - a state, not a failed send. */
    @StringRes val words: Int,
    /**
     * The fix offered inline, in the order they are drawn: the sentence's own fix first, and the
     * failures link after it when this conversation has failures to look at. An empty list is a notice
     * with nothing to do but read it, which the tree drew for the no-key line.
     */
    val actions: List<NoticeAction> = emptyList(),
)

/**
 * Item 17's decision one, as the rule: **which banner a conversation shows, if any**.
 *
 * <p>It is `ConversationFragment`'s own resting-bar chain
 * (`ui/src/main/java/uk/xa0/tulkki/ui/ConversationFragment.java:4820-4856`), lifted out of the
 * fragment so the banner has cells and the screen has no branch:
 *
 * <ul>
 *   <li>**the interpreter off draws nothing** - off, the chip is gone and this line names the same
 *       pair, so there is no line to draw ("no second half, no composer gate, no gloss, no covers, a
 *       plain client");
 *   <li>**a known language is not news** - the chip carries it, and a bar that said it again would be
 *       noise on every conversation the owner opens;
 *   <li>**an unknown language with a key** offers the picker, because that is the one thing that
 *       changes the state;
 *   <li>**an unknown language without a key** says there is no key: the tree drew it with no action,
 *       and decision one asks for the fix inline, so this rule offers the settings screen where the key
 *       is entered. That is the one place this rule deliberately reads past the tree, and it is
 *       recorded rather than quietly repaired.
 * </ul>
 */
object ConversationNotice {

    /**
     * The banner for a conversation, or `null` when there is nothing true to say.
     *
     * @param interpreting whether the pair is on: off draws no banner at all
     * @param languageKnown whether the conversation's own language is detected or set
     * @param keyConfigured whether a DeepSeek key is in the settings
     * @param failures whether this conversation has failures the failures screen would list - the
     *     link decision four asks for, and the one thing that makes the banner say something a known
     *     language would otherwise silence
     */
    @JvmStatic
    fun of(
        interpreting: Boolean,
        languageKnown: Boolean,
        keyConfigured: Boolean,
        failures: Boolean = false,
    ): UiNotice? =
        when {
            !interpreting -> null
            // The language is known and nothing failed: the chip carries the language and there is
            // nothing else true to say.
            languageKnown && !failures -> null
            languageKnown -> UiNotice(R.string.tulkki_state_failed, listOf(NoticeAction.SEE_FAILURES))
            keyConfigured ->
                UiNotice(
                    R.string.tulkki_state_unknown_language,
                    withFailures(NoticeAction.CHANGE_LANGUAGE, failures),
                )
            else -> UiNotice(R.string.tulkki_state_no_key, withFailures(NoticeAction.OPEN_SETTINGS, failures))
        }

    /** The sentence's own fix, and the failures link after it when there is one to offer. */
    private fun withFailures(fix: NoticeAction, failures: Boolean): List<NoticeAction> =
        if (failures) listOf(fix, NoticeAction.SEE_FAILURES) else listOf(fix)
}
