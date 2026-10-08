package uk.xa0.tulkki.translation

/**
 * The English row on a received bubble: whether it is there, where it sits, and what it shows.
 *
 * <p>This is the seam the two new settings plug into, kept as a pure value for the same reason
 * [SecondHalf] is: the settings, the conversation's language and the display have to be threaded in
 * at one place, and that place has to be testable without a device.
 *
 * <ul>
 *   <li><strong>Show a blurred English translation</strong> (global, off by default). It is the master
 *       switch: off, no received bubble has an English row at all, and nothing English is ever bought.
 *   <li><strong>Tap to unblur this translation</strong> (on by default). On, the English is bought
 *       *only* when the owner taps the row. Off, it is bought without waiting for a tap and shown
 *       blurred until one arrives. See [buysOnArrival].
 * </ul>
 *
 * <p><strong>"English" is the hardcoded language code [ENGLISH] and nothing else.</strong> It is
 * never the app language, never the conversation's language and never the study language: the whole
 * point of this row is a language the other two settings do not decide. When the app language *is*
 * English the top half of the bubble is already English, so the row would only duplicate it and there
 * is deliberately no row - the settings screen greys the master switch for the same reason and says
 * so.
 *
 * <p><strong>A purchase is never made here.</strong> This class says what the row is; the tap and the
 * eager prefetch are what buy, and they buy through the one path that consults the cache first
 * ([EnglishLookup]). Nothing in this class, and nothing on the render path, can spend anything.
 *
 * <p><strong>Where the row sits is the conversation's language, not the message's.</strong> A room
 * that speaks English already has the English as its own side of the bubble, so the row takes the
 * concealed original's place ([Placement.REPLACES_ORIGINAL]); anywhere else it is a third row under
 * that strip ([Placement.THIRD_ROW]). Whether there is anything to *buy* is a question about the
 * message, though, and it is answered separately: a message whose own original was already English
 * needs no purchase at all ([englishIsTheOriginal]), and the owner approved showing that original
 * here as a deliberate exception to "originals are hidden".
 *
 * <p>Pure Kotlin apart from the compile-time `Message` constants reached through
 * [TranslationDecision], so it is exercised by JVM unit tests.
 *
 * <p><strong>With the interpreter off there is no row at all.</strong> [of], [ofSent] and
 * [buysOnArrival] take the interpreter and answer [Kind.NONE] / `false` as their first statement, so
 * a plain XMPP client has no third row, no bare bar, no smear and nothing to buy on arrival - and,
 * because [of]'s guard is above the original-was-English branch, the one approved exception to
 * "originals are hidden" is hidden too: off, no original becomes readable anywhere. The guard is
 * the first statement rather than an extra clause in the setting tests because off is not "the
 * switch happened to be off" - it is the app not interpreting, which no setting can express.
 */
