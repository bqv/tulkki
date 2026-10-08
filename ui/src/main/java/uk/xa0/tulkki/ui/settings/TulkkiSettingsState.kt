package uk.xa0.tulkki.ui.settings

import uk.xa0.tulkki.translation.PromptBook
import uk.xa0.tulkki.translation.RetryWording

/**
 * Tulkki's own settings page, as the facts its rows are a function of.
 *
 * <p>docs/MIGRATION.md "Design: the Compose UI" §3.1 gives the state and §3.4 item 5 the rule its rows
 * obey: "**Rows that cannot apply are removed, not greyed** ... by predicate." Both are here, and
 * [SettingsPage.rows] is the one place either is decided - the same predicate the engine consults for
 * [interpreter], so the screen and the engine cannot drift about which mode the app is in.
 *
 * <p>**Nothing is read here.** Every field is something a host has already read from
 * `TranslationSettings` (or from the platform's locale), and the value-dependent sentence under a row
 * is computed from these fields rather than by a second trip to the store - which is what makes the
 * whole page a JVM-testable value with no `Context`, no `Preference` and no Android.
 *
 * <p>[promptEdited] and [promptLength] are the two readings the prompts' own summaries need. They are
 * carried rather than looked up because `PromptBook.template` reaches the settings store, and a
 * `Context` is exactly what this type does not have.
 */
data class TulkkiSettingsState(
    /** Whether the interpreter is on: the same predicate the engine consults, off when the pair is equal. */
    val interpreter: Boolean,
    /** The app language in force - the stored value, or the device's locale while none is stored. */
    val appLanguage: String,
    /** The study language in force, read the same way. */
    val studyLanguage: String,
    /** Whether a DeepSeek key is stored. The key itself is never carried and never shown. */
    val keyPresent: Boolean,
    /** Whether the Keystore-backed store can be opened at all; false refuses the key rather than storing it in the clear. */
    val keyStorageAvailable: Boolean,
    /** Where DeepSeek is reached. */
    val baseUrl: String,
    /** The daily token cap in tokens; `DailyTokenCounter.UNLIMITED` means no cap. */
    val dailyTokenCap: Int,
    val revealSuggestionFirst: Boolean,
    val showSecondHalf: Boolean,
    /** The received original's own switch; its value in force, which follows the global one while unset. */
    val showConcealedOriginal: Boolean,
    val concealOwnSecondHalf: Boolean,
    /** The English row's master switch. */
    val showBlurredEnglish: Boolean,
    val unblurEnglishOnTap: Boolean,
    val showEnglishRetranslation: Boolean,
    /** For each instruction, whether the wording in force is the owner's rather than the shipped one. */
    val promptEdited: Map<PromptBook.Kind, Boolean>,
    /** For each instruction, the length of the wording in force, as the row's summary counts it. */
    val promptLength: Map<PromptBook.Kind, Int>,
    /**
     * The held send's retry action in force - UI copy the owner may edit, whose blank field is the
     * shipped wording. Carried as its own field, never as one of [promptEdited]/[promptLength]'s
     * kinds, because it is not a prompt and is not part of any cache identity.
     */
    val retryWording: String = RetryWording.SHIPPED,
)
