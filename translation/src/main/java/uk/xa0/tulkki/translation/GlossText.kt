package uk.xa0.tulkki.translation

import java.util.ArrayList

/**
 * Which words in a message may be tapped for a gloss.
 *
 * <p>The text a gloss explains is the <strong>app-language text of the bubble</strong>: the
 * translation, or an original that needed none. Two things are therefore not glossable, and the rules
 * are here rather than at the call site so they can be tested:
 *
 * <ul>
 *   <li><strong>A covered bubble.</strong> It is showing a cover, and a tap on it already means
 *       "translate this one, now" - a second meaning for that tap would be a bug, not a feature. The
 *       rule is stated against [DisplayedBody] so the covered case cannot be glossed even by a
 *       caller that forgets to check.
 *   <li><strong>A concealed half.</strong> It has no text at all: `ConcealedText` draws its
 *       line into a bitmap and leaves the view empty, so there are no words there to find, by
 *       construction rather than by a check.
 * </ul>
 *
 * <p>Within glossable text, a token is a run of letters. That single rule already excludes
 * punctuation-only tokens, numbers, emoji and whitespace. A run that the offline detector is
 * <em>confident</em> is already in the study language is also skipped: a gloss in the same language
 * as the word explains nothing. The confidence threshold is the one the receive path uses for
 * "already the app language".
 *
 * <p><strong>With the interpreter off there are no tokens at all.</strong> A plain XMPP client has no
 * reading aid, and the token list is the whole of it: the renderer adds one invisible tap target per
 * token (`MessageAdapter.addGlossSpans`) and a tap target is the popup's only way in, so an
 * empty list is what removes the words, the spans and the taps together.
 *
 * <p>Pure Kotlin, so it is exercised by JVM unit tests. `Token`'s three values stay `@JvmField`s
 * because the Java callers read them as fields, its constructor is `internal` (package-private in the
 * Java, and nothing in Java constructs one), and the two blank tests keep Java's `trim()`.
 */
object GlossText {

    /** One tappable word, as a range in the text it was found in. */
    class Token internal constructor(
            @JvmField val word: String,
            @JvmField val start: Int,
            @JvmField val end: Int
    ) {

        override fun toString(): String = word + "[" + start + "," + end + ")"
    }

    /**
     * The most of a sentence a gloss is bought with. A chat message is a sentence or two; anything
     * past this is a wall of text, and the word is what the answer is about - the context is a hint,
     * not a corpus.
     */
    private const val MAX_SENTENCE = 240

    /**
     * The sentence the tapped word sits in, which is the context a gloss is bought with.
     *
     * <p>It is the stretch between the punctuation around the word - a full stop, a question or
     * exclamation mark, an ellipsis, a line break - so what comes back is the sentence as the reader
     * sees it and not a window of characters. Nothing here decides anything: it feeds the request and
     * the cache identity.
     *
     * @param text what the bubble is showing
     * @param start the word's first character
     * @param end the word's last character plus one
     * @return the sentence, trimmed; empty when there is no text or no sensible range
     */
    @JvmStatic
    fun sentence(text: CharSequence?, start: Int, end: Int): String {
        if (text == null) {
            return ""
        }
        val length = text.length
        if (start < 0 || end > length || start >= end) {
            return ""
        }
        var from = start
        while (from > 0 && !isSentenceBreak(text[from - 1])) {
            from--
        }
        var to = end
        while (to < length && !isSentenceBreak(text[to])) {
            to++
        }
        // Keep the mark that ended it, and anything closing the sentence behind it - a quote, a
        // bracket, a second question mark - so the context reads the way the reader saw it.
        while (to < length && isCloser(text[to])) {
            to++
        }
        if (to - from > MAX_SENTENCE) {
            // Keep the window around the word rather than the sentence's head: the context that
            // matters is the words next to it.
            val windowStart = maxOf(from, start - (MAX_SENTENCE / 2))
            from = windowStart
            to = minOf(to, windowStart + MAX_SENTENCE)
        }
        // Java's own `trim()` (`<= ' '`), as in the Java this replaces.
        return text.subSequence(from, to).toString().javaTrim()
    }

    /** A character that belongs to the sentence it follows rather than to the next one. */
    private fun isCloser(c: Char): Boolean =
            isSentenceBreak(c) ||
                    c == '"' ||
                    c == '\'' ||
                    c == '\u201d' ||
                    c == '\u2019' ||
                    c == ')' ||
                    c == ']' ||
                    c == '\u00bb'

    /** Where one sentence ends and the next begins. A comma is not a break: a clause is context. */
    private fun isSentenceBreak(c: Char): Boolean =
            c == '.' ||
                    c == '!' ||
                    c == '?' ||
                    c == '\u2026' ||
                    c == '\n' ||
                    c == '\r' ||
                    c == '\u3002' ||
                    c == '\uff01' ||
                    c == '\uff1f'

    /**
     * The tappable words of a displayed body, in order.
     *
     * @param displayed what the bubble is showing; a covered body yields nothing
     * @param studyLanguage the language the gloss would be in, or `null` when it is not known
     * @param interpreter the app's one derived setting; off yields nothing, however drawn the body is
     */
    @JvmStatic
    fun words(
            displayed: DisplayedBody?,
            studyLanguage: String?,
            interpreter: Interpreter?
    ): List<Token> {
        if (displayed == null || displayed.isBlurred()) {
            // Covered: the tap there is already spoken for.
            return emptyList()
        }
        return words(displayed.text(), studyLanguage, interpreter)
    }

    /**
     * The words of a piece of text.
     *
     * <p>For a caller that already knows the text is glossable - the renderer never reaches this for
     * a covered bubble, because it draws the cover and returns - and for the tests, which are about
     * the rule rather than about a bubble.
     *
     * <p>The off guard is here, at the bottom, and the bubble overload above routes through it: one
     * rule, asked once, so the two calls cannot answer differently about the mode.
     */
    @JvmStatic
    fun words(text: String?, studyLanguage: String?, interpreter: Interpreter?): List<Token> {
        val tokens = ArrayList<Token>()
        if (interpreter == null || !interpreter.enabled()) {
            // Off: no token, so no span, so no tap target, so no popup. The list is the whole of the
            // reading aid's surface, and a plain client has none of it.
            return tokens
        }
        if (text == null || text.isEmpty()) {
            return tokens
        }
        var start = -1
        for (i in 0..text.length) {
            val letter = i < text.length && Character.isLetter(text[i])
            if (letter && start < 0) {
                start = i
            } else if (!letter && start >= 0) {
                val word = text.substring(start, i)
                if (isWorthGlossing(word, studyLanguage)) {
                    tokens.add(Token(word, start, i))
                }
                start = -1
            }
        }
        return tokens
    }

    /**
     * Whether a run of letters is worth a lookup. A run that is confidently the study language is
     * not: the gloss would be in the language the word is already in.
     */
    private fun isWorthGlossing(word: String, studyLanguage: String?): Boolean {
        if (word.isEmpty()) {
            return false
        }
        if (studyLanguage == null || studyLanguage.javaTrim().isEmpty()) {
            return true
        }
        val guess = TextLanguage.detect(word)
        return !guess.isProbably(
                studyLanguage.javaTrim().lowercase(java.util.Locale.ROOT),
                TextLanguage.TRUSTWORTHY_CONFIDENCE)
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
