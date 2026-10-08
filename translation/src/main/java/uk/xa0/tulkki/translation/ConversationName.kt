package uk.xa0.tulkki.translation

import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational

/**
 * The name a conversation is shown by, read in one place so the bare-name rule has one source.
 *
 * <p>That name is `Conversation.getName()`, for both shapes of conversation: a MUC room's
 * name (upstream falls back from there to its subject, then a bookmark name, then a name generated
 * from the participants) and, in a 1:1 chat, the contact's display name. It is the string the owner
 * sees in the conversation list, so a message that is exactly it is the conversation's own name as
 * far as they are concerned.
 *
 * <p>Where upstream has no display name of its own, `getName()` does not go blank: it falls
 * back to the JID's own label (a room's local part, or the whole JID for a stranger) or to the
 * contact's JID-derived name. That fallback is upstream's and is kept deliberately rather than
 * second-guessed here - inventing a second source of truth, such as "only a real MUC name counts",
 * would make the rule fire in a place the owner cannot see and would disagree with the name on the
 * screen. A name that is blank even so matches nothing, which [Ping] decides; this class never
 * invents one.
 *
 * <p>Only a real `Conversation` has a name: a `Conversational` is what a
 * `Message` holds, and the JVM tests' own fakes are not a `Conversation`. Those read as
 * "no name", which turns the name half of the rule off rather than guessing.
 *
 * <p>`getName()` is a Kotlin-declared getter (`Conversation.kt`'s `override fun getName()`), so
 * Kotlin synthesises no `name` property over it; the call stays a call.
 */
object ConversationName {

    /**
     * The name the interface shows for this conversation, or `null` when there is none to
     * read. Read-only and total: anything that is not a real conversation, or has no name, is
     * simply `null`.
     */
    @JvmStatic
    fun of(conversation: Conversational?): String? {
        if (conversation !is Conversation) {
            return null
        }
        // Declared `CharSequence` by Kotlin, but the Java this replaces read a platform type and
        // kept the null branch, so it is kept here too.
        val name: CharSequence? = conversation.getName()
        return name?.toString()
    }
}
