package uk.xa0.tulkki.translation

/**
 * Whether the doubt hold is in force for one conversation: the owner's stored answer combined with
 * the wording this build ships.
 *
 * <p>This is the one place that answers the question, and it answers it as a <em>derived setting</em>
 * rather than as a second code path. The send path has one hold decision
 * (`OutgoingTranslation.decide`) which asks this; nothing else consults the switch, and the
 * display path cannot - a received message still draws a probable translation as the translation it
 * probably is, whatever a conversation stored, because doubt was never evidence for drawing
 * (docs/MIGRATION.md item 16, and `AGENTS.md`'s "fails on evidence, never on doubt").
 *
 * <p><strong>The stored value is a tri-state, and the third answer is not a spelling of "off".</strong>
 * `NULL` is "this conversation never chose", and it follows [SHIPPED_DEFAULT]; `0`
 * is the owner turning the hold off for this room and `1` is the owner turning it explicitly on.
 * The column and the model store exactly that and resolve nothing (`ConversationDoubtHoldTest`);
 * resolving it here is what keeps "a fresh install holds doubtful answers" a fact about the build
 * rather than a number somebody has to write into every row.
 *
 * <p><strong>The default is on, and it is hardcoded.</strong> [SHIPPED_DEFAULT] is
 * `true` because a switch the owner has to remember to set leaves the bug in every room they
 * forgot: a doubtful send that goes out untranslated is the complaint this hold exists for, and the
 * owner's override is the exception where a stray untranslated line is harmless. There is no setting
 * behind this default and no row to migrate - a fresh install reads `NULL` everywhere and
 * therefore holds.
 *
 * <p><strong>What the override cannot cover is the echo.</strong> The switch's scope is a room "where a
 * stray untranslated line is harmless", and an answer that is the input handed back <em>is</em> that
 * line - so the exception lives here, beside the rule, rather than at the call site. See
 * [holds], which is the question the send path actually asks.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests. Both parameters are
 * nullable, exactly as the Java's `Boolean`/enum platform types were: a Java caller passes `null` for
 * "never chose" and for "no doubt at all".
 */
object DoubtHold {

    /**
     * What a conversation that never chose gets: the hold is <strong>on</strong>.
     *
     * <p>A constant rather than a private `true` so the decision is nameable: the value the
     * owner's override is an exception to belongs in one place, and a test pins it rather than
     * trusting the expression below.
     */
    const val SHIPPED_DEFAULT = true

    /**
     * Whether the owner's override is in force for this conversation - the switch's own reading, and
     * only half of the hold decision. [holds] is what the send path asks.
     *
     * @param perConversation the conversation's stored answer: `null` for "never chose",
     *     `TRUE` for the owner's explicit on, `FALSE` for the owner's off
     * @return the derived setting - [SHIPPED_DEFAULT] when the conversation never chose,
     *     otherwise the owner's own answer
     */
    @JvmStatic
    fun inForce(perConversation: Boolean?): Boolean = perConversation ?: SHIPPED_DEFAULT

    /**
     * Whether this doubt is held for this conversation rather than sent - the send path's one question.
     *
     * <p><strong>An echo is held whatever the switch says.</strong> The override reaches the
     * doubtful-<em>language</em> kind - a probable translation the check could not vouch for, which is
     * a real translation and may go as held where the owner says so - and never
     * [LanguageCheck.Doubt.NOTHING_TRANSLATED]. Item 16's own sentence is the reason: an answer
     * that is an echo or empty "(nothing was translated) must re-translate on the tap, because sending
     * the held answer would send the original, which is the bug". A stray untranslated line is exactly
     * what "nothing is sent untranslated" forbids, so the switch's own scope - "where a stray
     * untranslated line is harmless" - cannot cover it. Keeping the exception here, in the same place
     * as the rule, is what makes it impossible to move the switch without moving the reason.
     */
    @JvmStatic
    fun holds(doubt: LanguageCheck.Doubt?, perConversation: Boolean?): Boolean {
        if (doubt == LanguageCheck.Doubt.NOTHING_TRANSLATED) {
            return true
        }
        return inForce(perConversation)
    }
}
