package uk.xa0.tulkki.translation

/**
 * Whether a bubble's second half is shown at all, and whether it may be read.
 *
 * <p>This is the seam the three display settings plug into, kept as a pure value so the settings can
 * be threaded in without touching a single drawing site:
 *
 * <ul>
 *   <li><strong>Show the second half</strong> (global, sent side). Off means every bubble is a single
 *       half, the owner's own included: the divider goes with it. It is read first of all, so it is
 *       still the outer gate: the received switch below can take a strip away but never put back one
 *       this switch has removed.
 *   <li><strong>Show the concealed original</strong> (received side). Off means a received message is
 *       the translation alone, with nothing drawn under it. It can only ever <em>remove</em> that
 *       strip; see below.
 *   <li><strong>Conceal the owner's own half.</strong> On, the text the owner sent is concealed like
 *       anybody else's; off it is readable, which is the default because seeing what went on the wire
 *       is the point of the second half.
 * </ul>
 *
 * <p><strong>With the interpreter off no half is drawn at all.</strong> The predicate is the first
 * thing read - before the bottom is consulted and before any of the three settings - and its answer
 * is [Kind.NONE] for every combination. Reading it first is safe for exactly the reason the
 * concealment rule is structural: off can only <em>remove</em> a half. `NONE` is not on the
 * readable side of the received branch, so putting the predicate above that branch cannot make
 * somebody else's original readable.
 *
 * <p><strong>A contact's original is concealed unconditionally, and no setting is consulted about
 * whether it may be read.</strong> The [BubbleHalves.Bottom.CONCEALED] case returns before the
 * conceal-own setting is read, and the flags it does answer to decide only whether the strip is drawn
 * at all: that branch chooses between [Kind.CONCEALED] and [Kind.NONE] and never between
 * either of them and [Kind.READABLE]. `SecondHalfTest` walks every combination of the settings
 * to prove it.
 *
 * <p>The same value answers for a reply's quote: [ReplyQuote.bottom] reports the quoted
 * row's side in these same terms.
 *
 * <p>Pure Kotlin, no Android and no message entity, so it is exercised by JVM unit tests without a
 * device. `Kind` stays nested, the three defaults are `const val`s, and `kind()`/`isShown()`/
 * `isConcealed()`/`isReadable()` stay methods.
 */
class SecondHalf private constructor(private val kindValue: Kind) {

    /** What the caller draws: nothing, a concealed half, or readable text. */
    enum class Kind {
        /** No second half at all: the bubble (or quote) is one word, and no divider is drawn. */
        NONE,
        /** The half is drawn but never readable - a contact's original, or the owner's own on request. */
        CONCEALED,
        /** The half is drawn as its text: the owner's own words, with the setting left alone. */
        READABLE
    }

    companion object {

        /** The shipped answers for the three settings, in one place until their screen exists. */
        const val SHOW_SECOND_HALF_BY_DEFAULT = true

        /**
         * The received side's "show the concealed original", at its shipped answer: the strip is drawn,
         * which is exactly what the app did before the setting existed. An install that never touches
         * the row keeps today's appearance, and [TranslationSettings.showConcealedOriginal] keeps
         * that true even more literally by following the older switch while this one is unset.
         */
        const val SHOW_CONCEALED_ORIGINAL_BY_DEFAULT = true

        const val CONCEAL_OWN_SECOND_HALF_BY_DEFAULT = false

        private val NOTHING = SecondHalf(Kind.NONE)
        private val CONCEALED = SecondHalf(Kind.CONCEALED)
        private val READABLE = SecondHalf(Kind.READABLE)

        /**
         * The decision.
         *
         * @param bottom which side sits below the divider, from [BubbleHalves] or
         *     [ReplyQuote]; `null` and [BubbleHalves.Bottom.NONE] both mean there is
         *     nothing to divide
         * @param showSecondHalf the global "show the second half" setting, which is the sent side's
         *     switch; off hides the half and its divider for every message, the received strip included
         * @param showConcealedOriginal the received side's "show the concealed original" setting; it is
         *     read only for [BubbleHalves.Bottom.CONCEALED] and decides only whether that strip is
         *     drawn, never whether its text may be read
         * @param concealOwnSecondHalf the "conceal the owner's own half" setting; it is read only for
         *     [BubbleHalves.Bottom.READABLE], because a contact's original is concealed already and
         *     unconditionally
         * @param interpreter the interpreter's off-switch, as the last argument the way every rule that
         *     consults it takes it. With it off the answer is [Kind.NONE] for every combination,
         *     first statement, and the three settings above are not read at all.
         */
        @JvmStatic
        fun of(
                bottom: BubbleHalves.Bottom?,
                showSecondHalf: Boolean,
                showConcealedOriginal: Boolean,
                concealOwnSecondHalf: Boolean,
                interpreter: Interpreter
        ): SecondHalf {
            if (!interpreter.enabled()) {
                return NOTHING
            }
            if (bottom == null || bottom == BubbleHalves.Bottom.NONE) {
                return NOTHING
            }
            if (!showSecondHalf) {
                return NOTHING
            }
            if (bottom == BubbleHalves.Bottom.CONCEALED) {
                // Somebody else's original. The setting below is deliberately not read here: this is
                // the one case that must not be a preference. The received switch cannot make this
                // text readable either - it chooses only between the concealed strip and nothing - so
                // this branch never reaches the line that can answer READABLE.
                return if (showConcealedOriginal) CONCEALED else NOTHING
            }
            return if (concealOwnSecondHalf) CONCEALED else READABLE
        }

        /** All three settings at their shipped answers, for a caller that has no settings screen yet. */
        @JvmStatic
        fun defaults(bottom: BubbleHalves.Bottom?, interpreter: Interpreter): SecondHalf =
                of(
                        bottom,
                        SHOW_SECOND_HALF_BY_DEFAULT,
                        SHOW_CONCEALED_ORIGINAL_BY_DEFAULT,
                        CONCEAL_OWN_SECOND_HALF_BY_DEFAULT,
                        interpreter)
    }

    fun kind(): Kind = kindValue

    /** True when a second half is drawn at all; false means the bubble is a single half. */
    fun isShown(): Boolean = kindValue != Kind.NONE

    /** True when the half is drawn and must never be readable. */
    fun isConcealed(): Boolean = kindValue == Kind.CONCEALED

    /** True when the half is drawn as its text. */
    fun isReadable(): Boolean = kindValue == Kind.READABLE

    override fun toString(): String = kindValue.toString()
}
