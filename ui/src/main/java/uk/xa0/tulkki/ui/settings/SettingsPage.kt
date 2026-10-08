package uk.xa0.tulkki.ui.settings

import java.text.NumberFormat
import uk.xa0.tulkki.translation.ConversationLanguage
import uk.xa0.tulkki.translation.DailyTokenCounter
import uk.xa0.tulkki.translation.PromptBook
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.TulkkiSettingsRows
import uk.xa0.tulkki.ui.TranslationSettingsStore

/**
 * Tulkki's settings page as a function of its state - §3.4's "**Rows that cannot apply are removed,
 * not greyed** (`IDEA-SCAN §5.7`), by predicate", and §3.1's "the screen reduces to a short page" when
 * the interpreter is off.
 *
 * <p>**The page is the screen's whole model, and the live screen draws it.** `TulkkiSettingsFragment`
 * reads the state, [SettingsScreen] draws these rows, and `preferences_tulkki.xml` was deleted with the
 * commit that made this the page - so there is no tree to go stale between rebinds, which is what the
 * old `onCreatePreferences`-only build could not avoid.
 *
 * <p>Two things this page deliberately is not. It is not the raw-key store: the keys come from
 * [TranslationSettingsStore]'s own constants, and `SettingsPageTest` audits that every row's key is one
 * the store defines *and* routes in both directions - "a Compose screen must keep writing the same raw
 * keys" (§3.4). And it carries no message text: the failures screen's §3.2 body exception belongs to
 * `ui-5`'s row, which is a list of failures and not a setting, so nothing here can grow one.
 */
object SettingsPage {

    /** The two container rows the page names and the store does not know. */
    const val KEY_USAGE = "tulkki_usage"

    const val KEY_FAILURES = "tulkki_failures"

    /** The one informative line the off state adds; it stores nothing, so it has no key. */
    const val KEY_OFF_LINE = "tulkki_interpreter_off_line"

