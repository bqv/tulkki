package uk.xa0.tulkki.data.utils

import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException

/**
 * Phone-number normalisation, declared in `:data` and implemented by `:app`.
 *
 * 3.7 pair 2. `Conversation`'s command form builds two `tel:` URIs out of a command field's value, and both
 * go through `uk.xa0.tulkki.app.utils.PhoneNumberUtilWrapper.normalize`. That class cannot move down: it asks
 * `uk.xa0.tulkki.ui.utils.LocationProvider` which country the owner is in, and `:data` may not name `:ui`. So
 * the question is inverted - `:data` declares the one method it needs and `:app` answers it.
 *
 * It is a functional interface on purpose: the implementation is a method reference to the existing static
 * (`PhoneNumberNormalizer.install(PhoneNumberUtilWrapper::normalize)`), so no adapter class and no second copy
 * of the parsing exist. The checked [NumberParseException] is a third-party type from the bundled
 * libphonenumber, not an edge, so it crosses the boundary unchanged; the two `Conversation` callers already
 * catch it beside `IllegalArgumentException`.
 *
 * **The holder fails loudly.** [normalizePhoneNumber] throws an [IllegalStateException] naming the port and the
 * install point rather than returning the input unchanged - a silently unnormalised number would dial the
 * wrong thing. The install line lives in `uk.xa0.tulkki.app.TulkkiApplication.onCreate`, the same composition
 * point as 8b's `ViewPorts` and pair 10's `EngineHost`: this port needs nothing but a `Context`, so it can
 * exist before any service does.
 */
fun interface PhoneNumberNormalizer {

    /** [input] as an E.164 number for the owner's country. */
    @Throws(IllegalArgumentException::class, NumberParseException::class)
    fun normalize(context: Context, input: String): String

    companion object {

        @Volatile
        private var installed: PhoneNumberNormalizer? = null

        /** The composition root's one line, from `TulkkiApplication.onCreate`. */
        @JvmStatic
        fun install(normalizer: PhoneNumberNormalizer) {
            installed = normalizer
        }

        /** @throws IllegalStateException when nothing was installed - a build fault, not a device state */
        @JvmStatic
        @Throws(IllegalArgumentException::class, NumberParseException::class)
        fun normalizePhoneNumber(context: Context, input: String): String {
            val normalizer =
                installed
                    ?: throw IllegalStateException(
                        "uk.xa0.tulkki.data.utils.PhoneNumberNormalizer was never installed: uk.xa0.tulkki.app." +
                            "TulkkiApplication.onCreate must call PhoneNumberNormalizer.install" +
                            " at process start, and it has not",
                    )
            return normalizer.normalize(context, input)
        }
    }
}
