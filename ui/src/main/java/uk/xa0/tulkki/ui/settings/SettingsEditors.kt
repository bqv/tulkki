package uk.xa0.tulkki.ui.settings

import androidx.annotation.StringRes
import uk.xa0.tulkki.translation.PromptBook
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TranslationSettingsStore

/**
 * What tapping a row does, decided by the row and nothing else.
 *
 * <p>One place knows which key is a switch and which is a field - the same knowledge
 * `TranslationSettingsStore`'s four switches hold on the write side - so the screen cannot grow a
 * second opinion about a row, and `SettingsNavigationTest` fails if a row of the page resolves to
 * [NONE]. That is deliberate: an unrecognised row is a red test rather than a row that renders and
 * does nothing.
 */
enum class RowEditor {
    /** A category heading: drawn, not touched. */
    HEADER,

    /** The off state's one line: drawn, stores nothing. */
    LINE,

    /** A row that opens a screen of its own. */
    SCREEN,

    /** A stored boolean, drawn as a switch. */
    TOGGLE,

    /** A stored string, edited in a one-line field. */
    TEXT,

    /** A stored integer, edited in a digits-only field. */
    NUMBER,

    /** A stored language code, chosen from the detectors' own list. */
    LANGUAGE,

    /** One of the three instructions, edited in the multi-line field. */
    PROMPT,

    /** A row this model does not know: a defect, and `SettingsNavigationTest` says so. */
    NONE,
}

/**
 * The rows' values, read off the state by the key that identifies them - the row's own identity is its
 * key (§3.0.1's rule, kept for settings), so the screen asks for the key it already has rather than
 * carrying a second copy of the value on the row.
 */
object SettingsEditors {

    /** Which editor a row's key opens; [RowEditor.NONE] is the defect the test refuses. */
    fun editorFor(row: SettingsRow): RowEditor =
        when (row.kind) {
            SettingsRow.RowKind.HEADER -> RowEditor.HEADER
            SettingsRow.RowKind.LINE -> RowEditor.LINE
            SettingsRow.RowKind.SCREEN -> RowEditor.SCREEN
            SettingsRow.RowKind.SETTING -> settingEditor(row.key)
        }

    private fun settingEditor(key: String?): RowEditor =
        when (key) {
            TranslationSettingsStore.KEY_APP_LANGUAGE,
            TranslationSettingsStore.KEY_STUDY_LANGUAGE -> RowEditor.LANGUAGE
            TranslationSettingsStore.KEY_DAILY_TOKEN_CAP -> RowEditor.NUMBER
            TranslationSettingsStore.KEY_API_KEY,
            TranslationSettingsStore.KEY_API_BASE_URL -> RowEditor.TEXT
            // The retry wording is UI copy the owner may edit, not one of the three instructions: it
            // is a one-line field and must never be minted as a "prompt" row.
            TranslationSettingsStore.KEY_RETRY_WORDING -> RowEditor.TEXT
            TranslationSettingsStore.KEY_TRANSLATE_PROMPT,
            TranslationSettingsStore.KEY_REVIEW_PROMPT,
            TranslationSettingsStore.KEY_GLOSS_PROMPT -> RowEditor.PROMPT
            TranslationSettingsStore.KEY_REVEAL_SUGGESTION_FIRST,
            TranslationSettingsStore.KEY_SHOW_SECOND_HALF,
            TranslationSettingsStore.KEY_SHOW_CONCEALED_ORIGINAL,
            TranslationSettingsStore.KEY_CONCEAL_OWN_SECOND_HALF,
            TranslationSettingsStore.KEY_SHOW_BLURRED_ENGLISH,
            TranslationSettingsStore.KEY_UNBLUR_ENGLISH_ON_TAP,
            TranslationSettingsStore.KEY_SHOW_ENGLISH_RETRANSLATION -> RowEditor.TOGGLE
            else -> RowEditor.NONE
        }

