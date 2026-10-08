package uk.xa0.tulkki.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Locale

/**
 * Tulkki's settings: where the app language, the daily cap, the composer gate's reveal order and the
 * DeepSeek key live.
 *
 * <p>Both languages - the app language and the study language - default to the device's current
 * locale and go on following it until the owner sets one ([defaultLanguage]); a set value is
 * stored and no longer follows anything.
 *
 * <p>This is storage, not a screen. The screen comes later and reads exactly these methods.
 *
 * <p>The key is the one secret, so it is the one thing not in the ordinary preferences file: it goes
 * through `EncryptedSharedPreferences`, whose master key lives in the Android Keystore. If that
 * store cannot be opened the key is treated as absent and writing one is refused - it is never
 * quietly written in the clear. [apiKeyStorageAvailable] says which of the two happened.
 *
 * <p>Everything the app spends is counted here too ([tokenCounter]), because the cap covers
 * every request Tulkki makes, not one feature's share of them.
 *
 * <p><strong>The accessors stay functions, not properties.</strong> The Java this replaces declared
 * them as plain methods (`appLanguage()`, `showSecondHalf()`), so every Kotlin and Java call site in
 * the tree already reads them with parentheses; a Kotlin `val` would move every one of those call
 * sites, and several live in other lanes' files. A faithful port keeps them calls.
 */
