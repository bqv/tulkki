package uk.xa0.tulkki.ui.failures

import uk.xa0.tulkki.translation.HeldSend
import uk.xa0.tulkki.translation.TranslationFailures

/**
 * The failures screen's state, docs/MIGRATION.md "Design: the Compose UI" §3.2.
 *
 * <p>Three readings and no fourth. [blocker] is the question the send path already asks before it
 * holds a message - [HeldSend.localReason]'s answer about the app language - drawn so that an empty
 * list cannot read as "nothing is wrong" while every received message is covered. [waiting] is the
 * queue's own count of the messages it still owes a translation. [rows] is §3.2's list, and it is the
 * one member the declaration had to be read against the tree to get right.
 *
 * <p><strong>[rows] is three-valued, and `null` is a state of its own.</strong> §3.2 declares
 * `rows: List<UiFailure>`; the tree reads the rows on an executor and draws the two live lines at
 * once, so between the screen appearing and the read landing there is no list yet - and an empty list
 * there would draw `tulkki_failures_none`, saying "No translation has failed" while the read is still
 * in flight. `null` is that state, `emptyList()` is "the read landed and nothing has failed", and a
 * non-empty list is the rows. It is the same three values `TokenPrices.Selection.listed` uses to
 * separate its two extra sentences (§3.0.1 #4), and a read that throws leaves this at `null` rather
 * than at a lie.
 *
 * <p>A row is [TranslationFailures.Failure] itself - the value `:ui`'s ledger already names `UiFailure`
 * - and not a wrapper: §3.2 is reuse, and a second spelling of a failure would be a second answer to
 * what failed. The one thing a row cannot carry is the conversation's own *name*, which is what the row
 * identifies the failure by: only the running service knows it, so the screen takes it as a parameter
 * (`conversationLabel`) exactly as the ledger takes its `enabled` and `hasKey`.
 *
 * <p>§3.2's fourth member, `notice`, is deliberately not here. Its sibling in §3.0 turned out to be a
 * read's own words, one per source (§3.0.1 #5, `LedgerNotices`); this screen's one read either hands
 * back rows or throws, and the tree's screen has no sentence for the second case - so a `notice` would
 * be a member with no source and a string with no design behind it. What carries the same fact is the
 * `null` [rows] above: the list is not there yet, and nothing is claimed about it.
 */
data class FailuresState(
    /** Why nothing is being translated at all right now, or `null` when nothing is. */
    val blocker: HeldSend.HoldReason?,
    /** How many messages the queue still owes a translation; `<= 0` draws no line. */
    val waiting: Int,
    /** The failures, newest first - or `null` while the read that produces them is in flight. */
    val rows: List<TranslationFailures.Failure>?,
    /**
     * The conversation this screen was opened **for**, by local uuid, or `null` for all of them.
     *
     * <p>It is item 17's decision four - the failures screen "reachable from the covered message,
     * filtered to that conversation" - and it arrives already filtered from the conversation: the
     * banner that links here names this conversation, so the screen draws that conversation's rows and
     * does not own a filter control. The uuid and not a name: the screen identifies a failure by the
     * conversation it came from, and two conversations may share a name.
     */
    val conversation: String? = null,
) {

    /**
     * The rows the screen draws: the read's own list, narrowed to [conversation] when there is one.
     *
     * <p>A failure whose `conversationUuid` is `null` - a conversation that is gone - is not this
     * conversation's and is not drawn under a filter; unfiltered it is, because the diagnostic list is
     * where such a row is recognisable at all.
     */
    val visible: List<TranslationFailures.Failure>
        get() = rows.orEmpty().filter { conversation == null || it.conversationUuid == conversation }
}