    /**
     * The page, in the order it draws its rows - `SettingsPageTest` freezes that order and the raw key
     * and title of every row, so a row added or renamed on one side without the other is a red test
     * rather than a screen that quietly disagrees with its own contract.
     */
    fun rows(state: TulkkiSettingsState): List<SettingsRow> {
        val rows = ArrayList<SettingsRow>()
        rows.add(SettingsRow(key = null, title = R.string.tulkki_category_everything, kind = SettingsRow.RowKind.HEADER))
        rows.add(language(TranslationSettingsStore.KEY_APP_LANGUAGE, R.string.tulkki_app_language, state.appLanguage, R.string.tulkki_app_language_summary, R.string.tulkki_app_language_off_summary))
        rows.add(language(TranslationSettingsStore.KEY_STUDY_LANGUAGE, R.string.tulkki_study_language, state.studyLanguage, R.string.tulkki_study_language_summary, R.string.tulkki_study_language_off_summary))
        if (!state.interpreter) {
            rows.add(SettingsRow(key = null, title = R.string.tulkki_interpreter_off_line, kind = SettingsRow.RowKind.LINE))
            return rows
        }
        rows.add(
            SettingsRow(
                TranslationSettingsStore.KEY_REVEAL_SUGGESTION_FIRST,
                R.string.tulkki_reveal_first,
                SettingSummary.Plain(
                    if (state.revealSuggestionFirst) R.string.tulkki_reveal_first_on else R.string.tulkki_reveal_first_off,
                ),
            )
        )
        rows.add(apiKey(state))
        rows.add(
            SettingsRow(
                TranslationSettingsStore.KEY_API_BASE_URL,
                R.string.tulkki_api_base_url,
                SettingSummary.Formatted(R.string.tulkki_api_base_url_summary, listOf(state.baseUrl)),
            )
        )
        rows.add(SettingsRow(TranslationSettingsStore.KEY_DAILY_TOKEN_CAP, R.string.tulkki_daily_cap, capSummary(state.dailyTokenCap)))
        rows.add(screen(KEY_USAGE, R.string.tulkki_usage, R.string.tulkki_usage_summary))
        rows.add(screen(KEY_FAILURES, R.string.tulkki_failures, R.string.tulkki_failures_summary))
        rows.add(screen(TulkkiSettingsRows.KEY_TOP_UP, R.string.tulkki_top_up, R.string.tulkki_top_up_summary))
        rows.add(screen(TulkkiSettingsRows.KEY_PROMPTS, R.string.tulkki_prompts, R.string.tulkki_prompts_summary))
        rows.add(prompt(TranslationSettingsStore.KEY_TRANSLATE_PROMPT, R.string.tulkki_prompt_translate, PromptBook.Kind.TRANSLATE, R.string.tulkki_prompt_translate_hint, state))
        rows.add(prompt(TranslationSettingsStore.KEY_REVIEW_PROMPT, R.string.tulkki_prompt_review, PromptBook.Kind.REVIEW, R.string.tulkki_prompt_review_hint, state))
        rows.add(prompt(TranslationSettingsStore.KEY_GLOSS_PROMPT, R.string.tulkki_prompt_gloss, PromptBook.Kind.GLOSS, R.string.tulkki_prompt_gloss_hint, state))
        rows.add(SettingsRow(TulkkiSettingsRows.KEY_RECEIVED_CATEGORY, R.string.tulkki_category_received, kind = SettingsRow.RowKind.HEADER))
        rows.add(concealedOriginal(state))
        rows.add(
            SettingsRow(
                TranslationSettingsStore.KEY_SHOW_BLURRED_ENGLISH,
                R.string.tulkki_show_blurred_english,
                SettingSummary.Plain(
                    if (state.showBlurredEnglish) R.string.tulkki_show_blurred_english_on else R.string.tulkki_show_blurred_english_off,
                ),
            )
        )
        rows.add(unblurEnglishOnTap(state))
        rows.add(SettingsRow(TulkkiSettingsRows.KEY_SENT_CATEGORY, R.string.tulkki_category_sent, kind = SettingsRow.RowKind.HEADER))
        rows.add(switch(TranslationSettingsStore.KEY_SHOW_SECOND_HALF, R.string.tulkki_show_second_half, state.showSecondHalf, R.string.tulkki_show_second_half_on, R.string.tulkki_show_second_half_off))
        rows.add(switch(TranslationSettingsStore.KEY_CONCEAL_OWN_SECOND_HALF, R.string.tulkki_conceal_own_second_half, state.concealOwnSecondHalf, R.string.tulkki_conceal_own_second_half_on, R.string.tulkki_conceal_own_second_half_off))
        rows.add(
            switch(
                TranslationSettingsStore.KEY_SHOW_ENGLISH_RETRANSLATION,
                R.string.tulkki_show_english_retranslation,
                state.showEnglishRetranslation,
                R.string.tulkki_show_english_retranslation_on,
                R.string.tulkki_show_english_retranslation_off,
            )
        )
        // The retry's own words, and deliberately down here with the sent-side behaviour rather than
        // behind the prompts container: it is UI copy, not an instruction, and a row placed with the
        // three prompts would be read as one. Its own section is what makes that unmistakable.
        rows.add(
            SettingsRow(
                TranslationSettingsStore.KEY_RETRY_WORDING,
                R.string.tulkki_retry_wording,
                SettingSummary.Formatted(R.string.tulkki_retry_wording_summary, listOf(state.retryWording)),
            )
        )
        return rows
    }

    /**
     * A language row: the sentinel is a mode and never a language, so it is asked first - a row that
     * rendered `none` through the detector-derived name path would print a language for the one value
     * that means "no interpreter at all" ([TulkkiSettingsRows.isTheOffState]).
     */
    private fun language(
        key: String,
        title: Int,
        code: String,
        summary: Int,
        offSummary: Int,
    ): SettingsRow =
        SettingsRow(
            key,
            title,
            if (TulkkiSettingsRows.isTheOffState(code)) {
                SettingSummary.Plain(offSummary)
            } else {
                SettingSummary.Formatted(summary, listOf(ConversationLanguage.languageName(code)))
            },
        )

