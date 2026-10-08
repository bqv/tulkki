package uk.xa0.tulkki.ui

import android.content.Context
import android.util.Log

import androidx.preference.PreferenceDataStore

import uk.xa0.tulkki.translation.PromptBook
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.host.UiHost

/**
 * The settings screen's storage: [TranslationSettings], not a preferences file of its own.
 *
 * <p>Every read and write the screen makes goes through the settings class, so there is exactly one
 * copy of each value and the receive path, the send path and this screen cannot disagree about the app
 * language, the cap or the composer gate's reveal order. The values are read at the moment they are
 * shown, never held here.
 *
 * <p>It also means the key cannot leak into the ordinary preferences file: the only writer this class
 * has is the settings class, which puts the key in the Keystore-backed store. The key is never logged
 * and never used as a summary; the screen only ever asks whether one is there.
 */
class TranslationSettingsStore(context: Context) : PreferenceDataStore() {

    private val context: Context = context.applicationContext ?: context

    /** The settings, read fresh every time so a value changed elsewhere is seen here at once. */
    private fun settings(): TranslationSettings {
        return TranslationSettings.get(context)
    }

    override fun putString(key: String, value: String?) {
        when (key) {
            KEY_API_KEY -> putApiKey(value)
            KEY_API_BASE_URL -> settings().setApiBaseUrl(value)
            KEY_APP_LANGUAGE -> putLanguage(KEY_APP_LANGUAGE, value)
            KEY_STUDY_LANGUAGE -> putLanguage(KEY_STUDY_LANGUAGE, value)
            KEY_DAILY_TOKEN_CAP -> putDailyTokenCap(value)
            KEY_PRICE_CACHE_HIT -> settings().setPeakCacheHitPrice(value)
            KEY_PRICE_CACHE_MISS -> settings().setPeakCacheMissPrice(value)
            KEY_PRICE_OUTPUT -> settings().setPeakOutputPrice(value)
            KEY_TRANSLATE_PROMPT -> settings().setTranslatePrompt(value)
            KEY_REVIEW_PROMPT -> settings().setReviewPrompt(value)
            KEY_GLOSS_PROMPT -> settings().setGlossPrompt(value)
            // UI copy, not an instruction: stored verbatim like a prompt, but PromptBook never reads
            // it, so editing it moves no cache key.
            KEY_RETRY_WORDING -> settings().setRetryWording(value)
            else -> throw IllegalArgumentException("unknown Tulkki setting: " + key)
        }
    }

    /**
     * Writes one of the two languages, and - when that write is what switched the interpreter off -
     * drops the state that only meant something while it was on and cancels the queue's scheduled
     * records.
     *
     * <p>This is the on&rarr;off transition as the running app sees it, and it is the only place that
     * can see it: these two keys are the only production writers of either language, so a comparison
     * of the derived interpreter across the write is the literal moment, not an inference from it. The
     * other situation - a flip made while the process was dead - has no write to catch and is caught at
     * the next process start instead (see `TulkkiApplication.onCreate`), which cancels and calls the
     * same [TranslationSettings.clearInterpreterState].
     *
     * <p>Both halves of the transition are owed here. Dropping the carried state is the
     * [TranslationSettings.clearInterpreterState] half; cancelling is the half that stops a pending
     * `tulkki-translation-later` waking the process at its due time after the interpreter is off. The
     * cancel cannot be named directly - it lives in `:app`'s `TranslationWork`, which `:ui` may not
     * depend on - so it goes through the seam that already carries the mirror call,
     * [UiHost.cancelTranslation], beside [UiHost.kickTranslation].
     *
     * <p>Only on&rarr;off runs any of it. Writing a language while the interpreter is already off, or
     * turning it on, is not a transition out of anything and has nothing to clear or cancel - which is
     * also what keeps a fresh enable from cancelling the work it has just enqueued; the clear is
     * idempotent either way, and the comparison is here so that the ordinary case does no storage work
     * at all.
     *
     * <p>A write here is also what stops a language following the device: before it the key is blank
     * and the settings read the current locale, and after it the stored value is the answer even when
     * the locale moves. That is why nothing else in the screen may write these two keys.
     *
     * <p>The transition call itself is a <em>device seam</em>: this class has no JVM test - the module
     * hosts no Activity, no Fragment and no Robolectric, so a [PreferenceDataStore] over a real
     * `Context` and a real database cannot be built here. What is JVM-pinned is the decision the write
     * makes (`TranslationSettingsStoreTest`, over [onLanguageWrite]), the clear it calls
     * (`InterpreterOffSpendsNothingTest`) and the names the cancel takes (`TranslationWorkTest`).
     */
    private fun putLanguage(key: String, value: String?) {
        val wasInterpreting = settings().interpreter().enabled()
        if (KEY_APP_LANGUAGE == key) {
            settings().setAppLanguage(value)
        } else {
            settings().setStudyLanguage(value)
        }
        onLanguageWrite(
                wasInterpreting,
                settings().interpreter().enabled(),
                Runnable { TranslationSettings.clearInterpreterState(context) },
                Runnable { UiHost.installed().cancelTranslation(context) })
    }

