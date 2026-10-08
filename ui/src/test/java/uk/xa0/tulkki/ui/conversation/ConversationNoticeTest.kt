package uk.xa0.tulkki.ui.conversation

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.ui.R

/**
 * Item 17's decision one, as the rule the screen has no branch for: which banner a conversation shows.
 *
 * <p>The cells pin the four answers the tree's resting bar gave, and the one place this rule reads past
 * the tree: the no-key sentence now carries the settings screen as its fix, because decision one asks
 * for the fix inline where the tree's bar offered none.
 */
class ConversationNoticeTest {

    @Test
    fun offDrawsNoBanner() {
        Assert.assertNull(
            "off, the chip is gone and this line would name the same pair",
            ConversationNotice.of(interpreting = false, languageKnown = false, keyConfigured = true),
        )
    }

    @Test
    fun aKnownLanguageIsNotNews() {
        Assert.assertNull(
            "the chip carries it, and a bar that said it again would be noise",
            ConversationNotice.of(interpreting = true, languageKnown = true, keyConfigured = true),
        )
    }

    @Test
    fun anUnknownLanguageWithAKeyOffersThePicker() {
        val notice = ConversationNotice.of(interpreting = true, languageKnown = false, keyConfigured = true)
        Assert.assertNotNull(notice)
        Assert.assertEquals(R.string.tulkki_state_unknown_language, notice!!.words)
        Assert.assertEquals(listOf(NoticeAction.CHANGE_LANGUAGE), notice.actions)
        Assert.assertEquals(R.string.tulkki_language_change, notice.actions.first().label)
    }

    @Test
    fun anUnknownLanguageWithoutAKeyOffersTheSettings() {
        val notice = ConversationNotice.of(interpreting = true, languageKnown = false, keyConfigured = false)
        Assert.assertNotNull(notice)
        Assert.assertEquals(R.string.tulkki_state_no_key, notice!!.words)
        Assert.assertEquals(
            "the tree's bar had no action here; decision one asks for the fix inline",
            listOf(NoticeAction.OPEN_SETTINGS),
            notice.actions,
        )
        Assert.assertEquals(R.string.tulkki_usage_open_settings, notice.actions.first().label)
    }

    @Test
    fun failuresAreWorthASentenceAndASecondWayIn() {
        // A known language silences the banner - the chip carries it - unless something failed, and
        // then the sentence is the failure's own and the link goes to the filtered list.
        val notice = ConversationNotice.of(interpreting = true, languageKnown = true, keyConfigured = true, failures = true)
        Assert.assertNotNull(notice)
        Assert.assertEquals(R.string.tulkki_state_failed, notice!!.words)
        Assert.assertEquals(listOf(NoticeAction.SEE_FAILURES), notice.actions)
        Assert.assertEquals(R.string.tulkki_failures, notice.actions.first().label)
    }

    @Test
    fun theFailuresLinkFollowsTheFixRatherThanReplacingIt() {
        val notice =
            ConversationNotice.of(interpreting = true, languageKnown = false, keyConfigured = true, failures = true)
        Assert.assertEquals(
            "the fix the state needs comes first, and the diagnostic way in after it",
            listOf(NoticeAction.CHANGE_LANGUAGE, NoticeAction.SEE_FAILURES),
            notice!!.actions,
        )
        Assert.assertEquals(
            "and a conversation with no failures does not offer the list at all",
            listOf(NoticeAction.OPEN_SETTINGS),
            ConversationNotice.of(interpreting = true, languageKnown = false, keyConfigured = false)!!.actions,
        )
    }

    @Test
    fun theWordsAreAStateAtRestAndNeverAFailedSend() {
        // `strings.xml` says why: the bar is "a statement about the app, not about a send that happened,
        // so it must not say 'Not sent': nothing has been sent or tried."
        val noKey = ConversationNotice.of(interpreting = true, languageKnown = false, keyConfigured = false)!!
        val unknown = ConversationNotice.of(interpreting = true, languageKnown = false, keyConfigured = true)!!
        Assert.assertNotEquals(R.string.tulkki_hold_no_key, noKey.words)
        Assert.assertNotEquals(R.string.tulkki_hold_unknown_language, unknown.words)
    }
}
