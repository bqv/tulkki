package uk.xa0.tulkki.xmpp.refs

import uk.xa0.tulkki.libs.Jid

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.Conversational`, the interface
 * `Conversation` and `StubConversation` both implement.
 *
 * Declared in the island and implemented the cheapest way the brief names (§2.1): `:data`'s own
 * interface `Conversational` **extends** this one, so every implementor - the real
 * conversation and the placeholder alike - is one without an `implements` clause of its own. Legal
 * because `:data.allow` names `:xmpp`.
 *
 * It is the parameter type of `AccountRef`'s conference operations rather than
 * `ConversationRef`, and that is deliberate: the island's old code asked
 * `Set<Conversation>.contains(conversation)` with a `Conversational` in hand, which
 * answers `false` for a placeholder instead of throwing. Typing the port over
 * `ConversationalRef` keeps that behaviour; typing it over a conversation-only ref would force
 * a cast at that call site and turn a `false` into a `ClassCastException`.
 *
 * **Two members, added by part 12**, and the reason is the same invariance that put
 * the parameter here: `MessageRef.getConversation()` cannot answer `ConversationRef` -
 * the model's own accessor returns `Conversational`, which is not a `ConversationRef` -
 * so it answers this ref, and `MessageParser` then reads a uuid and an account through it. Both are
 * declarations the model's `Conversational` already carries, so `Conversation` and
 * `StubConversation` satisfy them with no edit at all; `ConversationRef.getAccount()`, which
 * part 11 declared for `PresenceParser`, is **moved up here** rather than left duplicated, because
 * this is the type that owns it.
 *
 * Where a `:ui` file has to name this type, it must write it **fully qualified in the
 * signature and import nothing** - an `import` of an island type in a `:ui` file is one more
 * `ui-reaches-island` site, decided before `allow` is consulted (rounds 151/161, pair
 * 11's four `extends` clauses). Do not "tidy" one into an import.
 */
interface ConversationalRef {

    /** Return-position drag: the model answers `Account`, which implements `AccountRef`. */
    fun getAccount(): AccountRef?

    fun getUuid(): String?

    /**
     * Tulkki: part 16 - the two the connection service reads through a message's conversation
     * (`message.getConversation().getJid()`, `.getMode()`). Both are declarations the model's
     * `Conversational` already carries, so `Conversation` and `StubConversation` satisfy them
     * with no edit at all.
     */
    fun getJid(): Jid?

    fun getMode(): Int

    companion object {
        /**
         * Tulkki: `Conversational.MODE_MULTI`, copied - a compile-time `int` with no identity, exactly
         * like `ContactRef.OptionsRef`'s bits. It lives **here** rather than on
         * `ConversationRef` because this is the type that owns it in the model, and the island's
         * six `getMode() == MODE_MULTI` tests read it through whichever of the two names is in hand.
         */
        @JvmField
        val MODE_MULTI: Int = 1

        /**
         * Tulkki: `Conversational.MODE_SINGLE`, copied for the same reason as [MODE_MULTI] and
         * added by part 13, when `XmppConnectionService` became the first island file to test for the
         * single mode rather than the multi one (`existing.getMode() == Conversational.MODE_SINGLE`).
         */
        @JvmField
        val MODE_SINGLE: Int = 0

    }
}
