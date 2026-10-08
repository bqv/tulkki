package uk.xa0.tulkki.app.utils

import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import io.michaelrocks.libphonenumber.android.Phonenumber
import java.util.ArrayList
import java.util.Locale
import uk.xa0.tulkki.ui.utils.LocationProvider
import uk.xa0.tulkki.libs.Jid

/**
 * The libphonenumber façade: one lazily created parser, and the country list the picker shows.
 *
 * <p>An `object` with `@JvmStatic`s - every member was static and nothing constructed the class.
 *
 * <p><strong>`@Throws` is load-bearing here, not decoration.</strong> `toPhoneNumber` and both
 * `normalize` entries declared `throws NumberParseException` in the Java, and their Java callers
 * catch it. Kotlin has no checked exceptions, so without `@Throws` the Kotlin method would not
 * declare it and those `try`/`catch` blocks would stop compiling ("exception is never thrown").
 * `IllegalArgumentException` is unchecked and needs no declaration, exactly as before.
 *
 * <p>The double-checked lock keeps the Java's monitor - the class object, not the singleton - and
 * its two reads of the field; `instance` stays `@Volatile`, which is what makes the lock-free first
 * read safe.
 *
 * <p>`Country` keeps the Java's odd-looking pair: a private `int code` and a **`getCode()` that
 * returns a `String`** (`'+'` and the number). Kotlin would generate an `Int`-returning `getCode()`
 * for a public `code` property, so the number is held privately under a name of its own and the
 * getter is written out. `getName()` and `getRegion()` come from public `val`s, which generate the
 * same methods the Java had.
 */
object PhoneNumberUtilWrapper {

    @Volatile
    private var instance: PhoneNumberUtil? = null

    @JvmStatic
    fun getCountryForCode(code: String): String {
        val locale = Locale("", code)
        return locale.displayCountry
    }

    @JvmStatic
    fun toFormattedPhoneNumber(context: Context, jid: Jid): String? =
            try {
                getInstance(context)
                        .format(
                                toPhoneNumber(context, jid),
                                PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
                        .replace(' ', '\u202F')
            } catch (e: Exception) {
                jid.getLocal()
            }

    @JvmStatic
    @Throws(NumberParseException::class)
    fun toPhoneNumber(context: Context, jid: Jid): Phonenumber.PhoneNumber =
            getInstance(context).parse(jid.getLocal(), "de")

    @JvmStatic
    @JvmOverloads
    @Throws(NumberParseException::class)
    fun normalize(context: Context, input: String, preferNetwork: Boolean = false): String {
        val number =
                getInstance(context)
                        .parse(input, LocationProvider.getUserCountry(context, preferNetwork))
        if (!getInstance(context).isValidNumber(number)) {
            throw IllegalArgumentException(
                    String.format("%s is not a valid phone number", input))
        }
        return normalize(context, number)
    }

    @JvmStatic
    fun normalize(context: Context, phoneNumber: Phonenumber.PhoneNumber): String =
            getInstance(context).format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164)

    @JvmStatic
    fun getInstance(context: Context): PhoneNumberUtil =
            instance
                    ?: synchronized(PhoneNumberUtilWrapper::class.java) {
                        instance
                                ?: PhoneNumberUtil.createInstance(context).also { instance = it }
                    }

    @JvmStatic
    fun getCountries(context: Context): List<Country> {
        val countries = ArrayList<Country>()
        for (region in getInstance(context).supportedRegions) {
            countries.add(
                    Country(region, getInstance(context).getCountryCodeForRegion(region)))
        }
        return countries
    }

    class Country internal constructor(region: String, code: Int) : Comparable<Country> {

        val name: String = PhoneNumberUtilWrapper.getCountryForCode(region)

        val region: String = region

        private val codeValue: Int = code

        fun getCode(): String = "+" + codeValue

        override fun compareTo(other: Country): Int = name.compareTo(other.name)
    }
}
