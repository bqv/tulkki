package uk.xa0.tulkki.data.model

import org.junit.Assert.assertNull
import org.junit.Test
import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: a conversation that was never given an account still answers Java's own `null` from
 * `getAccount()` and `getAccountUuid()`, and not an NPE.
 *
 * The two accessors are nullable in Kotlin because eight Java call sites *receive* that null and
 * test it (`XmppActivity:935`, `:1056`, `MessageAdapter:3030`, `UIHelper:211`,
 * `ConversationListFragment:634`, `ShareWithActivity:421`, `XmppTulkkiHost:1086`) and because
 * `accountUuid` is a nullable column (`schema-75.sql`). A narrower signature cannot be rescued by a
 * cast: `account as Account` looks like a null-passing `checkcast`, but K2 2.3.21 inserts the null
 * check and both cases below NPE'd when the port tried it. That is the evidence for the nullable
 * signatures, so the two cases are pinned here rather than argued in a comment.
 */
class ConversationNullAccountTest {

    private fun conversationWithoutAnAccount(): Conversation {
        return Conversation(
            "00000000-0000-0000-0000-000000000000",
            "a name",
            null,
            null,
            Jid.ofOrInvalid("test@example.com"),
            0L,
            Conversation.STATUS_AVAILABLE,
            Conversation.MODE_SINGLE,
            "",
        )
    }

    @Test
    fun aConversationWithoutAnAccountStillAnswersJavaNull() {
        val conversation = conversationWithoutAnAccount()
        assertNull(conversation.getAccount())
    }

    @Test
    fun aRowWithoutAnAccountUuidStillAnswersJavaNull() {
        val conversation = conversationWithoutAnAccount()
        assertNull(conversation.getAccountUuid())
    }
}