    /**
     * A switch row's own value, or null for a row that is not a switch. The null is half the check:
     * a [RowEditor.TOGGLE] row with no value here is a switch the screen could not draw, and the test
     * fails on it.
     */
    fun checked(state: TulkkiSettingsState, key: String?): Boolean? =
        when (key) {
            TranslationSettingsStore.KEY_REVEAL_SUGGESTION_FIRST -> state.revealSuggestionFirst
            TranslationSettingsStore.KEY_SHOW_SECOND_HALF -> state.showSecondHalf
            TranslationSettingsStore.KEY_SHOW_CONCEALED_ORIGINAL -> state.showConcealedOriginal
            TranslationSettingsStore.KEY_CONCEAL_OWN_SECOND_HALF -> state.concealOwnSecondHalf
            TranslationSettingsStore.KEY_SHOW_BLURRED_ENGLISH -> state.showBlurredEnglish
            TranslationSettingsStore.KEY_UNBLUR_ENGLISH_ON_TAP -> state.unblurEnglishOnTap
            TranslationSettingsStore.KEY_SHOW_ENGLISH_RETRANSLATION -> state.showEnglishRetranslation
            else -> null
        }

    /**
     * The title its editor opens under. Android's own dialogs had their own strings for five of these
     * (`*_dialog`), and they are kept: a picker titled "App language" and a field titled "DeepSeek API
     * key" are the words the owner has been reading, and the three instructions are titled as their
     * rows are, which is what `android:dialogTitle` said too.
     */
    @StringRes
    fun dialogTitle(row: SettingsRow): Int =
        when (row.key) {
            TranslationSettingsStore.KEY_API_KEY -> R.string.tulkki_api_key_dialog
            TranslationSettingsStore.KEY_API_BASE_URL -> R.string.tulkki_api_base_url_dialog
            TranslationSettingsStore.KEY_DAILY_TOKEN_CAP -> R.string.tulkki_daily_cap_dialog
            TranslationSettingsStore.KEY_APP_LANGUAGE -> R.string.tulkki_app_language_dialog
            TranslationSettingsStore.KEY_STUDY_LANGUAGE -> R.string.tulkki_study_language_dialog
            else -> row.title
        }

    /**
     * What the row's editor opens holding - **the value in force**, not a stored string:
     *
     * <ul>
     *   <li>the key is empty, because it is never echoed: the row says whether one is stored and the
     *       field asks for a new one;
     *   <li>the base URL, the cap and both languages are the state's own readings, so the field shows
     *       what is actually in use (a language nobody has chosen is the device's locale);
     *   <li>an instruction is `PromptBook.template`, the same place the request is built from - so a
     *       field that opens on the shipped wording writes that wording back unchanged and changes
     *       nothing, cache namespace included.
     * </ul>
     */
    fun initialFor(state: TulkkiSettingsState, row: SettingsRow): String =
        when (row.key) {
            TranslationSettingsStore.KEY_API_KEY -> ""
            TranslationSettingsStore.KEY_API_BASE_URL -> state.baseUrl
            TranslationSettingsStore.KEY_DAILY_TOKEN_CAP -> state.dailyTokenCap.toString()
            TranslationSettingsStore.KEY_RETRY_WORDING -> state.retryWording
            TranslationSettingsStore.KEY_APP_LANGUAGE -> state.appLanguage
            TranslationSettingsStore.KEY_STUDY_LANGUAGE -> state.studyLanguage
            TranslationSettingsStore.KEY_TRANSLATE_PROMPT -> PromptBook.template(PromptBook.Kind.TRANSLATE)
            TranslationSettingsStore.KEY_REVIEW_PROMPT -> PromptBook.template(PromptBook.Kind.REVIEW)
            TranslationSettingsStore.KEY_GLOSS_PROMPT -> PromptBook.template(PromptBook.Kind.GLOSS)
            else -> ""
        }
}