class TranslationSettings
internal constructor(
        private val preferences: Prefs,
        private val secrets: Prefs,
        usageStore: UsageLedger.Store
) {

    private val tokenCounterValue: DailyTokenCounter =
            DailyTokenCounter(CounterStore(preferences))
    private val activityValue: TranslationActivity =
            TranslationActivity(ActivityStore(preferences))
    private val usageLedgerValue: UsageLedger = UsageLedger(usageStore)

    /** The refreshed tariff, decoded on first use and dropped whenever it is written. */
    private var publishedTariffTable: TariffPage.Table? = null

    private var tariffRead = false

    internal constructor(preferences: Prefs, secrets: Prefs) :
            this(preferences, secrets, UsageLedger.MemoryStore())


    /**
     * The language everything received is translated into, and everything sent is translated from.
     *
     * <p>Unset or blank, it is [Interpreter.NONE] - **not** the device's current locale. The
     * owner's instruction of 2026-10-04: a fresh install has no app language until the owner sets
     * one, so the plain client is reached by an explicit sentinel rather than by the two sides
     * happening to be equal. Set, it is what the owner chose, and the sentinel is a storable value
     * like any other.
     */
    fun appLanguage(): String = language(preferences, KEY_APP_LANGUAGE, Interpreter.NONE)

    fun setAppLanguage(isoCode: String?) {
        preferences.putString(KEY_APP_LANGUAGE, isoCode?.javaTrim() ?: "")
    }

    /**
     * The language a word gloss is written in - the language the owner is learning, which is not the
     * app language.
     *
     * <p>It has to be separate: the text being glossed is already in the app language, so a gloss
     * "into the app language" would explain Finnish with Finnish. **Unset it follows the locale** -
     * the app language does not, since the owner's 2026-10-04 instruction gave that side the
     * sentinel - so a fresh install pairs the sentinel with the locale and is off because of the
     * sentinel, not because the two happened to match.
     */
    fun studyLanguage(): String = language(preferences, KEY_STUDY_LANGUAGE, defaultLanguage())


    fun setStudyLanguage(isoCode: String?) {
        preferences.putString(KEY_STUDY_LANGUAGE, isoCode?.javaTrim() ?: "")
    }

    /**
     * The kind of doubt a held send is waiting on, or `null` when this message is not held on
     * one (docs/MIGRATION.md item 16). Kept beside the answer - and in this store rather than on the
     * message, because the column the answer lives in is the message's own - so that the tap still
     * knows which of the two kinds it is after the app has been killed.
     */
    fun heldDoubt(messageUuid: String?): LanguageCheck.Doubt? {
        if (messageUuid == null) {
            return null
        }
        return HeldDoubt.parse(preferences.getString(HeldDoubt.key(messageUuid), ""))
    }

    /**
     * Records - or, with `null`, drops - the kind of doubt this message is held on. Dropped
     * whenever the hold ends, whichever way it ends: sent as held, discarded and re-translated, or
     * replaced by a failure.
     */
    fun setHeldDoubt(messageUuid: String?, doubt: LanguageCheck.Doubt?) {
        if (messageUuid == null) {
            return
        }
        if (doubt == null) {
            preferences.remove(HeldDoubt.key(messageUuid))
        } else {
            preferences.putString(HeldDoubt.key(messageUuid), HeldDoubt.name(doubt))
        }
    }

    /**
     * The notes that came with a held answer, or `null` when this hold kept none. Stored as
     * the raw answer `ReviewParser` reads back, beside the kind, for the same reason: the tap
     * happens in another process lifetime.
     */
    fun heldNotes(messageUuid: String?): String? {
        if (messageUuid == null) {
            return null
        }
        val stored = preferences.getString(HeldDoubt.notesKey(messageUuid), "")
        return if (stored == null || stored.javaTrim().isEmpty()) null else stored
    }

    /**
     * Records - or, with `null`, drops - the notes a held answer carried. Dropped with the
     * hold, whichever way it ends, so no later message can read another one's notes.
     */
    fun setHeldNotes(messageUuid: String?, rawNotes: String?) {
        if (messageUuid == null) {
            return
        }
        if (rawNotes == null) {
            preferences.remove(HeldDoubt.notesKey(messageUuid))
        } else {
            preferences.putString(HeldDoubt.notesKey(messageUuid), rawNotes)
        }
    }

    /**
     * Why this message is a <strong>send failure</strong> - the owner's retry was tried and nothing
     * was sent - or `null` when the message is not one.
     *
     * <p>Kept per message and in this store rather than only in the last-failure record, because the
     * resting bar has to name the reason for the row it finds after a restart: the last-failure
     * record belongs to the newest failure, which may be another message's by then
     * ([TranslationActivity.reasonFor] says so itself). The message's own uuid is the whole
     * address, so a stale entry for a message that no longer exists is never read.
     *
     * <p>Written when the row is marked a send failure (`OutgoingTranslation`) and dropped
     * when the row stops being one - sent as written, or translated and sent.
     */
    fun sendFailure(messageUuid: String?): HeldSend.HoldReason? {
        if (messageUuid == null) {
            return null
        }
        return HeldSend.parseReason(
                preferences.getString(SEND_FAILURE_KEY_PREFIX + messageUuid, ""))
    }

    /** Records - or, with `null`, drops - this message's send-failure reason. */
    fun setSendFailure(messageUuid: String?, reason: HeldSend.HoldReason?) {
        if (messageUuid == null) {
            return
        }
        if (reason == null) {
            preferences.remove(SEND_FAILURE_KEY_PREFIX + messageUuid)
        } else {
            preferences.putString(
                    SEND_FAILURE_KEY_PREFIX + messageUuid, HeldSend.reasonName(reason))
        }
    }

    /**
     * Why this message was sent as written untranslated, or `null` when it was not.
     *
     * <p>The owner's one exception to "nothing is sent untranslated" is a per-message action, so the
     * record that it happened - and the reason the row was in the state that offered it - is kept
     * per message and outlives the process, exactly as the send failure it replaces. Only a row the
     * owner chose reaches this: nothing writes it on its own.
     */
    fun sentAsWritten(messageUuid: String?): HeldSend.HoldReason? {
        if (messageUuid == null) {
            return null
        }
        return HeldSend.parseReason(
                preferences.getString(SENT_AS_WRITTEN_KEY_PREFIX + messageUuid, ""))
    }

    /** Records that this message was sent as written, and why. */
    fun setSentAsWritten(messageUuid: String?, reason: HeldSend.HoldReason?) {
        if (messageUuid == null) {
            return
        }
        if (reason == null) {
            preferences.remove(SENT_AS_WRITTEN_KEY_PREFIX + messageUuid)
        } else {
            preferences.putString(
                    SENT_AS_WRITTEN_KEY_PREFIX + messageUuid, HeldSend.reasonName(reason))
        }
    }

    /**
     * The interpreter's one derived setting: on exactly when the two languages differ and neither is
     * [Interpreter.NONE].
     *
     * <p>This is the whole adapter between the two stored settings and [Interpreter], and it is
     * deliberately the only one. Nothing else may answer "is the interpreter on": a pure consumer is
     * handed this value as a required argument, so a new call site cannot compile without deciding,
     * and "forgot the guard" cannot become a third mode.
     */
    fun interpreter(): Interpreter = Interpreter.of(appLanguage(), studyLanguage())




    /** The most tokens Tulkki may spend in one local calendar day. Zero or less means no cap. */
    fun dailyTokenCap(): Int = preferences.getInt(KEY_DAILY_TOKEN_CAP, DEFAULT_DAILY_TOKEN_CAP)

    fun setDailyTokenCap(tokens: Int) {
        preferences.putInt(KEY_DAILY_TOKEN_CAP, tokens)
    }

    /**
     * The prices the per-day yuan estimate is computed at: three <em>peak</em> numbers in CNY per 1M
     * tokens, with off-peak at half ([TokenPrices.OFF_PEAK_FACTOR]).
     *
     * <p>Read through [TokenPrices.of], so a blank or unparseable field is the embedded default
     * rather than zero - see that method for why zero is the one wrong answer.
     */
    fun tokenPrices(): TokenPrices = tokenPrices(TokenPrices.inForceFor(DeepSeekClient.DEFAULT_MODEL))

    /**
     * The same, with the model row the blanks fall back to named.
     *
     * <p>The usage screen passes the row it picked from the ids the key reports, so switching the
     * model the app sends - or a key that offers only the other row - moves the defaults with it
     * while an edited field still wins.
     */
    fun tokenPrices(fallback: TokenPrices.Model?): TokenPrices {
        val embedded =
                if (fallback == null) TokenPrices.inForceFor(DeepSeekClient.DEFAULT_MODEL)
                else fallback
        val published = publishedTariff()
        // The refreshed page is the base the owner's fields fall back to, so a refresh moves the
        // numbers while an edited field still wins: what the owner wrote is a judgement, and a
        // fetched table is only a better-informed default.
        val base = if (published == null) embedded else published.effective(embedded)
        return TokenPrices.of(
                preferences.getString(KEY_PRICE_PEAK_CACHE_HIT, ""),
                preferences.getString(KEY_PRICE_PEAK_CACHE_MISS, ""),
                preferences.getString(KEY_PRICE_PEAK_OUTPUT, ""),
                base,
                if (published == null) TokenPrices.OFF_PEAK_FACTOR else published.offPeakFactor)
    }

    /**
     * The tariff last read off DeepSeek's own pricing page, or `null` when none ever was.
     *
     * <p>Decoded once and kept, because the Ledger screen asks for it several times per draw and a
     * preference read plus a JSON parse under every one of those is work done for nothing. Writing or
     * clearing it drops the copy, so there is no window in which the screen and the ledger disagree.
     */
    @Synchronized
    fun publishedTariff(): TariffPage.Table? {
        if (tariffRead) {
            return publishedTariffTable
        }
        tariffRead = true
        publishedTariffTable = TariffPage.fromJson(preferences.getString(KEY_PUBLISHED_TARIFF, ""))
        return publishedTariffTable
    }

    /** Keeps a refreshed tariff. An empty or unusable table clears it rather than storing one. */
    @Synchronized
    fun setPublishedTariff(table: TariffPage.Table?) {
        val json = TariffPage.toJson(table)
        if (json.isEmpty() || TariffPage.fromJson(json) == null) {
            clearPublishedTariff()
            return
        }
        preferences.putString(KEY_PUBLISHED_TARIFF, json)
        publishedTariffTable = TariffPage.fromJson(json)
        tariffRead = true
    }

    /** Forgets the refreshed tariff, which puts the build's own prices and hours back in force. */
    @Synchronized
    fun clearPublishedTariff() {
        preferences.putString(KEY_PUBLISHED_TARIFF, "")
        publishedTariffTable = null
        tariffRead = true
    }

    /** The peak windows in force: the page's, when one has been read, and the build's otherwise. */
    fun tariffSchedule(): PeakTariff.Schedule? {
        val table = publishedTariff()
        return table?.schedule
    }

    /** Stores one peak price verbatim; a blank value means "use the embedded default". */
    fun setPeakCacheHitPrice(price: String?) {
        preferences.putString(KEY_PRICE_PEAK_CACHE_HIT, price?.javaTrim() ?: "")
    }

    fun setPeakCacheMissPrice(price: String?) {
        preferences.putString(KEY_PRICE_PEAK_CACHE_MISS, price?.javaTrim() ?: "")
    }

    fun setPeakOutputPrice(price: String?) {
        preferences.putString(KEY_PRICE_PEAK_OUTPUT, price?.javaTrim() ?: "")
    }

    /** The stored price as a settings field should open: the value in force, default included. */
    fun peakCacheHitPrice(): String = TokenPrices.format(tokenPrices().peakCacheHit)

    fun peakCacheMissPrice(): String = TokenPrices.format(tokenPrices().peakCacheMiss)

    fun peakOutputPrice(): String = TokenPrices.format(tokenPrices().peakOutput)

    /**
     * The app's own spend history, as tokens per local day. Read by the usage screen and written by
     * [recordUsage].
     */
    fun usageLedger(): UsageLedger = usageLedgerValue

    /**
     * The same, for a call that belongs to no conversation. Kept as its own entry point because most
     * callers are of that kind - a gloss, a note, the sample, a suggestion - and there is one place
     * that decides what "no origin" means, which is the ledger's own `''` bucket.
     */
    fun recordUsage(atMillis: Long, usage: DeepSeekClient.Usage?) {
        recordUsage(atMillis, usage, null)
    }

    /**
     * Files one DeepSeek call's tokens in the per-day ledger, and in the day's per-origin row.
     *
     * <p>The instant is the call's own, which is what decides the tariff (in UTC, so the device's
     * timezone cannot move a call to the cheaper window); the day is that instant's local calendar
     * day, the same boundary [tokenCounter] uses. The two are separate facts and both come
     * from the same timestamp, so they cannot be taken from different moments.
     *
     * <p>`origin` is the conversation the call belonged to - its uuid - or `null` for a
     * call that belongs to no conversation (a gloss, a note, the silent language sample, a pre-send
     * suggestion). It is what the ledger's day drill-down groups by; the name is resolved from
     * `conversations` when that read happens and is never stored in the ledger.
     *
     * <p>The daily cap is charged separately by each caller, from the response's own
     * `total_tokens`: that figure's behaviour must not change, and the ledger is the new
     * history rather than a replacement for the counter.
     */
    fun recordUsage(atMillis: Long, usage: DeepSeekClient.Usage?, origin: String?) {
        if (usage == null) {
            return
        }
        usageLedgerValue.record(
                atMillis,
                usage.cacheHitTokens,
                usage.cacheMissTokens,
                usage.completionTokens,
                origin)
    }

    /**
     * The composer gate's reveal order: `true` shows the suggested translation first, for the
     * owner to retype; `false` makes them attempt it before comparing.
     */
    fun revealSuggestionFirst(): Boolean =
            preferences.getBoolean(KEY_REVEAL_SUGGESTION_FIRST, false)

    fun setRevealSuggestionFirst(revealSuggestionFirst: Boolean) {
        preferences.putBoolean(KEY_REVEAL_SUGGESTION_FIRST, revealSuggestionFirst)
    }

    /**
     * The first display setting: whether a translated message shows its second half at all. Off makes
     * every bubble a single half, the owner's own included.
     *
     * <p>The default is [SecondHalf.SHOW_SECOND_HALF_BY_DEFAULT], which is exactly what the app
     * did before this setting existed: an install that never opens the settings screen sees no change.
     * This setting can only ever <em>remove</em> a half; it cannot make one readable that was not.
     */
    fun showSecondHalf(): Boolean =
            preferences.getBoolean(KEY_SHOW_SECOND_HALF, SecondHalf.SHOW_SECOND_HALF_BY_DEFAULT)

    fun setShowSecondHalf(showSecondHalf: Boolean) {
        preferences.putBoolean(KEY_SHOW_SECOND_HALF, showSecondHalf)
    }

    /**
     * The received side's own switch: whether a received message draws the concealed original's strip
     * under its translation at all.
     *
     * <p>Off, the message is the translation alone and nothing is drawn under it. On, the strip is
     * drawn exactly as it always was. It is not a blur control and never can be: the concealment is
     * decided in [SecondHalf] before this value is read, so there is no on state of this switch
     * that makes a contact's original readable - it only decides whether the concealed strip is there.
     *
     * <p><strong>The fallback is the migration.</strong> Until the owner touches this row the value
     * stored for it is absent, and an absent value is read as [showSecondHalf] - the older
     * global switch - so an install that upgrades to this build and never opens the settings screen
     * keeps exactly the appearance it had: strip where it had a strip, single half where it had one.
     * Writing the row stores a value of its own, after which the two switches are independent.
     */
    fun showConcealedOriginal(): Boolean =
            preferences.getBoolean(KEY_SHOW_CONCEALED_ORIGINAL, showSecondHalf())

    fun setShowConcealedOriginal(showConcealedOriginal: Boolean) {
        preferences.putBoolean(KEY_SHOW_CONCEALED_ORIGINAL, showConcealedOriginal)
    }

    /**
     * The second display setting: whether the owner's <em>own</em> second half - the text that went
     * on the wire - is concealed like a contact's.
     *
     * <p>It reaches only the owner's own messages. A received message's concealed half is concealed
     * unconditionally in [SecondHalf], before this value is read, so no setting - this one, a
     * future one, or a mistake in this class - can make somebody else's original readable. The default
     * is [SecondHalf.CONCEAL_OWN_SECOND_HALF_BY_DEFAULT]: today's behaviour, where the owner can
     * see what they sent.
     */
    fun concealOwnSecondHalf(): Boolean =
            preferences.getBoolean(
                    KEY_CONCEAL_OWN_SECOND_HALF, SecondHalf.CONCEAL_OWN_SECOND_HALF_BY_DEFAULT)

    fun setConcealOwnSecondHalf(concealOwnSecondHalf: Boolean) {
        preferences.putBoolean(KEY_CONCEAL_OWN_SECOND_HALF, concealOwnSecondHalf)
    }

    /**
     * The English row's master switch: whether a received bubble offers the English at all.
     *
     * <p>Off, nothing English is bought and no bubble grows a row. The default is
     * [EnglishRow.SHOW_BY_DEFAULT], which is off: this is the one setting whose on state buys
     * something, so an install that never opens the settings screen must not be spending on it.
     */
    fun showBlurredEnglish(): Boolean =
            preferences.getBoolean(KEY_SHOW_BLURRED_ENGLISH, EnglishRow.SHOW_BY_DEFAULT)

    fun setShowBlurredEnglish(showBlurredEnglish: Boolean) {
        preferences.putBoolean(KEY_SHOW_BLURRED_ENGLISH, showBlurredEnglish)
    }

    /**
     * When the English is bought: on the tap (on, the default), or as the message arrives (off), in
     * which case it is shown blurred until the owner taps it.
     *
     * <p>Either way the row is the same and the blur is the same; the only difference is whether the
     * request waits for a finger. The default is [EnglishRow.UNBLUR_ON_TAP_BY_DEFAULT], the
     * cheap one - nothing is bought for a message nobody asks about.
     */
    fun unblurEnglishOnTap(): Boolean =
            preferences.getBoolean(KEY_UNBLUR_ENGLISH_ON_TAP, EnglishRow.UNBLUR_ON_TAP_BY_DEFAULT)

    fun setUnblurEnglishOnTap(unblurEnglishOnTap: Boolean) {
        preferences.putBoolean(KEY_UNBLUR_ENGLISH_ON_TAP, unblurEnglishOnTap)
    }

    /**
     * Whether the owner's own bubbles may carry an English retranslation of what went on the wire.
     *
     * <p>Off by default, like the received switch and for the same reason: it is a display setting
     * whose on state buys something. It buys only on a tap, so there is no eager variant of it, and
     * nothing is bought at all in a conversation that already speaks English - the wire is the English
     * there, and the sent-side blur governs it instead (see [EnglishRow.ofSent]).
     */
    fun showEnglishRetranslation(): Boolean =
            preferences.getBoolean(
                    KEY_SHOW_ENGLISH_RETRANSLATION, EnglishRow.SHOW_SENT_BY_DEFAULT)

    fun setShowEnglishRetranslation(showEnglishRetranslation: Boolean) {
        preferences.putBoolean(KEY_SHOW_ENGLISH_RETRANSLATION, showEnglishRetranslation)
    }

    /**
     * The API base URL, or the real DeepSeek API when it has never been set - and when it has been
     * cleared, because an empty field means the same thing as an untouched one.
     */
    fun apiBaseUrl(): String {
        val url = preferences.getString(KEY_API_BASE_URL, DeepSeekClient.DEFAULT_BASE_URL)
        return if (url == null || url.javaTrim().isEmpty()) DeepSeekClient.DEFAULT_BASE_URL
        else url.javaTrim()
    }

    fun setApiBaseUrl(baseUrl: String?) {
        preferences.putString(KEY_API_BASE_URL, baseUrl?.javaTrim() ?: "")
    }



    /**
     * The instruction Tulkki sends to translate, or the empty string when the owner has not written
     * one - see [PromptBook], which is the only reader and knows what an empty setting means.
     *
     * <p>Stored verbatim rather than trimmed: a prompt is prose the owner typed, and the one thing a
     * settings field must not do is quietly change the words of a prompt it is holding. An all-blank
     * value is how [PromptBook] is told to use the shipped wording again.
     */
    fun translatePrompt(): String = storedPrompt(KEY_TRANSLATE_PROMPT)

    fun setTranslatePrompt(prompt: String?) {
        preferences.putString(KEY_TRANSLATE_PROMPT, prompt ?: "")
    }

    /** The instruction the outbound review rides on. See [translatePrompt]. */
    fun reviewPrompt(): String = storedPrompt(KEY_REVIEW_PROMPT)

    fun setReviewPrompt(prompt: String?) {
        preferences.putString(KEY_REVIEW_PROMPT, prompt ?: "")
    }

    /** The instruction the reading aid's gloss is bought with. See [translatePrompt]. */
    fun glossPrompt(): String = storedPrompt(KEY_GLOSS_PROMPT)

    fun setGlossPrompt(prompt: String?) {
        preferences.putString(KEY_GLOSS_PROMPT, prompt ?: "")
    }

    /**
     * The retry action's wording in force: the owner's edit, or the shipped wording while the field
     * is blank. Read through [RetryWording], which is the only place that answers "what does a
     * blank mean", and deliberately never through [PromptBook] - this is UI copy and not part
     * of any prompt's cache identity.
     */
    fun retryWording(): String = RetryWording.inForce(storedPrompt(KEY_RETRY_WORDING))

    /** Stores the retry wording verbatim; blank means "use the shipped wording". */
    fun setRetryWording(wording: String?) {
        preferences.putString(KEY_RETRY_WORDING, wording ?: "")
    }

    private fun storedPrompt(key: String): String {
        val prompt = preferences.getString(key, "")
        return prompt ?: ""
    }

    /** The DeepSeek key, or the empty string when there is none. Never logged. */
    fun apiKey(): String {
        val key = secrets.getString(KEY_API_KEY, "")
        return key?.javaTrim() ?: ""
    }

    /**
     * Stores the key in the Keystore-backed file. Silently does nothing when that store is
     * unavailable - see [apiKeyStorageAvailable]; the key is never written in the clear.
     */
    fun setApiKey(key: String?) {
        secrets.putString(KEY_API_KEY, key?.javaTrim() ?: "")
    }

    fun hasApiKey(): Boolean = apiKey().isNotEmpty()

    /** False when the Keystore-backed store could not be opened, so no key can be read or saved. */
    fun apiKeyStorageAvailable(): Boolean = secrets.available()

    /** Today's tokens against the cap, shared by every feature that calls DeepSeek. */
    fun tokenCounter(): DailyTokenCounter = tokenCounterValue

    /**
     * What Tulkki has translated today and the last thing that stopped it, shared by every screen
     * that has to say what the app is doing.
     */
    fun activity(): TranslationActivity = activityValue

    companion object {

        /**
         * S5-4 retired the history watermark from here. It was the newest message time a pass over the
         * gap had already considered - "a watermark and not a record of what was translated" - and it had
         * one reader and one writer, both in the pass. The cursor replaced it (`sync_cursor.anchor_*`,
         * `sync_conversation.swept_through`, `messages.delivery`), because what a catch-up carried is now
         * recorded rather than inferred from a monotone long. The stored key
         * `history_watermark` is deliberately left in the encrypted preferences: a dead key costs
         * nothing and rewriting the prefs file to remove one name is a risk with no return.
         */
        private fun encryptedPrefs(context: Context): Prefs =
                try {
                    val masterKey =
                            MasterKey.Builder(context)
                                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                    .build()
                    SharedPrefs(
                            EncryptedSharedPreferences.create(
                                    context,
                                    SECRET_PREFERENCES_FILE,
                                    masterKey,
                                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM))
                } catch (e: GeneralSecurityException) {
                    keystoreUnavailable(e)
                } catch (e: IOException) {
                    keystoreUnavailable(e)
                } catch (e: RuntimeException) {
                    keystoreUnavailable(e)
                }

        /** The one refusal path, shared by the three exception shapes the Keystore store can throw. */
        private fun keystoreUnavailable(e: Exception): Prefs {
            Log.e(
                    TAG,
                    "the Keystore-backed store for the DeepSeek key is unavailable, so the key cannot"
                            + " be read or saved: "
                            + e)
            return UnavailablePrefs()
        }

        /**
         * The one instance, backed by the app's own preferences files.
         *
         * <p>The double-checked read is written so no `!!` is needed: the first cheap read, then the
         * lock, then the same read again inside it.
         */
        @JvmStatic
        fun get(context: Context): TranslationSettings {
            val existing = instance
            if (existing != null) {
                return existing
            }
            synchronized(TranslationSettings::class.java) {
                val again = instance
                if (again != null) {
                    return again
                }
                val app = context.applicationContext
                val base = app ?: context
                val local =
                        TranslationSettings(
                                SharedPrefs(
                                        base.getSharedPreferences(
                                                PREFERENCES_FILE, Context.MODE_PRIVATE)),
                                encryptedPrefs(base),
                                TranslationStore(base))
                instance = local
                return local
            }
        }

        /**
         * For JVM tests: the same class, with the settings in a map instead of a preferences file, and
         * installed as the process-wide instance so that code which has only a key to go on - the usage
         * screen's client - sees the settings the test just made.
         *
         * <p>C1 (docs/MIGRATION.md "The cycle rules" §4) made this `public`: the class lived in
         * `uk.xa0.tulkki.data` while the tests that use it live in `uk.xa0.tulkki.translation`, so no
         * narrower access compiled. 3.7 pair 12 moved the class into `uk.xa0.tulkki.translation`, where
         * those tests already are, and the modifier is deliberately left alone rather than narrowed in
         * a commit that is already moving the class.
         */
        @JvmStatic
        fun inMemory(): TranslationSettings {
            val prefs = MapPrefs()
            val settings = TranslationSettings(prefs, prefs)
            instance = settings
            return settings
        }

        @Volatile private var instance: TranslationSettings? = null

        /** A starting cap, if the owner never touches it. Roughly 600 translated messages. */
        const val DEFAULT_DAILY_TOKEN_CAP = 100_000

        /** The preferences file this class owns. */
        const val PREFERENCES_FILE = "tulkki"

        /** The separate file the Keystore-backed key lives in. */
        internal const val SECRET_PREFERENCES_FILE = "tulkki_secrets"

        internal const val KEY_APP_LANGUAGE = "app_language"
        internal const val KEY_STUDY_LANGUAGE = "study_language"
        internal const val KEY_DAILY_TOKEN_CAP = "daily_token_cap"
        internal const val KEY_REVEAL_SUGGESTION_FIRST = "reveal_suggestion_first"
        internal const val KEY_SHOW_SECOND_HALF = "show_second_half"
        internal const val KEY_SHOW_CONCEALED_ORIGINAL = "show_concealed_original"
        internal const val KEY_CONCEAL_OWN_SECOND_HALF = "conceal_own_second_half"
        internal const val KEY_SHOW_BLURRED_ENGLISH = "show_blurred_english"
        internal const val KEY_UNBLUR_ENGLISH_ON_TAP = "unblur_english_on_tap"
        internal const val KEY_SHOW_ENGLISH_RETRANSLATION = "show_english_retranslation"
        internal const val KEY_API_KEY = "api_key"
        internal const val KEY_API_BASE_URL = "api_base_url"
        internal const val KEY_TRANSLATE_PROMPT = "prompt_translate"
        internal const val KEY_REVIEW_PROMPT = "prompt_review"
        internal const val KEY_GLOSS_PROMPT = "prompt_gloss"

        /**
         * The retry action's wording. It is stored like a prompt but is deliberately not one: it is UI
         * copy, and [RetryWording] and [PromptBook] keep it out of the cache identity.
         */
        internal const val KEY_RETRY_WORDING = "retry_wording"
        internal const val KEY_TOKENS_DAY = "tokens_day"
        internal const val KEY_TOKENS_USED = "tokens_used"
        internal const val KEY_PRICE_PEAK_CACHE_HIT = "price_peak_cache_hit"
        internal const val KEY_PRICE_PEAK_CACHE_MISS = "price_peak_cache_miss"
        internal const val KEY_PRICE_PEAK_OUTPUT = "price_peak_output"
        internal const val KEY_PUBLISHED_TARIFF = "published_tariff"
        internal const val KEY_ACTIVITY_DAY = "activity_day"
        internal const val KEY_ACTIVITY_TRANSLATED = "activity_translated"
        internal const val KEY_FAILURE_REASON = "last_failure_reason"
        internal const val KEY_FAILURE_DETAIL = "last_failure_detail"
        internal const val KEY_FAILURE_MESSAGE = "last_failure_message"
        internal const val KEY_FAILURE_AT = "last_failure_at"

        /**
         * The per-message send-failure reason. A prefix rather than a second store: the entry is written
         * when the row is marked a send failure and dropped when it stops being one, and the message's
         * own uuid is the whole address.
         */
        internal const val SEND_FAILURE_KEY_PREFIX = "send-failure:"

        /**
         * The per-message record of a message the owner chose to send as written. The fact is not a
         * failure and not a translation, so it is neither the send-failure record nor a message column:
         * it is the durable half of "this left the device untranslated, because the owner asked".
         */
        internal const val SENT_AS_WRITTEN_KEY_PREFIX = "sent-as-written:"
        /**
         * The default for <em>both</em> language settings: the device's current locale, read live.
         *
         * <p>An unset or blank language follows the device, and it goes on following it: this is asked on
         * every read rather than captured once, so a system locale change moves a language the owner has
         * not set. A language the owner writes is stored, and from then on the stored value is the answer
         * - a set language does not move when the locale does. There is no other default, and in
         * particular no hardcoded language: both settings share this one answer, so a fresh install is
         * the pair (locale, locale), which is off because the two are equal.
         *
         * <p>Normalised through [ComposerGate.normalize], the one spelling the predicate compares
         * in, so a locale tag is handed on in the same form a stored code would be. A locale that names
         * no language at all yields the empty string; the two settings are then empty and equal, which is
         * still the off pair rather than a language nobody chose.
         */
        @JvmStatic
        fun defaultLanguage(): String = ComposerGate.normalize(Locale.getDefault().getLanguage())

        /**
         * One language setting: the stored value, or the caller's fallback when it is blank or absent.
         * One helper behind both getters and the process-start read below, so a blank cannot mean two
         * things in two places - and the fallback is a parameter rather than the locale, because the two
         * sides no longer share one: the app language's fallback is [Interpreter.NONE] and the
         * study language's is the device's locale ([defaultLanguage]), which is re-read every
         * time, so an unset study language follows a system locale change while a stored one stands
         * still.
         */
        private fun language(preferences: Prefs, key: String, fallback: String): String {
            val language = preferences.getString(key, fallback)
            return if (language == null || language.javaTrim().isEmpty()) fallback
            else language.javaTrim()
        }

        /**
         * The same question, asked by a process start that cannot afford the instance.
         *
         * <p>[get] builds the Keystore-backed store for the DeepSeek key, and
         * `TulkkiApplication` may not pay that on every cold start - its own note says so, and
         * `WorkReArm` answers its own startup question the same way. The two languages are not in
         * that store: they are ordinary values in [PREFERENCES_FILE], so this reads them through
         * the same keys, the same two fallbacks (the sentinel and the locale) and the same blank rule as
         * [appLanguage] and [studyLanguage] - one [language] helper behind all
         * three, so the startup answer and the running answer cannot drift - and it never touches the
         * Keystore.
         *
         * <p>When the process already has the instance, that is the answer: the instance is the one truth
         * and it reads the same two keys. It is also what makes this method JVM-testable at all, since
         * `inMemory()` installs one.
         *
         * <p>Nothing else may call it. A caller with a running process has [interpreter], and
         * this exists for the one moment there is no instance yet.
         */
        @JvmStatic
        fun interpreterAtStartup(context: Context?): Interpreter {
            val local = instance
            if (local != null) {
                return local.interpreter()
            }
            if (context == null) {
                // No store to read, so nobody can have set either language: a fresh install's pair - no
                // app language and the device's locale - which is off on the sentinel.
                return Interpreter.of(Interpreter.NONE, defaultLanguage())
            }
            val app = context.applicationContext
            val base = app ?: context
            val preferences =
                    SharedPrefs(base.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE))
            return Interpreter.of(
                    language(preferences, KEY_APP_LANGUAGE, Interpreter.NONE),
                    language(preferences, KEY_STUDY_LANGUAGE, defaultLanguage()))
        }

        /**
         * Tulkki: drops the state that only existed while the interpreter was on, for the moment it is
         * observed off.
         *
         * <p>Two things go, and nothing else. The <strong>pending queue rows</strong> are work the
         * interpreter owed, and each carries the target language that was the app language when it was
         * queued ([TranslationService]); the pump classifies against that captured value, so a row
         * left behind would be translated into a language that is no longer the app language once the
         * interpreter came back. The <strong>last-failure record</strong> is the interpreter's own
         * account of a DeepSeek call, and off there is no call for it to describe.
         *
         * <p><strong>Nothing the owner could read is deleted.</strong> Message rows stay
         * `TRANSLATION_NONE` with their original bodies, the history watermark does not move, the
         * day's translated-message count is the account of work already done, and a held outgoing
         * `STATUS_WAITING` row is <em>not</em> deleted - an untranslated owner's draft is released
         * as typed (MIGRATION.md "Design: the interpreter off-switch" §2.4), because deleting it would
         * silently discard the owner's own unsent words. The seam's own type is half that guarantee: the
         * store is asked as a [TranslationQueue.Store], which has no statement that can reach the
         * `messages` table at all.
         *
         * <p>It <strong>spends nothing</strong>: a `DELETE` and four preference removals, no API
         * call, no ledger row, no tokens, nothing counted against the cap. It is called from the two
         * places the flip can be observed - the settings screen's write of either language, which catches
         * a flip made while the process runs (`TranslationSettingsStore.putLanguage`), and the
         * process start, which catches one made while it was dead
         * ([interpreterAtStartup], `TulkkiApplication.onCreate`). Off is a state, so this is
         * idempotent: it is correct to run it when there is nothing to clear.
         */
        @JvmStatic
        fun clearInterpreterState(context: Context?) {
            if (context == null) {
                return
            }
            val app = context.applicationContext
            val base = app ?: context
            clearInterpreterState(
                    TranslationStore(base),
                    TranslationActivity(
                            ActivityStore(
                                    SharedPrefs(
                                            base.getSharedPreferences(
                                                    PREFERENCES_FILE, Context.MODE_PRIVATE)))))
        }

        /**
         * The same clear, over the two stores it is made of: the queue's carried work and the failure
         * record, in that order and nothing else.
         *
         * <p>Split out for the reason [interpreterAtStartup] is written to be callable without a
         * `Context`: this is the composition the JVM suite has to drive, and the production entry
         * point above is the only caller. It deliberately takes the <em>store</em> and the record rather
         * than a `TranslationStore`, so neither half of it can name a message row.
         */
        @JvmStatic
        fun clearInterpreterState(queue: TranslationQueue.Store, activity: TranslationActivity) {
            queue.clearPending()
            activity.clearFailure()
        }

        /**
         * The API base URL in force, for callers that have no `Context` to read the settings with:
         * the usage screen builds its DeepSeek client from the key alone. Every entry point - the receive
         * path, the send path, both screens - reads the settings before a request is made, so the
         * configured value is in place by then; a process whose settings have never been read, and an
         * install that never touched the setting, get exactly the real API.
         */
        @JvmStatic
        fun apiBaseUrlOrDefault(): String {
            val local = instance
            return local?.apiBaseUrl() ?: DeepSeekClient.DEFAULT_BASE_URL
        }

        /**
         * The process-wide instance, or `null` before any entry point has read the settings.
         *
         * <p>For [PromptBook], which is asked for an instruction at request time and by three cache
         * keys that have no `Context`: a process that never read the settings, and an install that
         * never edited a prompt, get the shipped wording - which is the same thing as far as any of those
         * callers can tell.
         */
        @JvmStatic
        fun current(): TranslationSettings? = instance

    }

    /** The slice of SharedPreferences this class uses, so the settings can be faked in tests. */
    interface Prefs {
        fun available(): Boolean

        fun getString(key: String, fallback: String?): String?

        fun putString(key: String, value: String?)

        fun getInt(key: String, fallback: Int): Int

        fun putInt(key: String, value: Int)

        fun getLong(key: String, fallback: Long): Long

        fun putLong(key: String, value: Long)

        fun getBoolean(key: String, fallback: Boolean): Boolean

        fun putBoolean(key: String, value: Boolean)

        /**
         * Drops a stored value. Needed by the one clear Tulkki does - the interpreter's last-failure
         * record ([clearInterpreterState]) - because a value written back as an empty string
         * is still a value, and the record's absence is read as "nothing has failed".
         */
        fun remove(key: String)
    }

    private class SharedPrefs(private val prefs: SharedPreferences) : Prefs {
        override fun available(): Boolean = true

        override fun getString(key: String, fallback: String?): String? =
                prefs.getString(key, fallback)

        override fun putString(key: String, value: String?) {
            prefs.edit().putString(key, value).apply()
        }

        override fun getInt(key: String, fallback: Int): Int = prefs.getInt(key, fallback)

        override fun putInt(key: String, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }

        override fun getLong(key: String, fallback: Long): Long = prefs.getLong(key, fallback)

        override fun putLong(key: String, value: Long) {
            prefs.edit().putLong(key, value).apply()
        }

        override fun getBoolean(key: String, fallback: Boolean): Boolean =
                prefs.getBoolean(key, fallback)

        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }
    }

    /**
     * What stands in when the Keystore store cannot be opened. Reads are empty and writes are
     * refused, loudly, so a missing key is visible as a missing key and never as a stored one.
     */
    private class UnavailablePrefs : Prefs {
        override fun available(): Boolean = false

        override fun getString(key: String, fallback: String?): String? = fallback

        override fun putString(key: String, value: String?) {
            Log.e(
                    TAG,
                    "refusing to save a setting: the Keystore-backed store is unavailable and the"
                            + " value will not be written in the clear")
        }

        override fun getInt(key: String, fallback: Int): Int = fallback

        override fun putInt(key: String, value: Int) {
            Log.e(TAG, "refusing to save " + key + ": the store is unavailable")
        }

        override fun getLong(key: String, fallback: Long): Long = fallback

        override fun putLong(key: String, value: Long) {
            Log.e(TAG, "refusing to save " + key + ": the store is unavailable")
        }

        override fun getBoolean(key: String, fallback: Boolean): Boolean = fallback

        override fun putBoolean(key: String, value: Boolean) {
            Log.e(TAG, "refusing to save " + key + ": the store is unavailable")
        }

        override fun remove(key: String) {
            // Nothing to remove: this store never held anything.
        }
    }

    class MapPrefs : Prefs {
        private val values: MutableMap<String, Any?> = HashMap()

        override fun available(): Boolean = true

        override fun getString(key: String, fallback: String?): String? {
            val value = values[key]
            return if (value is String) value else fallback
        }

        override fun putString(key: String, value: String?) {
            values[key] = value
        }

        override fun getInt(key: String, fallback: Int): Int {
            val value = values[key]
            return if (value is Int) value else fallback
        }

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun getLong(key: String, fallback: Long): Long {
            val value = values[key]
            return if (value is Long) value else fallback
        }

        override fun putLong(key: String, value: Long) {
            values[key] = value
        }

        override fun getBoolean(key: String, fallback: Boolean): Boolean {
            val value = values[key]
            return if (value is Boolean) value else fallback
        }

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    /** Today's token count, kept in the ordinary preferences file. */
    private class CounterStore(private val prefs: Prefs) : DailyTokenCounter.Store {
        override fun day(): String? = prefs.getString(KEY_TOKENS_DAY, null)

        override fun tokens(): Int = prefs.getInt(KEY_TOKENS_USED, 0)

        override fun save(day: String?, tokens: Int) {
            prefs.putString(KEY_TOKENS_DAY, day)
            prefs.putInt(KEY_TOKENS_USED, tokens)
        }
    }

    /**
     * Today's translated-message count and the last failure, in the same ordinary preferences file
     * as the token count. None of it is secret, so none of it needs the Keystore-backed store.
     */
    private class ActivityStore(private val prefs: Prefs) : TranslationActivity.Store {
        override fun day(): String? = prefs.getString(KEY_ACTIVITY_DAY, null)

        override fun translated(): Int = prefs.getInt(KEY_ACTIVITY_TRANSLATED, 0)

        override fun saveTranslated(day: String?, translated: Int) {
            prefs.putString(KEY_ACTIVITY_DAY, day)
            prefs.putInt(KEY_ACTIVITY_TRANSLATED, translated)
        }

        override fun failure(): TranslationActivity.Failure? {
            val stored = prefs.getString(KEY_FAILURE_REASON, null) ?: return null
            val reason =
                    try {
                        HeldSend.HoldReason.valueOf(stored)
                    } catch (e: IllegalArgumentException) {
                        // A reason this build no longer has - a downgrade, or a renamed constant - is not a
                        // crash and not a reason to keep showing something we cannot name.
                        Log.w(TAG, "dropping an unreadable last-failure reason: " + stored)
                        return null
                    }
            return TranslationActivity.Failure(
                    reason,
                    prefs.getString(KEY_FAILURE_DETAIL, null),
                    prefs.getString(KEY_FAILURE_MESSAGE, null),
                    prefs.getLong(KEY_FAILURE_AT, 0L))
        }

        override fun saveFailure(failure: TranslationActivity.Failure) {
            prefs.putString(KEY_FAILURE_REASON, failure.reason?.name)
            prefs.putString(KEY_FAILURE_DETAIL, failure.detail)
            prefs.putString(KEY_FAILURE_MESSAGE, failure.messageUuid)
            prefs.putLong(KEY_FAILURE_AT, failure.at)
        }

        /**
         * All four keys, because the record is four values and only one of them is what the reader
         * tests for ([failure]). Removing the reason alone would read the same - nothing
         * consults the other three except a `saveFailure`, which rewrites them - so this is not
         * a behaviour the suite can pin, and it is not claimed as one. It is the residue it removes
         * that matters: DeepSeek's own error text and the message uuid should not be left sitting in
         * the preferences file, readable by anything that opens it, for a mode that is off.
         */
        override fun clearFailure() {
            prefs.remove(KEY_FAILURE_REASON)
            prefs.remove(KEY_FAILURE_DETAIL)
            prefs.remove(KEY_FAILURE_MESSAGE)
            prefs.remove(KEY_FAILURE_AT)
        }
    }
}

/** The log tag every refusal in this file carries. */
private const val TAG = "Tulkki"

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 *
 * <p>A stored language code, a base URL, a price field and the DeepSeek key are all Java-trimmed text
 * in the Java this replaces, and Kotlin's `trim()` strips a different set. The Java call is kept.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
