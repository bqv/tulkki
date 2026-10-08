package uk.xa0.tulkki.translation

import java.util.regex.Pattern

/**
 * A body with no language of its own: a nudge, or the conversation's own bare name.
 *
 * <p>Two shapes, one answer. A *ping* is a whole-body name-and-colon - `"Matti: "` - which is how
 * the owner nudges someone in a chat. A *bare name* is a whole body that is exactly the name of the
 * conversation it was sent in: in a room called Helsinki, the message `"Helsinki"`. Neither is
 * prose, so neither has a language, and both get the same treatment everywhere below: never
 * translated, never covered, never held by the gate and never read for a language.
 *
 * <p>Why it has to be one predicate: a name has no language to detect, and a detector asked anyway
 * answers with a language, because a single token is exactly what the profiles are worst at.
 * `"Matti"` comes back Maltese at 0.96 - the reading that found the conversation-language bug - so a
 * bare name that reached detection would fix a conversation to Maltese and then send everything the
 * owner writes in Maltese.
 *
 * <p>Called from every place that answers "is there language here": a ping is on
 * [TranslationDecision.hasLanguage]'s no-language side of that line, which is the one rule the
 * receive path and [ComposerGate.verdict] both ask. It is also skipped by [ConversationLanguage.read]
 * and by [LanguageSample], which read languages rather than deciding translatability. Every one of
 * those callers passes the name the interface shows, so the four cannot drift apart.
 *
 * <p>The name is matched *whole* and case-insensitively, and only ever against [ConversationName] -
 * the one name the owner can see. `"Helsinki is quiet today"` is prose in a room called Helsinki, and
 * so is a name plus a second name; `"Matti"` in a room called Helsinki is an ordinary short message
 * and is translated like one. There is deliberately no "any single capitalised word is a name" rule:
 * that would exempt real one-word messages from translation and from detection, which is the failure
 * this class exists to avoid.
 *
 * <p>Pure Kotlin, no Android types, so it is exercised by JVM unit tests.
 */
object Ping {

    /**
     * One token, a colon, then whitespace only - with optional whitespace around the whole thing.
     *
     * <p>The token may not contain whitespace or a second colon: `"Matti: hello"` is prose that
     * happens to open with a name, and `"12:30"` is a time. It may not be empty either, so a bare
     * `":"` is not a ping. Everything that is not one of those is not a ping, which is the direction
     * to fail in: an ordinary message wrongly regarded as a nudge would escape translation silently,
     * while a nudge wrongly regarded as ordinary text is merely translated.
     *
     * <p>The nick has no whitespace, so this shape covers `"Helsinki: "` in a room called Helsinki
     * but not a colon after a multi-word room name. That is the shape's own edge, not the name
     * rule's: a multi-word name is still matched whole, without the colon.
     */
    private val SHAPE = Pattern.compile("\\A\\s*[^\\s:]+:\\s*\\z")

    /**
     * True when the whole body is a ping or the conversation's own name. `false` for everything else,
     * including anything this is not sure about - the failure direction is "treated as ordinary
     * text", so a missing or blank name simply turns the name half off and the body is translated
     * like any other.
     *
     * @param body the message as it arrived or as it was typed
     * @param conversationName the name the interface shows for the conversation this body was sent
     *     in ([ConversationName]), or `null` when the caller has none
     */
    @JvmStatic
    fun isPing(body: String?, conversationName: String?): Boolean {
        if (body == null) {
            return false
        }
        return SHAPE.matcher(body).matches() || isTheConversationName(body, conversationName)
    }

    /**
     * The whole body, trimmed, against the whole name, trimmed, case-insensitively.
     *
     * <p>A null or blank name matches nothing, which is the safe direction: with no name to compare
     * against, a body is ordinary text. Note that "blank" is decided here rather than at the name's
     * source, so a conversation whose name is whitespace matches nothing either.
     */
    private fun isTheConversationName(body: String, conversationName: String?): Boolean {
        if (conversationName == null) {
            return false
        }
        val name = conversationName.javaTrim()
        if (name.isEmpty()) {
            return false
        }
        return name.equals(body.javaTrim(), ignoreCase = true)
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>Kotlin's `trim()` removes Unicode whitespace instead, and here that decides whether a message
 * is the room's own bare name: a body of a non-breaking space around "Helsinki" is still "Helsinki"
 * to Kotlin and is not to Java. The Java this replaces used `String.trim()`, so this does too.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
