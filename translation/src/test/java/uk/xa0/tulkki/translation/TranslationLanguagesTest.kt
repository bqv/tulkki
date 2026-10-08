package uk.xa0.tulkki.translation

import com.optimaize.langdetect.profiles.LanguageProfileReader
import java.util.HashSet
import java.util.Locale
import java.util.TreeSet
import org.junit.Assert
import org.junit.Test

/**
 * One list of selectable languages, shared by the settings screen and the conversation's picker. A
 * second list is exactly what this class exists to prevent, and since the post-translation check asks
 * three identifiers about a text, the list is what all three of them cover: a language only one
 * detector can name would make the check's agreement meaningless for it.
 *
 * <p>That claim used to be a comment rather than a test, and the list had drifted: it held 18 codes
 * while the bundled detector had profiles for 69 languages. {@link
 * #itIsExactlyWhatAllThreeDetectorsCover()} now reads all three libraries themselves, so the list
 * cannot drift again without failing here - and it is where a detector upgrade announces itself.
 */
class TranslationLanguagesTest {

    @Test
    fun finnishIsFirstInThePicker() {
        Assert.assertEquals(
                "the list opens on the language this app was built around",
                "fi",
                TranslationLanguages.codes().get(0))
    }

    @Test
    fun everyLanguageIsDistinct() {
        Assert.assertEquals(
                TranslationLanguages.codes().size,
                HashSet(TranslationLanguages.codes()).size)
    }

    @Test
    fun theLanguagesTheAppActuallyUsesAreInTheList() {
        for (code in arrayOf("fi", "en", "de", "sv", "et", "ru", "no", "zh")) {
            Assert.assertTrue(code, TranslationLanguages.isKnown(code))
        }
    }

    /**
     * The two languages that are in the list because a detector spells them differently, and the one
     * spelling that is deliberately not aliased.
     */
    @Test
    fun aDetectorsOwnSpellingIsTranslatedIntoTheApps() {
        Assert.assertEquals("zh", TranslationLanguages.appCode("cmn"))
        Assert.assertEquals("zh", TranslationLanguages.appCode("zho"))
        Assert.assertEquals("no", TranslationLanguages.appCode("nb"))
        Assert.assertEquals("no", TranslationLanguages.appCode("nob"))
        Assert.assertEquals("no", TranslationLanguages.appCode("NOR"))
        Assert.assertEquals("de", TranslationLanguages.appCode("de"))
        Assert.assertNull(TranslationLanguages.appCode(null))
        Assert.assertNull(TranslationLanguages.appCode(""))
        // Nynorsk is its own written language, not the one Norwegian this app offers. This method
        // only applies the aliases; turning the platform's three-letter codes into two-letter ones is
        // the reader's own step (OpenNlpReading.iso1), so "nno" is left as the code it came in as.
        Assert.assertEquals("nno", TranslationLanguages.appCode("nno"))
        Assert.assertNotEquals("no", TranslationLanguages.appCode("nno"))
    }

    @Test
    fun nothingElseIs() {
        Assert.assertFalse(TranslationLanguages.isKnown(null))
        Assert.assertFalse(TranslationLanguages.isKnown(""))
        Assert.assertFalse(TranslationLanguages.isKnown("und"))
        Assert.assertFalse(TranslationLanguages.isKnown("zz"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun theListIsReadOnly() {
        try {
            (TranslationLanguages.codes() as MutableList<String>).add("zz")
            Assert.fail("the shared language list must not be modifiable")
        } catch (expected: UnsupportedOperationException) {
            // An unmodifiable list is the point: one caller cannot change what another sees.
        }
    }

    /**
     * The shipped detector has the profiles; this list must be exactly what all three detectors can
     * name. Each profile is mapped to its language subtag the way {@link TextLanguage#detect(String)}
     * maps the winner of a detection - {@code getLocale().getLanguage()}, lower-cased - which is why
     * the detector's two Chinese profiles ({@code zh-CN}, {@code zh-TW}) both contribute {@code zh}
     * and the 70 profiles give 69 distinct subtags. Lingua contributes its own ISO 639-1 codes, and
     * OpenNLP its ISO 639-3 ones, mapped through the platform's own table and then through
     * {@link TranslationLanguages#appCode}, which is where a detector's spelling of a language it does
     * cover is translated into the app's ({@code cmn} is {@code zh}, {@code nob} and {@code nb} are
     * {@code no}). Compared as sets, so a missing code and a code one of the three cannot name both
     * fail here.
     *
     * <p>The failure message prints the intersection, which is how the list was written: this test is
     * the definition, and the list is a transcription of it.
     */
    @Test
    fun itIsExactlyWhatAllThreeDetectorsCover() {
        val shipped = TreeSet<String>()
        for (profile in LanguageProfileReader().readAllBuiltIn()) {
            shipped.add(profile.getLocale().getLanguage().lowercase(Locale.ROOT))
        }

        val lingua = TreeSet(LinguaReading.languages())

        val openNlp = TreeSet<String>()
        for (iso3 in OpenNlpReading.modelLanguages()) {
            val iso1 = OpenNlpReading.iso1(iso3)
            if (iso1 != null) {
                openNlp.add(iso1)
            }
        }

        val covered = TreeSet(shipped)
        covered.retainAll(lingua)
        covered.retainAll(openNlp)

        Assert.assertEquals(
                "the shared language list must be exactly what all three detectors cover: "
                        + covered,
                covered,
                TreeSet(TranslationLanguages.codes()))
    }
}
