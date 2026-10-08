package uk.xa0.tulkki.translation

/**
 * The settings that decide how a bubble is drawn, captured as one value.
 *
 * <p>`MessageAdapter` reads them while it binds a row - [SecondHalf] for the two halves,
 * [EnglishRow] for the English rows - and a row that has been bound is not bound again by
 * itself. A change made on the settings screen therefore reaches the bubbles already on screen only
 * when something binds them again, and this value is what tells a conversation whether that is
 * needed: it holds the settings its rows were last decided with, and a difference from the store is
 * exactly "those rows were drawn under other rules".
 *
 * <p>It is the settings and not the decision: every field here is one input the two decision classes
 * take straight from [TranslationSettings]. [of] is the only place that reads them, so
 * "which settings the drawing depends on" is one list a test can pin. The failure it guards against
 * is silent: a display setting that reaches a decision class without reaching this list would be a
 * setting whose change never repaints the bubbles on screen, which is the bug this class exists to
 * close.
 *
 * <p>One setting a reader might expect is deliberately absent:
 *
 * <ul>
 *   <li><strong>Tap to unblur</strong> ([TranslationSettings.unblurEnglishOnTap]) decides
 *       <em>who buys</em> the English, not what a bound row draws: bare bar either way, and the
 *       re-bind would never change a pixel.
 * </ul>
 *
 * <p>The three prompts are absent for the other reason: they are text sent to the model, and only
 * matter once a call is made.
 *
 * <p><strong>Both language settings are here.</strong> The <strong>app language</strong> decides what
 * the top half is - when it is English [EnglishRow.of] refuses the row - so a change to it can take
 * rows off the screen. The <strong>study language</strong> is half of the interpreter's own rule and an
 * input to two decisions a <em>bound</em> row makes on its own: which words carry the reading aid's tap
 * target ([GlossText.words]) and which review the bubble reads back, because [ReviewKey] keys a
 * review by the language its notes are in. It is therefore carried as the <em>value</em> and not as
 * the interpreter's derived answer: carrying only `enabled()` would notice `de` -> `none` and miss
 * `de` -> `sv`.
 *
 * <p>Pure Kotlin and no Android, so the list is exercised by a JVM unit test. The seven settings keep
 * distinct private names because each is also a method on the source `TranslationSettings`, and
 * `equals`/`hashCode` stay overrides next to [of] so adding a field there and forgetting it here
 * fails a test rather than silently dropping the repaint.
 */
class BubbleDisplaySettings private constructor(
        private val showSecondHalfValue: Boolean,
        private val showConcealedOriginalValue: Boolean,
        private val concealOwnSecondHalfValue: Boolean,
        private val showBlurredEnglishValue: Boolean,
        private val showEnglishRetranslationValue: Boolean,
        private val appLanguageValue: String,
        private val studyLanguageValue: String
) {

    companion object {

        /**
         * The settings in force, in the order the two decision classes read them. Read fresh - this is
         * a snapshot, not a listener: the settings screen is another activity and writes through the
         * same store, so whoever binds a row after it closes sees what it wrote.
         */
        @JvmStatic
        fun of(settings: TranslationSettings): BubbleDisplaySettings =
                BubbleDisplaySettings(
                        settings.showSecondHalf(),
                        settings.showConcealedOriginal(),
                        settings.concealOwnSecondHalf(),
                        settings.showBlurredEnglish(),
                        settings.showEnglishRetranslation(),
                        settings.appLanguage(),
                        settings.studyLanguage())
    }

    /**
     * Equal exactly when every setting the drawing depends on is unchanged, which is what makes an
     * equal value mean "these rows are already drawn correctly" rather than "the two reads happened
     * to agree". Kept next to [of] so adding a field there and forgetting it here fails a
     * test rather than silently dropping the repaint.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is BubbleDisplaySettings) {
            return false
        }
        return showSecondHalfValue == other.showSecondHalfValue &&
                showConcealedOriginalValue == other.showConcealedOriginalValue &&
                concealOwnSecondHalfValue == other.concealOwnSecondHalfValue &&
                showBlurredEnglishValue == other.showBlurredEnglishValue &&
                showEnglishRetranslationValue == other.showEnglishRetranslationValue &&
                appLanguageValue == other.appLanguageValue &&
                studyLanguageValue == other.studyLanguageValue
    }

    override fun hashCode(): Int {
        var result = showSecondHalfValue.hashCode()
        result = 31 * result + showConcealedOriginalValue.hashCode()
        result = 31 * result + concealOwnSecondHalfValue.hashCode()
        result = 31 * result + showBlurredEnglishValue.hashCode()
        result = 31 * result + showEnglishRetranslationValue.hashCode()
        result = 31 * result + appLanguageValue.hashCode()
        return 31 * result + studyLanguageValue.hashCode()
    }

    /** The settings in words, for a log line about why a list re-bound. */
    override fun toString(): String =
            "showSecondHalf=" +
                    showSecondHalfValue +
                    " showConcealedOriginal=" +
                    showConcealedOriginalValue +
                    " concealOwnSecondHalf=" +
                    concealOwnSecondHalfValue +
                    " showBlurredEnglish=" +
                    showBlurredEnglishValue +
                    " showEnglishRetranslation=" +
                    showEnglishRetranslationValue +
                    " appLanguage=" +
                    appLanguageValue +
                    " studyLanguage=" +
                    studyLanguageValue
}