    /**
     * The key row. A store that cannot be opened refuses the key rather than writing it in the clear,
     * so the row says that and cannot act - the screen's one "error" state (§3.1). The key itself is
     * never carried: this reads [TulkkiSettingsState.keyPresent], a boolean.
     */
    private fun apiKey(state: TulkkiSettingsState): SettingsRow {
        if (!state.keyStorageAvailable) {
            val unavailable = SettingSummary.Plain(R.string.tulkki_api_key_storage_unavailable)
            return SettingsRow(
                TranslationSettingsStore.KEY_API_KEY,
                R.string.tulkki_api_key,
                unavailable,
                enabled = false,
                enabledWhy = unavailable,
            )
        }
        return SettingsRow(
            TranslationSettingsStore.KEY_API_KEY,
            R.string.tulkki_api_key,
            SettingSummary.Plain(if (state.keyPresent) R.string.tulkki_api_key_stored else R.string.tulkki_api_key_missing),
        )
    }

    /** The cap, the way the screen has always said it: grouped in the phone's convention, or "no cap". */
    private fun capSummary(cap: Int): SettingSummary =
        if (cap <= DailyTokenCounter.UNLIMITED) {
            SettingSummary.Plain(R.string.tulkki_daily_cap_none)
        } else {
            SettingSummary.Formatted(R.string.tulkki_daily_cap_summary, listOf(NumberFormat.getIntegerInstance().format(cap)))
        }

    /**
     * The received original's switch is the first of the two rows that are greyed rather than removed:
     * it depends on a sibling, not on the mode. Its value already follows "Show the second half" while
     * it is unset, and while that global switch is off every message is a single half and this row has
     * nothing to decide - so it says that and cannot act.
     */
    private fun concealedOriginal(state: TulkkiSettingsState): SettingsRow =
        dependent(
            TranslationSettingsStore.KEY_SHOW_CONCEALED_ORIGINAL,
            R.string.tulkki_show_concealed_original,
            state.showConcealedOriginal,
            R.string.tulkki_show_concealed_original_on,
            R.string.tulkki_show_concealed_original_off,
            state.showSecondHalf,
            R.string.tulkki_show_concealed_original_second_half_off,
        )

    /** The same shape for the English row's second switch, greyed while its master switch is off. */
    private fun unblurEnglishOnTap(state: TulkkiSettingsState): SettingsRow =
        dependent(
            TranslationSettingsStore.KEY_UNBLUR_ENGLISH_ON_TAP,
            R.string.tulkki_unblur_english_on_tap,
            state.unblurEnglishOnTap,
            R.string.tulkki_unblur_english_on_tap_on,
            R.string.tulkki_unblur_english_on_tap_off,
            state.showBlurredEnglish,
            R.string.tulkki_unblur_english_on_tap_master_off,
        )

    private fun dependent(
        key: String,
        title: Int,
        value: Boolean,
        on: Int,
        off: Int,
        canAct: Boolean,
        cannotAct: Int,
    ): SettingsRow {
        val summary = SettingSummary.Plain(if (canAct) (if (value) on else off) else cannotAct)
        return SettingsRow(key, title, summary, enabled = canAct, enabledWhy = if (canAct) null else summary)
    }

    private fun switch(key: String, title: Int, value: Boolean, on: Int, off: Int): SettingsRow =
        SettingsRow(key, title, SettingSummary.Plain(if (value) on else off))

    private fun screen(key: String, title: Int, summary: Int): SettingsRow =
        SettingsRow(key, title, SettingSummary.Plain(summary), kind = SettingsRow.RowKind.SCREEN)

    /**
     * One instruction's row. The hint is a resource the summary embeds, so it travels as a nested
     * [SettingSummary] and the Composable resolves it before formatting the sentence - the same two
     * strings the XML screen drew, with the same arguments in the same order.
     */
    private fun prompt(
        key: String,
        title: Int,
        kind: PromptBook.Kind,
        hint: Int,
        state: TulkkiSettingsState,
    ): SettingsRow {
        val hintText = SettingSummary.Plain(hint)
        val summary =
            if (state.promptEdited[kind] == true) {
                SettingSummary.Formatted(R.string.tulkki_prompt_edited, listOf(state.promptLength[kind] ?: 0, hintText))
            } else {
                SettingSummary.Formatted(R.string.tulkki_prompt_shipped, listOf(hintText))
            }
        return SettingsRow(key, title, summary)
    }
}
