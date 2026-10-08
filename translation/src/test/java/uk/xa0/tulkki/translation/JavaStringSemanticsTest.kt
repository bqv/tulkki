package uk.xa0.tulkki.translation

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Java text semantics the Kotlin port had to keep, pinned site by site.
 *
 * <p>This is the port's own reading of `docs/MIGRATION.md` "Design: Kotlin" §5.3: every semantic difference is decided
 * rather than inherited, and the decision here is "keep what Java did" for the sites where Kotlin's
 * standard library would quietly answer differently. Each test names one site and one observable
 * difference, so a later change of mind (the per-site fixes of `S6-6`) fails here and has to be
 * argued instead of slipping through.
 *
 * <ul>
 *   <li><code>String.trim()</code> removes every character at or below U+0020; Kotlin's
 *       <code>trim()</code> removes all Unicode whitespace, so a non-breaking space (U+00A0) is
 *       stripped by Kotlin and kept by Java.
 *   <li><code>Character.isWhitespace</code> is false for U+00A0 in Java; Kotlin's
 *       <code>Char.isWhitespace()</code> is <code>isWhitespace || isSpaceChar</code>, so it is true.
 *   <li><code>String.toLowerCase()</code> folds with the <em>default</em> locale; Kotlin's
 *       <code>lowercase()</code> folds with <code>Locale.ROOT</code>, and the two disagree on "I" in
 *       Turkish.
 * </ul>
 *
 * <p>NBSP is written as an escape in every literal so the test is readable in any editor.
 */
class JavaStringSemanticsTest {

    /** A non-breaking space: the character every difference below turns on. */
    private val NBSP = "\u00A0"

    /** The dotless i Turkish produces for "I", for the default-locale cache-key test. */
    private val DOTLESS_I = "\u0131"

    @After
    fun tearDown() {
        LanguageCheck.useReaders(null)
    }

    @Test
    fun translationDecisionKeepsJavasTrim() {
        // Java's trim leaves the trailing NBSP, so the body still contains a space, so it is a text
        // that happens to contain a URI rather than a bare link, so it has language. Kotlin's trim
        // would leave "https://" - a link - and the message would never be translated.
        assertTrue(TranslationDecision.hasLanguage("https:// " + NBSP, null))
        // And the link itself is still refused, NBSP or not.
        assertFalse(TranslationDecision.hasLanguage("https://" + NBSP, null))
    }

    @Test
    fun pingKeepsJavasTrim() {
        // The body is "Helsinki" padded with NBSP. Java's trim does not remove it, so the whole-body
        // comparison against the room's name fails and the message is ordinary text - translated,
        // which is the documented failure direction. Kotlin's trim would call it the room's own name
        // and skip it silently.
        assertFalse(Ping.isPing(NBSP + "Helsinki" + NBSP, "Helsinki"))
    }

    @Test
    fun suggestionIsTheDraftKeepsJavasTrim() {
        // The model answered "Hallo" for a draft padded with NBSP: those are not the same text to
        // Java, so the gate stands and the refusal holds. Kotlin's trim would read them as equal and
        // stand the gate down.
        assertFalse(ComposerGate.suggestionIsTheDraft(NBSP + "Hallo", "Hallo"))
    }

    @Test
    fun comparisonFormKeepsJavasWhitespace() {
        // Character.isWhitespace is false for NBSP, so the leading one is a character to Java and is
        // kept; the trailing one is dropped by the trailing-punctuation walk. Kotlin's
        // Char.isWhitespace() would collapse the leading one away and return "a".
        assertEquals(NBSP + "a", ComposerGate.comparisonForm(NBSP + "a" + NBSP))
    }

    @Test
    fun normalizeKeepsJavasTrim() {
        // A code padded with NBSP is not the same code to Java: it survives the trim, is not empty
        // and is not "und", so it comes back as itself. Kotlin's trim would fold it to "fi".
        assertEquals(NBSP + "fi" + NBSP, ComposerGate.normalize(NBSP + "fi" + NBSP))
        // ... and through ConversationLanguage, which stores whatever the one normaliser returns.
        assertEquals(
                NBSP + "de" + NBSP, ConversationLanguage.resolve(NBSP + "de" + NBSP, null).code())
    }

    @Test
    fun scriptReadingNormalizeKeepsJavasTrim() {
        assertEquals(NBSP + "fi" + NBSP, ScriptReading.normalize(NBSP + "fi" + NBSP))
    }

    @Test
    fun languageCheckKeepsJavasTrimOnAnAnswer() {
        // An answer of one NBSP is not empty to Java, so the check carries on; with no readers it
        // finds nothing to distrust and the answer stands. Kotlin's trim would call it empty and
        // cover the message as NOTHING_CAME_BACK.
        LanguageCheck.useReaders(listOf())
        assertFalse(LanguageCheck.of("Hei", NBSP, "fi", "fi", "fi").failed())
        // A genuinely empty answer still fails, so the test above is about NBSP and not about the
        // empty-answer check being broken.
        assertTrue(LanguageCheck.of("Hei", "", "fi", "fi", "fi").failed())
    }

    @Test
    fun cacheKeyKeepsJavasDefaultLocale() {
        // String.toLowerCase() folds with the default locale. In Turkish "FI" folds to "fı", which is
        // a different cache key from "fi" - two keys for one language, which is the Java behaviour
        // this translation keeps. Kotlin's lowercase() would fold with Locale.ROOT and make the two
        // keys equal.
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertNotEquals(CacheKey.of("merhaba", "FI"), CacheKey.of("merhaba", "fi"))
            assertEquals(CacheKey.of("merhaba", "FI"), CacheKey.of("merhaba", "f" + DOTLESS_I))
        } finally {
            Locale.setDefault(before)
        }
    }
}
