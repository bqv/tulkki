package uk.xa0.tulkki.ui

import uk.xa0.tulkki.translation.ComposerGate
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.translation.TranslationLanguages

import java.util.ArrayList

/**
 * The settings page's shared decisions, as values a JVM test can read: the container keys the page
 * and its route name, and the two rules of a language row.
 *
 * <p><strong>What this class no longer is.</strong> Until `ui-4` part 2b it held the two lists the
 * live screen removed rows by - `alwaysKeys` and `modeOnlyKeys` - because the screen was a tree
 * inflated from `preferences_tulkki.xml` and off meant taking rows out of it at bind time. The page
 * is now a function of the state (`uk.xa0.tulkki.ui.settings.SettingsPage.rows`), the XML is deleted
 * with that commit, and a list of keys to remove from a tree nothing builds would be dead weight;
 * the removal is the predicate. What stays is what the screen still asks for: the keys the page
 * names, the picker's values, and the sentinel's rule.
 *
 * <p>Pure Kotlin, no Android, so the picker's rule is exercised by unit tests.
 */
object TulkkiSettingsRows {

    /** The sub-screen row the three instructions live behind; its children follow it in the page. */
    const val KEY_PROMPTS = "tulkki_prompts"

    /** The Received category, whose children are the strip's switch and the two English-row switches. */
    const val KEY_RECEIVED_CATEGORY = "tulkki_category_received"

    /** The Sent category: the two display switches and the sent English row. */
    const val KEY_SENT_CATEGORY = "tulkki_category_sent"

    /** The row that opens the top-up WebView activity. */
    const val KEY_TOP_UP = "tulkki_top_up"

    /**
     * One language row's picker values: the sentinel first - it is the switch's own off position and
     * the reason a list of languages is not the whole answer - then the languages the three detectors
     * agree on, then a stored value nothing else knows, so a value set by something other than this
     * screen stays selectable and visible rather than being silently dropped out of the list.
     */
    @JvmStatic
    fun pickerValues(stored: String?): List<String> {
        val values = ArrayList<String>()
        values.add(Interpreter.NONE)
        for (code in TranslationLanguages.codes()) {
            if (!values.contains(code)) {
                values.add(code)
            }
        }
        // Java's String.trim, which strips every char at or below the space.
        val kept = stored?.trim { it <= ' ' } ?: ""
        if (!kept.isEmpty() && !values.contains(kept)) {
            values.add(kept)
        }
        return values
    }

    /**
     * True when a stored language code is the interpreter's sentinel rather than a language.
     *
     * <p>It is asked wherever a code would otherwise be <em>named</em>: the sentinel is not something
     * any detector can name (`TranslationLanguages.isKnown` stays false for it), and a row that
     * rendered it through the ordinary language-name path would print a language for the one value
     * that means "no interpreter at all".
     */
    @JvmStatic
    fun isTheOffState(code: String?): Boolean {
        return Interpreter.NONE == ComposerGate.normalize(code)
    }
}
