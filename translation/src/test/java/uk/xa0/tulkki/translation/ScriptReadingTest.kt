package uk.xa0.tulkki.translation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The script reader: the one opinion in the check that is a fact rather than an estimate, and the
 * narrowness that makes it one.
 */
class ScriptReadingTest {

    @Test
    fun aWritingSystemOneLanguageUsesNamesIt() {
        assertEquals("el", ScriptReading.language("Καλημέρα, τι κάνεις σήμερα;"))
        assertEquals("ko", ScriptReading.language("안녕하세요, 오늘 뭐 해요?"))
        assertEquals("ja", ScriptReading.language("こんにちは、元気ですか？"))
        assertEquals("th", ScriptReading.language("สวัสดีครับ วันนี้เป็นอย่างไรบ้าง"))
        assertEquals("ta", ScriptReading.language("வணக்கம், இன்று எப்படி இருக்கிறீர்கள்?"))
    }

    @Test
    fun theScriptsThatWouldBeAGuessSayNothing() {
        // Cyrillic is six of the app's languages at once, Arabic is three, Devanagari is three,
        // Hebrew is two (Hebrew and Yiddish), and Han characters are Chinese and Japanese together.
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("Привет, как дела сегодня?"))
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("مرحبا، كيف حالك اليوم؟"))
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("नमस्ते, आज कैसे हैं आप?"))
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("שלום, מה שלומך היום?"))
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("你好，你今天怎么样？"))
    }

    @Test
    fun latinNamesNoLanguageBecauseItNamesTooMany() {
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("Hei, mitä kuuluu tänään?"))
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("Hallo, wie geht es dir heute?"))
    }

    @Test
    fun aQuotationInsideAnotherTextDoesNotDecideTheLanguage() {
        // A Finnish message with a Greek word in it is a Finnish message: the script has to cover
        // most of the letters, not merely appear.
        assertEquals(
                TextLanguage.UNKNOWN,
                ScriptReading.language("Hän sanoi \"Καλημέρα\" ja lähti töihin aikaisin."))
    }

    @Test
    fun digitsPunctuationAndEmojiAreNotLetters() {
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("1234 !!! 😀😀😀"))
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language(""))
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language(null))
        // Three letters are not a text with a script, they are a word.
        assertEquals(TextLanguage.UNKNOWN, ScriptReading.language("αβγ"))
    }
}