    /**
     * Stores the key, and then starts the queue.
     *
     * <p>Without a key nothing can be sent, so nothing is: received and tapped messages stay queued and
     * are not failed (failing them would be a lie - the key is what decides). But a queue with no key
     * has no wake-up either, so the key arriving is the only event that can move them, and without this
     * they would sit there until the app was restarted or the daily worker ran. That is exactly the
     * shape of "I set the key and nothing happened", so the write kicks the queue. The key is stored
     * before the kick, so a run that starts immediately already sees it.
     */
    private fun putApiKey(value: String?) {
        settings().setApiKey(value)
        if (!settings().hasApiKey()) {
            return
        }
        UiHost.installed().kickTranslation(context)
    }

    override fun getString(key: String, defValue: String?): String? {
        return when (key) {
            KEY_API_KEY -> settings().apiKey()
            KEY_API_BASE_URL -> settings().apiBaseUrl()
            // The value in force, never a default of this screen's own: with neither language set
            // that is the device's current locale, so the picker opens on the language the app is
            // actually using and a system locale change is visible here the moment it happens.
            KEY_APP_LANGUAGE -> settings().appLanguage()
            KEY_STUDY_LANGUAGE -> settings().studyLanguage()
            KEY_DAILY_TOKEN_CAP -> settings().dailyTokenCap().toString()
            // The prices open holding the value in force, the embedded default included, so a blank
            // field is never what the editor shows while the arithmetic is using 0.04/2/8.
            KEY_PRICE_CACHE_HIT -> settings().peakCacheHitPrice()
            KEY_PRICE_CACHE_MISS -> settings().peakCacheMissPrice()
            KEY_PRICE_OUTPUT -> settings().peakOutputPrice()
            // The *effective* instruction, not the stored one: a prompt the owner has not written is
            // the shipped wording, and the editor has to open holding the words it would send. Saving
            // them back unchanged stores the default text, which PromptBook reads as the shipped
            // wording again - so it changes nothing, cache namespace included.
            KEY_TRANSLATE_PROMPT -> PromptBook.template(PromptBook.Kind.TRANSLATE)
            KEY_REVIEW_PROMPT -> PromptBook.template(PromptBook.Kind.REVIEW)
            KEY_GLOSS_PROMPT -> PromptBook.template(PromptBook.Kind.GLOSS)
            // The value in force, the shipped wording included, so the field opens holding what the
            // button actually says and a blank save stores the default text unchanged.
            KEY_RETRY_WORDING -> settings().retryWording()
            else -> defValue
        }
    }

    override fun putInt(key: String, value: Int) {
        if (KEY_DAILY_TOKEN_CAP == key) {
            settings().setDailyTokenCap(value)
            return
        }
        throw IllegalArgumentException("unknown Tulkki setting: " + key)
    }

    override fun getInt(key: String, defValue: Int): Int {
        return if (KEY_DAILY_TOKEN_CAP == key) settings().dailyTokenCap() else defValue
    }

    override fun putBoolean(key: String, value: Boolean) {
        if (KEY_REVEAL_SUGGESTION_FIRST == key) {
            settings().setRevealSuggestionFirst(value)
            return
        }
        if (KEY_SHOW_SECOND_HALF == key) {
            settings().setShowSecondHalf(value)
            return
        }
        if (KEY_SHOW_CONCEALED_ORIGINAL == key) {
            settings().setShowConcealedOriginal(value)
            return
        }
        if (KEY_CONCEAL_OWN_SECOND_HALF == key) {
            settings().setConcealOwnSecondHalf(value)
            return
        }
        if (KEY_SHOW_BLURRED_ENGLISH == key) {
            settings().setShowBlurredEnglish(value)
            return
        }
        if (KEY_UNBLUR_ENGLISH_ON_TAP == key) {
            settings().setUnblurEnglishOnTap(value)
            return
        }
        if (KEY_SHOW_ENGLISH_RETRANSLATION == key) {
            settings().setShowEnglishRetranslation(value)
            return
        }
        throw IllegalArgumentException("unknown Tulkki setting: " + key)
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        if (KEY_REVEAL_SUGGESTION_FIRST == key) {
            return settings().revealSuggestionFirst()
        }
        if (KEY_SHOW_SECOND_HALF == key) {
            return settings().showSecondHalf()
        }
        if (KEY_SHOW_CONCEALED_ORIGINAL == key) {
            // The value in force, so an untouched install shows the switch where it actually is: the
            // settings class reads an unset row as "whatever Show the second half says", and this
            // screen must show that answer rather than a default of its own.
            return settings().showConcealedOriginal()
        }
        if (KEY_CONCEAL_OWN_SECOND_HALF == key) {
            return settings().concealOwnSecondHalf()
        }
        if (KEY_SHOW_BLURRED_ENGLISH == key) {
            return settings().showBlurredEnglish()
        }
        if (KEY_UNBLUR_ENGLISH_ON_TAP == key) {
            return settings().unblurEnglishOnTap()
        }
        if (KEY_SHOW_ENGLISH_RETRANSLATION == key) {
            return settings().showEnglishRetranslation()
        }
        return defValue
    }

