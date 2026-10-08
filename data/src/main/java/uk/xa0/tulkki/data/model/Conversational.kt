package uk.xa0.tulkki.data.model

import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.ConversationalRef

/**
 * The model's view of a conversation - the real `Conversation` and the placeholder `StubConversation`
 * alike - and the two mode constants that say which kind it is.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **The two mode constants are companion `const val`s.** `Conversation.kt:1632-1633` and
 *    `Message.kt:583`, `:687` read `Conversational.MODE_MULTI`/`MODE_SINGLE`, the first pair inside
 *    `const val` initialisers, so they must be compile-time constants. `ConversationalRef` (Java)
 *    already declares both, and `:ui`'s `MessageAdapter:3495`, `ConversationMenuConfigurator:77` and
 *    `ConversationFragment:3458` name `Conversational.MODE_*`; a Java reader resolves the declared
 *    field or the inherited one, and they are the same value either way.
 * 2. **The contract is nullable, and that is the decision earlier port work already recorded.**
 *    `Conversation.kt` declares `override fun getAccount(): Account?` and `getJid(): Jid?` "exactly
 *    as wide as Java's platform type - never narrower", and `getUuid()` is inherited from
 *    `AbstractEntity`, whose column is nullable. Java's unannotated getters promised Kotlin nothing,
 *    so the `:app` callers that read them as non-null were leaning on an interop accident; they now
 *    handle the `null` Java could always return.
 * 3. **The four members `ConversationalRef` already declares carry `override`** where Java's
 *    interface merely re-declared them; the other three are this interface's own.
 * 4. **No `@JvmStatic`.** An interface constant is already a Java static field, and the rest are
 *    instance methods.
 * 5. **`StubConversation` and the two `:app` test fakes implement this shape unchanged**:
 *    `ReplySpanReadTest.FakeConversation` and `TranslationHooksTest.FakeConversation` implement the
 *    same seven members and read `Conversational.MODE_SINGLE`.
 */
interface Conversational : ConversationalRef {

    override fun getAccount(): Account?

    fun getContact(): Contact

    override fun getJid(): Jid?

    override fun getMode(): Int

    override fun getUuid(): String?

    fun getEphemeralTimer(): Int

    fun canInferPresence(): Boolean

    companion object {
        const val MODE_MULTI = 1

        const val MODE_SINGLE = 0
    }
}
