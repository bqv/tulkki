package uk.xa0.tulkki.translation

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * How much the account had left, and when that was read.
 *
 * <p>The balance is what the usage screen is about, and it is shared by every key on the account, so
 * it is recorded here as a reading with a time rather than attributed to anything. Remembering it
 * means opening the screen shows the last known figure at once and then corrects itself, and it means
 * a failed refresh still shows something honest - the figure, and how old it is.
 *
 * <p>Only the amount is kept here. The API key is not, and never will be: it lives in the
 * Keystore-backed store in [TranslationSettings] and nowhere else.
 *
 * <p>Pure Kotlin apart from [read] / [write], which keep the reading in Tulkki's own preferences
 * file.
 *
 * <p><strong>The four readings stay `@JvmField`s.</strong> `:ui`'s usage screen and the tests read
 * them as fields - `snapshot.currency`, `snapshot.fetchedAtMillis` - and a Kotlin `val` without
 * `@JvmField` would hand them a getter instead, which is a recompile of a file this port may not
 * touch. The constructor is `private` because nothing outside this class ever built one: the two
 * factories are the whole of how a reading comes to exist.
 */
class BalanceSnapshot private constructor(
        /** False when DeepSeek says the account can no longer pay for a call. */
        @JvmField val isAvailable: Boolean,

        /** The currency the amount is in - the yuan balance is the only one shown - or `null`. */
        @JvmField val currency: String?,

        /** What is left, as DeepSeek spelled it. A string, because that is how the API sends it. */
        @JvmField val totalBalance: String?,

        /** When this reading was taken. */
        @JvmField val fetchedAtMillis: Long
) {

    /** True when there is an amount to show. */
    fun hasAmount(): Boolean = !totalBalance.isNullOrEmpty()

    /**
     * The figure as the screen shows it - the yuan symbol, then the amount exactly as DeepSeek
     * spelled it and with no rounding of our own - or `null` when there is none to show.
     */
    fun displayAmount(): String? {
        val amount = totalBalance
        return if (hasAmount() && amount != null) "¥" + amount else null
    }

    fun toJson(): String {
        val root = JsonObject()
        root.addProperty("is_available", isAvailable)
        root.addProperty("currency", currency)
        root.addProperty("total_balance", totalBalance)
        root.addProperty("fetched_at", fetchedAtMillis)
        return root.toString()
    }

    override fun toString(): String =
            String.format(
                    Locale.ROOT, "%s %s (read at %d)", totalBalance, currency, fetchedAtMillis)

    companion object {

        /** Tulkki's balance file. Deliberately not the settings file, which has another owner. */
        const val PREFERENCES_FILE = "tulkki_balance"

        private const val KEY_SNAPSHOT = "balance"

        /**
         * The currency whose balance the screen shows, and the only one it ever shows. Tulkki's
         * account is billed in yuan; DeepSeek also reports a USD entry, in an order that varies from
         * call to call and often reading zero, and showing that entry - or showing both - answers
         * "how much is left" with a figure that is not the account's money.
         */
        private const val SHOWN_CURRENCY = "CNY"

        /**
         * The reading of `balance` taken at `fetchedAtMillis`.
         *
         * <p>One reading carries one figure, because the screen asks one question: how much is left.
         * The entry that answers it is [choose], which is emphatically not "the first one DeepSeek
         * listed" - see there. An account with nothing to show still gets a snapshot, so "no yuan
         * balance" and "no answer at all" stay distinguishable.
         */
        @JvmStatic
        fun of(balance: DeepSeekClient.Balance, fetchedAtMillis: Long): BalanceSnapshot {
            val chosen = choose(balance.infos)
            return BalanceSnapshot(
                    balance.isAvailable,
                    chosen?.currency,
                    chosen?.totalBalance,
                    fetchedAtMillis)
        }

        /**
         * The one entry the reading is about, or `null` when the answer listed no yuan entry.
         *
         * <p>DeepSeek answers with one entry per currency and does not keep them in a fixed order, so
         * position decides nothing here; the entry is found by its currency code. Only the yuan entry
         * is ever shown, and it is shown whatever it says: a yuan balance of zero is a real figure -
         * the most actionable one the screen can give - and not evidence that the answer failed to
         * parse. What a zero must never do is fall through to another currency; the dollar entry is
         * never shown, at any amount.
         *
         * <p>Package-private in the Java this replaces, and `private` here: nothing outside the class
         * ever called it - `of` and the tests both go through the factory - and Kotlin has no
         * package-private visibility to preserve.
         */
        private fun choose(infos: List<DeepSeekClient.Info>): DeepSeekClient.Info? =
                named(infos, SHOWN_CURRENCY)

        /** The entry in `currency`, in whichever case DeepSeek spelled it, or `null`. */
        private fun named(
                infos: List<DeepSeekClient.Info>,
                currency: String
        ): DeepSeekClient.Info? {
            for (info in infos) {
                if (currency.equals(info.currency, ignoreCase = true)) {
                    return info
                }
            }
            return null
        }

        /** The snapshot `json` describes, or `null` when it is missing or unreadable. */
        @JvmStatic
        fun fromJson(json: String?): BalanceSnapshot? {
            if (json.isNullOrEmpty()) {
                return null
            }
            try {
                val root = JsonParser.parseString(json).asJsonObject
                val currency = string(root, "currency")
                if (currency != null && !SHOWN_CURRENCY.equals(currency, ignoreCase = true)) {
                    // The screen shows the yuan balance and nothing else, so a reading in another
                    // currency is not one this app can show.
                    return null
                }
                return BalanceSnapshot(
                        root.get("is_available").asBoolean,
                        currency,
                        string(root, "total_balance"),
                        root.get("fetched_at").asLong)
            } catch (e: RuntimeException) {
                // A stored reading that cannot be read is simply not a reading.
                return null
            }
        }

        /** The last reading this phone took, or `null` when there is none. */
        @JvmStatic
        fun read(context: Context): BalanceSnapshot? =
                fromJson(balancePreferences(context).getString(KEY_SNAPSHOT, null))

        @JvmStatic
        fun write(context: Context, snapshot: BalanceSnapshot?) {
            if (snapshot == null) {
                return
            }
            balancePreferences(context).edit().putString(KEY_SNAPSHOT, snapshot.toJson()).apply()
        }

        private fun balancePreferences(context: Context): SharedPreferences {
            val application = context.applicationContext
            return (application ?: context)
                    .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        }

        private fun string(root: JsonObject, key: String): String? {
            if (!root.has(key) || root.get(key).isJsonNull) {
                return null
            }
            return root.get(key).asString
        }
    }
}