    /**
     * The number field is digits-only, so anything else is a paste or an empty dialog. Keeping the old
     * cap is the honest outcome: the summary still shows the cap that is actually in force.
     */
    private fun putDailyTokenCap(value: String?) {
        if (value == null) {
            return
        }
        try {
            // Java's String.trim, which strips every char at or below the space.
            settings().setDailyTokenCap(value.trim { it <= ' ' }.toInt())
        } catch (e: NumberFormatException) {
            Log.w(TAG, "ignoring a daily token cap that is not a number")
        }
    }

    companion object {

        // The raw preference keys. `res/xml/preferences_tulkki.xml` used to be the second spelling of
        // these; it was deleted with ui-4 part 2b, and the screen that writes them is now Compose
        // (`uk.xa0.tulkki.ui.settings`), audited against this source by `SettingsPageTest`. A key the
        // screen does not know is a bug, and is refused rather than silently dropped.
        const val KEY_API_KEY = "tulkki_api_key"
        const val KEY_APP_LANGUAGE = "tulkki_app_language"
        const val KEY_STUDY_LANGUAGE = "tulkki_study_language"
        const val KEY_DAILY_TOKEN_CAP = "tulkki_daily_token_cap"
        const val KEY_PRICE_CACHE_HIT = "tulkki_price_cache_hit"
        const val KEY_PRICE_CACHE_MISS = "tulkki_price_cache_miss"
        const val KEY_PRICE_OUTPUT = "tulkki_price_output"
        const val KEY_REVEAL_SUGGESTION_FIRST = "tulkki_reveal_suggestion_first"
        const val KEY_API_BASE_URL = "tulkki_api_base_url"
        const val KEY_SHOW_SECOND_HALF = "tulkki_show_second_half"
        const val KEY_SHOW_CONCEALED_ORIGINAL = "tulkki_show_concealed_original"
        const val KEY_CONCEAL_OWN_SECOND_HALF = "tulkki_conceal_own_second_half"
        const val KEY_SHOW_BLURRED_ENGLISH = "tulkki_show_blurred_english"
        const val KEY_UNBLUR_ENGLISH_ON_TAP = "tulkki_unblur_english_on_tap"
        const val KEY_SHOW_ENGLISH_RETRANSLATION = "tulkki_show_english_retranslation"
        const val KEY_TRANSLATE_PROMPT = "tulkki_prompt_translate"
        const val KEY_REVIEW_PROMPT = "tulkki_prompt_review"
        const val KEY_GLOSS_PROMPT = "tulkki_prompt_gloss"
        const val KEY_RETRY_WORDING = "tulkki_retry_wording"

        private const val TAG = "Tulkki"

        /**
         * The on&rarr;off transition, as the settings screen performs it: only the write that left the
         * interpreter off runs the two actions, and it runs both - the carried work is dropped and the
         * queue's scheduled records are cancelled. Every other write runs neither, which is what keeps
         * a fresh enable from cancelling the work it has just enqueued.
         *
         * <p>A method rather than an `if` inside [putLanguage] because this is the part of the store a
         * JVM test can drive: the store itself needs a real Android `Context` and the clear reaches a
         * real database, but "which write runs which action" is a decision about two booleans, and the
         * suite pins it here rather than leaving the transition untested.
         *
         * <p>The actions are handed in rather than called here so the decision - and only the
         * decision - is what a test drives; in production they are
         * [TranslationSettings.clearInterpreterState] and [UiHost.cancelTranslation]. The Java declared
         * this package-private; Kotlin has no package-private, so it is public here and the JVM test
         * still names it the same way.
         */
        @JvmStatic
        fun onLanguageWrite(
                wasInterpreting: Boolean,
                nowInterpreting: Boolean,
                dropCarriedWork: Runnable,
                cancelScheduledWork: Runnable) {
            if (!wasInterpreting || nowInterpreting) {
                return
            }
            dropCarriedWork.run()
            cancelScheduledWork.run()
        }
    }
}
