package uk.xa0.tulkki.data.model

import uk.xa0.tulkki.libs.Jid

/**
 * A conversation that is only an address: an account, a uuid, a JID and a mode.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **`jid` is nullable, and the constructor accepts the `null` two Java callers pass.**
 *    `PostsAdapter:613` builds `new StubConversation(account, "", null, 0)` and `MessageSearchTask`
 *    passes a real bare JID; a non-null Kotlin parameter would throw at construction where Java
 *    stored the `null`. [getJid] answers it nullable, as `Conversation.getJid()` does.
 * 2. **[getContact] keeps Java's dereference of the JID.** `Roster.getContact(jid: Jid)` declares a
 *    non-null parameter and return, so the `!!` throws exactly where Java's call into that Kotlin
 *    method did.
 * 3. **[getAccount] and [getUuid] stay non-null**: Java declared both, and the two construction
 *    sites pass a real account and a real uuid (`MessageSearchTask`'s conversation uuid,
 *    `PostsAdapter`'s empty string).
 * 4. **[canInferPresence] keeps Java's `contact != null` test.** The interface declares
 *    `getContact(): Contact`, so the branch is unreachable today; it is the Java shape rather than a
 *    live decision, kept rather than tidied.
 * 5. **No statics, so no `@JvmStatic`; nothing extends it**, so Kotlin's implicit `final` is Java's
 *    own shape.
 */
class StubConversation(
    private val account: Account,
    private val uuid: String,
    private val jid: Jid?,
    private val mode: Int,
) : Conversational {

    override fun getAccount(): Account = account

    override fun getContact(): Contact = account.getRoster().getContact(jid!!)

    override fun getJid(): Jid? = jid

    override fun getMode(): Int = mode

    override fun getUuid(): String = uuid

    override fun getEphemeralTimer(): Int = 0

    override fun canInferPresence(): Boolean {
        val contact = getContact()
        return contact != null && contact.canInferPresence()
    }
}
