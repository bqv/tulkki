package uk.xa0.tulkki.translation

import java.util.Locale

import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * The interpreter's off-switch, as the truth table defines it: two settings in, one answer out.
 *
 * <p>What decides it, in the owner's words: <em>interpretation happens if the app language is not the
 * study language</em>. So the nine combinations of the two settings over their three deciding states
 * - a real language, a second real language, and the {@link Interpreter#NONE} sentinel - fall into
 * three shapes: same language off, different languages (no sentinel) on, and either side's sentinel
 * off. English sits in the first group's opposite on purpose: it is a real language like any other,
 * so app Finnish with study English is <em>on</em>. Each shape is named and asked separately as well
 * as swept as a matrix, so a wrong answer names its own combination.
 *
 * <p>The failure this class exists to catch is not a wrong boolean but a comparison. A stored value
 * that reads {@code "None"} or {@code "  none  "} must switch the interpreter off exactly as
 * {@code "none"} does; {@code "FI"} and {@code " fi "} must read as the same language as {@code "fi"}
 * - which under this rule makes that pair <em>off</em>; and neither must be waved through as an
 * unknown language. Casing and padding are therefore asked explicitly, on both sides of the line.
 *
 * <p>The other half is the default. Both languages are unset on a fresh install, and both then read
 * the device's current locale ({@link TranslationSettings#defaultLanguage()}), so the pair is equal
 * and the interpreter is off without anything being stored. It keeps following the locale until the
 * owner sets one: an unset language moves with the device, a set one does not. The store's {@link
 * TranslationSettings#inMemory()} is the same class over a map instead of a preferences file, so the
 * reads under test are the ones the app performs, and the JVM's own default locale is set and
 * restored around each case because it <em>is</em> the input here.
 *
 * <p>The sentinel's home is also pinned here. {@link TranslationLanguages} is the pinned intersection
 * of the three detectors, so {@code NONE} must not be in it and the round trip through {@link
 * TranslationSettings} must give {@code "none"} back - not an empty string, not the locale, not a
 * dropped key - because a value that did not survive the store would silently re-enable the
 * interpreter on the next read.
 */
class InterpreterTest {

    /** A real language. */
    private val FINNISH = "fi"

    /** A second real language, so "both real" is asked with a pair and not one value twice. */
    private val GERMAN = "de"

    /**
     * A third real language, and the one that used to switch the interpreter off by itself. It is
     * kept as a named constant because that history is exactly what the cells below re-decide.
     */
    private val ENGLISH = "en"

    private lateinit var deviceLocale: Locale

    private lateinit var settings: TranslationSettings

    @Before
    fun setUp() {
        // The locale is an input to the settings, not the environment: taken here, put back in
        // tearDown, so no case can leak a device language into another class.
        deviceLocale = Locale.getDefault()
        settings = TranslationSettings.inMemory()
    }

    /** Leaves a fresh install, and the machine's own locale, behind for the next class. */
    @After
    fun tearDown() {
        Locale.setDefault(deviceLocale)
        TranslationSettings.inMemory()
    }

    private fun on(appLanguage: String, studyLanguage: String): Boolean {
        return Interpreter.of(appLanguage, studyLanguage).enabled()
    }

    /** The device's locale, set to one language so the default is a value a test can name. */
    private fun deviceLocaleIs(language: String) {
        Locale.setDefault(Locale.forLanguageTag(language))
    }

    /** The one rule, swept as the nine-cell matrix: only the shape below is on. */
    @Test
    fun theNineCombinationsAreOffExceptTwoDifferentLanguages() {
        val states = arrayOf(Interpreter.NONE, FINNISH, GERMAN)
        for (app in states) {
            for (study in states) {
                val expected =
                        !Interpreter.NONE.equals(app)
                                && !Interpreter.NONE.equals(study)
                                && !app.equals(study)
                Assert.assertEquals(
                        "app=" + app + " study=" + study, expected, on(app, study))
            }
        }
    }

    @Test
    fun theSameLanguageOnBothSidesIsAPlainClient() {
        Assert.assertFalse("fi x fi", on(FINNISH, FINNISH))
        Assert.assertFalse("de x de", on(GERMAN, GERMAN))
        Assert.assertFalse("en x en", on(ENGLISH, ENGLISH))
    }

    @Test
    fun twoDifferentRealLanguagesInterpret() {
        Assert.assertTrue("fi x de", on(FINNISH, GERMAN))
        Assert.assertTrue("de x fi, the other way round", on(GERMAN, FINNISH))
    }

    /**
     * English is a language and nothing else. App Finnish with study English is the pair the old rule
     * called off; it is on now - reading Finnish, studying English - and English only makes the pair
     * off when it is the language on <em>both</em> sides.
     */
    @Test
    fun englishHasNoSpecialRole() {
        Assert.assertTrue("fi x en: reading Finnish, studying English", on(FINNISH, ENGLISH))
        Assert.assertTrue("en x fi: the other way round", on(ENGLISH, FINNISH))
        Assert.assertTrue("en x de", on(ENGLISH, GERMAN))
        Assert.assertFalse("but en x en is the same language", on(ENGLISH, ENGLISH))
    }

    @Test
    fun theSentinelOnEitherSideIsAPlainClient() {
        Assert.assertFalse("none x fi", on(Interpreter.NONE, FINNISH))
        Assert.assertFalse("fi x none", on(FINNISH, Interpreter.NONE))
        Assert.assertFalse("none x none", on(Interpreter.NONE, Interpreter.NONE))
        Assert.assertFalse(
                "even against a language it differs from, because it is not a language",
                on(Interpreter.NONE, GERMAN))
    }

    @Test
    fun sentinelsAndLanguagesAreComparedCaseInsensitivelyAndUnpadded() {
        Assert.assertFalse("\"None\" is still the sentinel", on("None", FINNISH))
        Assert.assertFalse("\" NONE \" is still the sentinel", on(FINNISH, " NONE "))
        Assert.assertFalse("and mixed, on both sides at once", on("\tNone ", " En "))
        // Normalisation is what makes two spellings of one language the same language, which is now
        // the off answer rather than a second kind of language.
        Assert.assertFalse("\"FI\" is the same language as \"fi\"", on("FI", "fi"))
        Assert.assertFalse("\"  fi  \" too", on(FINNISH, "  FI  "))
        Assert.assertFalse("\"  EN  \" is the same language as \"en\"", on("  EN  ", "en"))
    }

    @Test
    fun anUpperCasedRealLanguageIsStillARealLanguage() {
        Assert.assertTrue("\"FI\" differs from \"de\"", on("FI", "de"))
        Assert.assertTrue("\"  fi  \" differs from \"  DE  \"", on("  fi  ", "  DE  "))
    }

    @Test
    fun theSentinelIsTheStoredLiteralNone() {
        Assert.assertEquals(
                "a stored value, so the spelling may not drift", "none", Interpreter.NONE)
    }

    /**
     * The default after the owner's 2026-10-04 instruction: with neither key set the app language is
     * the **sentinel** and the study language is the device's current locale, and the pair is off
     * because of the sentinel rather than because the two happened to match. The locale is German,
     * deliberately not the Finnish this app was built around, so a default hardcoded anywhere in the
     * build fails here rather than happening to agree with the device.
     */
    @Test
    fun anUnsetPairIsTheSentinelWithTheLocaleAndIsOff() {
        deviceLocaleIs(GERMAN)
        Assert.assertEquals(
                "an unset app language is the sentinel, not the locale",
                Interpreter.NONE,
                settings.appLanguage())
        Assert.assertEquals(
                "the study language still follows the locale", GERMAN, settings.studyLanguage())
        Assert.assertEquals(
                "and the locale is still what the study side falls back to",
                TranslationSettings.defaultLanguage(),
                GERMAN)
        Assert.assertFalse(
                "the sentinel is off, which is why a fresh install is a plain client",
                settings.interpreter().enabled())
    }

    /**
     * Setting only the study language moves **it** off the locale, and the pair is still off: the app
     * side was never set, so it is the sentinel. Before the owner's 2026-10-04 instruction the app
     * side followed the locale here and this cell asserted "de x fi: interpreting".
     */
    @Test
    fun settingOnlyTheStudyLanguageLeavesThePairOff() {
        deviceLocaleIs(GERMAN)
        settings.setStudyLanguage(FINNISH)
        Assert.assertEquals(
                "the app side is still the sentinel", Interpreter.NONE, settings.appLanguage())
        Assert.assertFalse("none x fi is off", settings.interpreter().enabled())
    }

    /** The same, from the other side. */
    @Test
    fun settingOnlyTheAppLanguageTurnsItOn() {
        deviceLocaleIs(GERMAN)
        settings.setAppLanguage(FINNISH)
        Assert.assertEquals(
                "the study language still follows the locale", GERMAN, settings.studyLanguage())
        Assert.assertTrue("fi x de: interpreting", settings.interpreter().enabled())
    }

    /**
     * A system locale change moves the **study** language, which nobody has set, and leaves the app
     * language where it is: the sentinel does not follow a locale (owner's 2026-10-04 instruction).
     * This is the "follows it until explicitly set" half for the side that still has it, asked
     * through both getters and through the derived predicate.
     */
    @Test
    fun anUnsetStudyLanguageFollowsALocaleChangeAndTheAppSideDoesNot() {
        deviceLocaleIs(FINNISH)
        Assert.assertEquals(Interpreter.NONE, settings.appLanguage())
        Assert.assertEquals(FINNISH, settings.studyLanguage())
        Assert.assertFalse("the fresh pair is off on the sentinel", settings.interpreter().enabled())

        deviceLocaleIs(GERMAN)
        Assert.assertEquals(
                "the sentinel does not move with the device",
                Interpreter.NONE,
                settings.appLanguage())
        Assert.assertEquals("the unset study language did move", GERMAN, settings.studyLanguage())
        Assert.assertFalse("and the pair is still off", settings.interpreter().enabled())
    }

    /**
     * A value the owner set sticks: it does not move when the locale does, while the study side -
     * still unset - goes on following it. The app side can no longer be the one that follows, so the
     * transition a locale change used to cause by itself is gone: with the app language unset the app
     * is off whatever the device does (owner's 2026-10-04 instruction).
     */
    @Test
    fun aSetLanguageSurvivesALocaleChange() {
        deviceLocaleIs(FINNISH)
        settings.setStudyLanguage(GERMAN)

        deviceLocaleIs(ENGLISH)
        Assert.assertEquals(
                "the set study language does not move", GERMAN, settings.studyLanguage())
        Assert.assertEquals(
                "and the unset app language is the sentinel, which does not move either",
                Interpreter.NONE,
                settings.appLanguage())
        Assert.assertFalse("none x de: still off", settings.interpreter().enabled())

        // And the same when the app side is the one that was set.
        settings.setAppLanguage(FINNISH)
        deviceLocaleIs(GERMAN)
        Assert.assertEquals("the set app language does not move", FINNISH, settings.appLanguage())
        Assert.assertEquals("and the study side, still unset, followed", GERMAN, settings.studyLanguage())
        Assert.assertTrue("fi x de", settings.interpreter().enabled())
    }

    /** A set language is a stored value, and a locale change is not a reason to rewrite one. */
    @Test
    fun aSetLanguageIsNotRewrittenByALocaleChange() {
        deviceLocaleIs(FINNISH)
        settings.setStudyLanguage(GERMAN)
        deviceLocaleIs(ENGLISH)
        Assert.assertEquals(
                "asking for it twice is what a locale change does, and the answer is the same",
                GERMAN,
                settings.studyLanguage())
        Assert.assertEquals(GERMAN, settings.studyLanguage())
    }

    @Test
    fun noneRoundTripsThroughTheAppLanguageSetting() {
        settings.setStudyLanguage(GERMAN)
        settings.setAppLanguage(Interpreter.NONE)
        Assert.assertEquals(
                "read back as the sentinel, not as the locale",
                Interpreter.NONE,
                settings.appLanguage())
        Assert.assertFalse("and the app is a plain client", settings.interpreter().enabled())
    }

    @Test
    fun noneRoundTripsThroughTheStudyLanguageSetting() {
        settings.setAppLanguage(FINNISH)
        settings.setStudyLanguage(Interpreter.NONE)
        Assert.assertEquals(
                "read back as the sentinel, not as the locale",
                Interpreter.NONE,
                settings.studyLanguage())
        Assert.assertFalse("and the app is a plain client", settings.interpreter().enabled())
    }

    /** Either side's sentinel is off through the settings too, not only through the pure predicate. */
    @Test
    fun eitherSidesSentinelIsOffThroughTheSettings() {
        deviceLocaleIs(FINNISH)
        settings.setAppLanguage(Interpreter.NONE)
        settings.setStudyLanguage(GERMAN)
        Assert.assertFalse("a sentinel app language is off", settings.interpreter().enabled())

        settings.setAppLanguage(GERMAN)
        settings.setStudyLanguage(Interpreter.NONE)
        Assert.assertFalse("and so is a sentinel study language", settings.interpreter().enabled())
    }

    /**
     * Blank is not a stored language on either side, and after the owner's 2026-10-04 instruction the
     * two sides' blanks resolve differently: the app language to the sentinel, the study language to
     * the locale. Clearing the app field therefore leaves the app language unset, not at the locale -
     * which is what this cell asserted before the instruction.
     */
    @Test
    fun blankAppLanguageIsTheSentinelAndBlankStudyLanguageIsTheLocale() {
        deviceLocaleIs(GERMAN)
        settings.setAppLanguage(GERMAN)
        settings.setStudyLanguage(GERMAN)
        settings.setAppLanguage("")
        settings.setStudyLanguage("   ")
        Assert.assertEquals(
                "a blank app language is unset, and unset is the sentinel",
                Interpreter.NONE,
                settings.appLanguage())
        Assert.assertEquals(GERMAN, settings.studyLanguage())
    }

    /**
     * The sentinel is a **stored** value, so a locale change must not resurrect a language behind it:
     * an owner who turned the app language off must not find it on again because the device moved.
     */
    @Test
    fun aStoredSentinelSurvivesALocaleChange() {
        deviceLocaleIs(FINNISH)
        settings.setAppLanguage(Interpreter.NONE)
        settings.setStudyLanguage(GERMAN)

        deviceLocaleIs(ENGLISH)
        Assert.assertEquals(
                "the stored sentinel is not the locale", Interpreter.NONE, settings.appLanguage())
        Assert.assertFalse("and the pair is still off", settings.interpreter().enabled())
    }

    @Test
    fun theResolverFoldsBothSettingsIntoTheOneAnswer() {
        deviceLocaleIs(FINNISH)
        settings.setAppLanguage(FINNISH)
        settings.setStudyLanguage(GERMAN)
        Assert.assertTrue("two languages chosen: interpreting", settings.interpreter().enabled())
        settings.setStudyLanguage(FINNISH)
        Assert.assertFalse(
                "the study language alone turns it off when it equals the app language",
                settings.interpreter().enabled())
        settings.setAppLanguage(Interpreter.NONE)
        settings.setStudyLanguage(GERMAN)
        Assert.assertFalse("and so does the app language alone", settings.interpreter().enabled())
    }

    /**
     * The process start's own read, which exists because {@code TulkkiApplication} may not build the
     * Keystore-backed store on every cold start ({@code TranslationSettings.get} does). With the
     * instance installed - which is what {@code inMemory()} leaves behind, and what a running process
     * has - it must answer exactly what the instance answers, for the on pairs, the off pairs, the
     * sentinel and the locale-following default. That is the whole of the drift guard: the two reads
     * share one {@code language} helper, and this fails if the startup path ever grows an answer of
     * its own.
     *
     * <p>What is <em>not</em> here is the other half of the method: when no instance exists yet it
     * reads the two keys out of the plain preferences file itself, and that needs a real
     * {@code Context} - a device path, not a JVM one. The no-{@code Context} branch it falls to
     * instead is asked directly below.
     */
    @Test
    fun theStartupReadAgreesWithTheInstanceItReplaces() {
        deviceLocaleIs(FINNISH)
        for (pair in
                arrayOf(
                        arrayOf(FINNISH, GERMAN),
                        arrayOf(FINNISH, ENGLISH),
                        arrayOf(FINNISH, Interpreter.NONE),
                        arrayOf(ENGLISH, GERMAN),
                        arrayOf(FINNISH, FINNISH),
                )) {
            settings.setAppLanguage(pair[0])
            settings.setStudyLanguage(pair[1])
            Assert.assertEquals(
                    "app " + pair[0] + " x study " + pair[1],
                    settings.interpreter().enabled(),
                    TranslationSettings.interpreterAtStartup(null).enabled())
        }
        // And the on case is on, so the loop above cannot be satisfied by "always off".
        settings.setAppLanguage(FINNISH)
        settings.setStudyLanguage(GERMAN)
        Assert.assertTrue(TranslationSettings.interpreterAtStartup(null).enabled())
    }

    /** An install that has set nothing is off, and it is the locale that makes it so. */
    @Test
    fun theStartupReadOfAnInstallThatHasSetNothingIsOff() {
        deviceLocaleIs(GERMAN)
        Assert.assertFalse(
                "both languages read the locale, so the pair is equal and the interpreter is off",
                TranslationSettings.interpreterAtStartup(null).enabled())
    }

    @Test
    fun noneIsNotALanguage() {
        Assert.assertFalse(
                "a sentinel is not something a detector can name",
                TranslationLanguages.isKnown(Interpreter.NONE))
        Assert.assertFalse(
                "and it may not enter the pinned intersection",
                TranslationLanguages.codes().contains(Interpreter.NONE))
    }
}