class EnglishRow
private constructor(
        private val kindValue: Kind,
        private val placementValue: Placement,
        private val originalFlag: Boolean
) {

    /** True when a row is drawn at all. False means the bubble is exactly what it was before. */
    val isShown: Boolean
        get() = kindValue != Kind.NONE

    /** True when the English is in hand and must not be readable until the owner asks for it. */
    val isBlurred: Boolean
        get() = kindValue == Kind.BLURRED

    /** True when the owner has asked for the English and it is shown as text. */
    val isReadable: Boolean
        get() = kindValue == Kind.READABLE

    /** True when the row is drawn with nothing to blur yet: the tap is what buys the English. */
    val isBareBar: Boolean
        get() = kindValue == Kind.BARE_BAR

    /** What the row shows, if it is there at all. */
    enum class Kind {
        /** No row: the master switch is off, the bubble has nothing to divide, or the app is English. */
        NONE,
        /**
         * The row is drawn but there is no English in hand yet, so there is nothing to blur: the
         * placeholder is a bare bar, the way the concealed second half's stand-in is. A tap is what
         * buys the English.
         */
        BARE_BAR,
        /** The English is in hand and must not be readable: a smear, with no string in the view. */
        BLURRED,
        /** The owner asked for it: the English is shown as text. */
        READABLE
    }

    /** Where the row is drawn, once it is drawn at all. */
    enum class Placement {
        /** No row, so nowhere. */
        NONE,
        /**
         * The conversation speaks English, so this row *is* the conversation's own side of the
         * bubble: it takes the concealed-original strip's place rather than being a third row, which
         * would otherwise be the same words twice.
         */
        REPLACES_ORIGINAL,
        /** The conversation speaks something else: a third row, under the concealed-original strip. */
        THIRD_ROW
    }

    /** The kind, as a method because Java reads it as one (`row.kind()`). */
    fun kind(): Kind = kindValue

    /** The placement, as a method for the same reason. */
    fun placement(): Placement = placementValue

    /** True when the row is drawn and there is English to blur or to read. */
    fun hasEnglish(): Boolean = kindValue == Kind.BLURRED || kindValue == Kind.READABLE

    /**
     * True when the row's English *is* the message's own original, so there was nothing to buy for it
     * and the render path must not look anything up. The original is shown here deliberately - the one
     * exception the owner approved to "originals are hidden" - and it is shown in this row only:
     *
     * <p>Nothing else may read it out of here. The row is drawn from the message the adapter already
     * holds; no English string is ever written to the message row, so it cannot reach a notification,
     * a conversation-list preview or a search, all of which render the row and never this view.
     */
    fun englishIsTheOriginal(): Boolean = originalFlag

    /** True when this row takes the concealed original's place rather than sitting below it. */
    fun replacesOriginalStrip(): Boolean = placementValue == Placement.REPLACES_ORIGINAL

    override fun toString(): String =
            "$kindValue" + if (placementValue == Placement.NONE) "" else "($placementValue)"

    companion object {

        /**
         * The language this row is about, hardcoded. Never a setting, never the app language, never
         * the conversation's language: a "blurred English" row that followed a setting would be a row
         * whose language changes when the owner changes something else.
         */
        const val ENGLISH = "en"

        /** Off: nothing English is bought, and no received bubble grows a row, until the owner asks. */
        const val SHOW_BY_DEFAULT = false

        /**
         * The sent half of the same row: whether the owner's own bubbles may carry an English
         * retranslation of what went on the wire. Off by default for the same reason the received
         * switch is - it is a display setting whose on state buys something - and it is bought by a
         * tap and by nothing else, so there is no eager variant of it.
         */
        const val SHOW_SENT_BY_DEFAULT = false

        /** On: the English is bought by the tap and by nothing else. The cheap half of the default. */
        const val UNBLUR_ON_TAP_BY_DEFAULT = true

        private val NOTHING = EnglishRow(Kind.NONE, Placement.NONE, false)

        /**
         * The decision.
         *
         * @param shown the "show a blurred English translation" setting
         * @param receivedWithTranslation a received bubble that has a translation to divide - that is,
         *     somebody else's message that genuinely needed translating and got one. A single-half
         *     bubble has no side to put the English beside, and a covered one has no place for it
         *     either: its tap already means "translate this one", and this row's own tap needs a row
         *     to be pointed at
         * @param appLanguage the app language; when it is already [ENGLISH] the row is not drawn,
         *     because the top half of the bubble is then the same English
         * @param conversationLanguage the conversation's own language, from
         *     `OutgoingTranslation.languageOf`; it decides the placement only
         * @param originalLanguage the language this message's original was in - for a received row
         *     with a stored translation that is `translation_lang`, which the receive path wrote from
         *     the offline reading of the original. When it is [ENGLISH] there is nothing to buy: the
         *     original *is* the English, and the owner approved showing it
         * @param englishBought the English translation is in hand - bought earlier, or read back out
         *     of the cache. It is what turns a bare bar into a blur without a second purchase
         * @param revealed the owner tapped this row in this process, so the English may be read
         * @param interpreter the interpreter's mode, last like every other rule's; {@code null} is
         *     off (a caller with nothing to say, not a licence), and off is [NOTHING] before any of
         *     the arguments above is read - the original-was-English exception included, because an
         *     original nobody is interpreting must not become readable
         */
        @JvmStatic
        fun of(
                shown: Boolean,
                receivedWithTranslation: Boolean,
                appLanguage: String?,
                conversationLanguage: String?,
                originalLanguage: String?,
                englishBought: Boolean,
                revealed: Boolean,
                interpreter: Interpreter?
        ): EnglishRow {
            if (interpreter == null || !interpreter.enabled()) {
                return NOTHING
            }
            if (!shown || !receivedWithTranslation || isEnglish(appLanguage)) {
                return NOTHING
            }
            val placement =
                    if (isEnglish(conversationLanguage)) {
                        Placement.REPLACES_ORIGINAL
                    } else {
                        Placement.THIRD_ROW
                    }
            val originalIsEnglish = isEnglish(originalLanguage)
            val inHand = originalIsEnglish || englishBought
            if (revealed && inHand) {
                return EnglishRow(Kind.READABLE, placement, originalIsEnglish)
            }
            if (inHand) {
                // Bought, or the original itself: either way there is something to blur, and the
                // blur is the app's existing conceal - the string never enters the view. Nothing
                // unblurs on its own: there is no timer and no "on open" anywhere in this class.
                return EnglishRow(Kind.BLURRED, placement, originalIsEnglish)
            }
            return EnglishRow(Kind.BARE_BAR, placement, false)
        }

        /**
         * The decision for one of the owner's own bubbles: an English retranslation of what went on
         * the wire.
         *
         * <p>Two things are the opposite of the received case, and both come from what a sent
         * bubble's second half *is*. That half is the conversation's language, so the row is
         * redundant - and is not drawn at all - exactly when the conversation already speaks English:
         * that is the collision the owner ruled on, and "blur sent translations" governs the wire in
         * such a room while no English row appears. And because the redundancy is a fact about the
         * conversation rather than about the app language, [of] and its app-language check are not
         * the question here.
         *
         * <p>The placement is always [Placement.THIRD_ROW]: the one case that would take the
         * concealed strip's place on the received side is the case in which this row is not drawn.
         *
         * @param shown the "show a blurred English retranslation" setting
         * @param conversationLanguage the conversation's own language; when it is [ENGLISH] the wire
         *     already *is* the English and there is nothing to add
         * @param englishBought the retranslation is in hand - bought earlier, or read back out of the
         *     cache under the wire's own text
         * @param revealed the owner tapped this row in this process, so the English may be read
         * @param interpreter the interpreter's mode, last; off - or absent - is [NOTHING], the same
         *     first statement as [of]'s
         */
        @JvmStatic
        fun ofSent(
                shown: Boolean,
                conversationLanguage: String?,
                englishBought: Boolean,
                revealed: Boolean,
                interpreter: Interpreter?
        ): EnglishRow {
            if (interpreter == null || !interpreter.enabled()) {
                return NOTHING
            }
            if (!shown || isEnglish(conversationLanguage)) {
                return NOTHING
            }
            if (revealed && englishBought) {
                return EnglishRow(Kind.READABLE, Placement.THIRD_ROW, false)
            }
            return EnglishRow(
                    if (englishBought) Kind.BLURRED else Kind.BARE_BAR, Placement.THIRD_ROW, false)
        }

        /** Both settings at their shipped answers, for a caller that has not read them. */
        @JvmStatic
        fun defaults(
                receivedWithTranslation: Boolean,
                appLanguage: String?,
                conversationLanguage: String?,
                originalLanguage: String?,
                englishBought: Boolean,
                revealed: Boolean,
                interpreter: Interpreter?
        ): EnglishRow =
                of(
                        SHOW_BY_DEFAULT,
                        receivedWithTranslation,
                        appLanguage,
                        conversationLanguage,
                        originalLanguage,
                        englishBought,
                        revealed,
                        interpreter)

        /**
         * Whether the English has to be bought as the message arrives rather than by the tap: the
         * master switch is on and "tap to unblur" is off. When the app language is English there is
         * no row at all, so there is nothing to buy either.
         *
         * <p>It is asked by the receive path, which is the one place that knows a message has just
         * arrived; the answer is what makes the setting's off state honest instead of a promise. Off,
         * the interpreter's own guard answers `false` before the settings are read - and the one call
         * site is behind the receive path's own guard as well, so the eager half is never reached.
         *
         * @param interpreter the interpreter's mode, last; off - or absent - buys nothing, whatever
         *     the two switches say
         */
        @JvmStatic
        fun buysOnArrival(
                shown: Boolean,
                unblurOnTap: Boolean,
                appLanguage: String?,
                interpreter: Interpreter?
        ): Boolean {
            if (interpreter == null || !interpreter.enabled()) {
                return false
            }
            return shown && !unblurOnTap && !isEnglish(appLanguage)
        }

        /**
         * Whether this received body needs an English translation bought for it at all, decided
         * locally and for free: it is prose (not a link, a code, a ping or the room's own bare name)
         * and it is not already English.
         *
         * <p>The same local decision the app-language pass makes, asked with [ENGLISH] as the target
         * - so a message the detector reads as English costs nothing here for the same reason it
         * costs nothing there. A body that needs no English never reaches the API, which is what
         * keeps the eager setting from buying the same sentence back from the model.
         *
         * <p>The [Interpreter] is threaded to [TranslationDecision.classify], which requires it. The
         * row's own off state - `NONE` before anything is asked - is [of]'s and [ofSent]'s, not this
         * method's.
         */
        @JvmStatic
        fun needsBuying(
                body: String?,
                conversationName: String?,
                appLanguage: String?,
                interpreter: Interpreter
        ): Boolean {
            if (isEnglish(appLanguage)) {
                return false
            }
            return TranslationDecision.classify(body, ENGLISH, conversationName, interpreter) ==
                    TranslationDecision.Verdict.TRANSLATE
        }

        /** Whether this language code is the hardcoded English, by the project's one normaliser. */
        @JvmStatic
        fun isEnglish(language: String?): Boolean = ENGLISH == ComposerGate.normalize(language)
    }
}
