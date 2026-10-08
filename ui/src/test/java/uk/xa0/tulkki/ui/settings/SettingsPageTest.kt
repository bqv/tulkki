package uk.xa0.tulkki.ui.settings

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashSet
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.translation.PromptBook
import uk.xa0.tulkki.ui.R

/**
 * `ui-4`'s cells: the page as a function of state, and the raw-preference-key audit §3.4 asks for -
 * "`PreferenceManager` binds screens to the **raw preference keys the Java reads everywhere**, so a
 * Compose screen must keep writing the same keys or every reader breaks".
 *
 * <p><strong>The anchor changed with the swap, and it is weaker - stated rather than hidden.</strong>
 * Until `ui-4` part 2b these cells compared the page against `preferences_tulkki.xml`, an *independent*
 * record of the rows, their order, their keys and their titles: a row renamed in one place and not the
 * other failed a test that no production file could satisfy by agreeing with itself. That file is deleted
 * with the screen's rewrite, so the row contract is now a **frozen list in this file**, and the two
 * independent facts that remain are the keys `TranslationSettingsStore` routes (read from its own
 * source) and the title resources in `strings_tulkki.xml` (read from theirs). A frozen list catches a
 * later edit to either side; it cannot catch the day the list and the page are edited together, and that
 * is the price of having no second tree.
 */
class SettingsPageTest {

    /**
     * The page, frozen: the raw key each row writes (`null` for the heading that writes none) and the
     * `strings_tulkki.xml` name its title comes from, in the order the page draws them.
     */
    private val contract: List<Pair<String?, String>> =
        listOf(
            null to "tulkki_category_everything",
            "tulkki_app_language" to "tulkki_app_language",
            "tulkki_study_language" to "tulkki_study_language",
            "tulkki_reveal_suggestion_first" to "tulkki_reveal_first",
            "tulkki_api_key" to "tulkki_api_key",
            "tulkki_api_base_url" to "tulkki_api_base_url",
            "tulkki_daily_token_cap" to "tulkki_daily_cap",
            "tulkki_usage" to "tulkki_usage",
            "tulkki_failures" to "tulkki_failures",
            "tulkki_top_up" to "tulkki_top_up",
            "tulkki_prompts" to "tulkki_prompts",
            "tulkki_prompt_translate" to "tulkki_prompt_translate",
            "tulkki_prompt_review" to "tulkki_prompt_review",
            "tulkki_prompt_gloss" to "tulkki_prompt_gloss",
            "tulkki_category_received" to "tulkki_category_received",
            "tulkki_show_concealed_original" to "tulkki_show_concealed_original",
            "tulkki_show_blurred_english" to "tulkki_show_blurred_english",
            "tulkki_unblur_english_on_tap" to "tulkki_unblur_english_on_tap",
            "tulkki_category_sent" to "tulkki_category_sent",
            "tulkki_show_second_half" to "tulkki_show_second_half",
            "tulkki_conceal_own_second_half" to "tulkki_conceal_own_second_half",
            "tulkki_show_english_retranslation" to "tulkki_show_english_retranslation",
            "tulkki_retry_wording" to "tulkki_retry_wording",
        )

    @Test
    fun thePageIsTheFrozenContractInItsFrozenOrder() {
        val rows = SettingsPage.rows(state(interpreter = true))
        Assert.assertEquals("the rows, in the contract's order", contract.map { it.first }, rows.map { it.key })
    }

    /**
     * Off, the page reduces to the switch itself - the two language rows are the only way back on - plus
     * the one informative line. Every key the contract names otherwise is gone, which is the same
     * statement `SettingsPage.rows` makes with its predicate.
     */
    @Test
    fun theOffStateIsTheTwoLanguageRowsAndNothingElse() {
        val rows = SettingsPage.rows(state(interpreter = false))
        Assert.assertEquals(
            listOf("tulkki_app_language", "tulkki_study_language"),
            rows.mapNotNull { it.key },
        )
        val kept = setOf("tulkki_app_language", "tulkki_study_language")
        for (key in contract.mapNotNull { it.first }) {
            if (key in kept) {
                continue
            }
            Assert.assertFalse("off, this row must not exist: $key", rows.any { it.key == key })
        }
        Assert.assertTrue(
            "and the off line is there, storing nothing",
            rows.any { it.kind == SettingsRow.RowKind.LINE && it.key == null },
        )
    }

    /** Each row's title is the string resource the contract names, and that resource exists. */
    @Test
    fun everyRowTitleIsAStringResourceTheContractNames() {
        val declared = stringNames(stringsXml())
        val rows = SettingsPage.rows(state(interpreter = true))
        Assert.assertEquals("one row per contract entry", contract.size, rows.size)
        for ((at, entry) in contract.withIndex()) {
            val name = entry.second
            Assert.assertTrue("$name is not declared in strings_tulkki.xml", declared.contains(name))
            val expected = R.string::class.java.getField(name).getInt(null)
            Assert.assertEquals("the row's title: ${entry.first}", expected, rows[at].title)
        }
    }

    /**
     * The audit's two directions. Every row that stores a value names a key the settings store already
     * handles; and every key the store handles is a value row, except the three prices, which the Ledger
     * screen edits and no settings row writes.
     */
    @Test
    fun everySettingRowWritesAKeyTheStoreAlreadyHandles() {
        val known = storeKeys()
        Assert.assertTrue("the store's own keys are readable as data", known.contains("tulkki_api_key"))
        val rows = SettingsPage.rows(state(interpreter = true))
        val valueRows = rows.filter { it.kind == SettingsRow.RowKind.SETTING }.mapNotNull { it.key }.toSet()
        for (row in rows) {
            if (row.kind != SettingsRow.RowKind.SETTING) {
                continue
            }
            Assert.assertTrue(
                "a row that stores a value must write a key the store knows: ${row.key}",
                row.key != null && known.contains(row.key),
            )
        }
        for (key in known) {
            if (key.startsWith("tulkki_price_")) {
                continue
            }
            Assert.assertTrue("$key is a stored value and must be a value row", valueRows.contains(key))
        }
    }

    /**
     * The deeper half of the same audit, and the one the Compose host's writes run through: naming a key
     * the store defines is not enough, because a constant with no branch routes nothing. Every value
     * row's key has to appear inside the store's own read switch **and** inside its write switch.
     */
    @Test
    fun everySettingRowIsRoutedByTheStoreInBothDirections() {
        val source = read("src/main/java/uk/xa0/tulkki/ui/TranslationSettingsStore.kt")
        val reads = routedValues(source, listOf("getString", "getBoolean", "getInt"))
        val writes = routedValues(source, listOf("putString", "putBoolean", "putInt"))
        Assert.assertTrue("the read switch is readable as data", reads.contains("tulkki_api_key"))
        Assert.assertTrue("the write switch is readable as data", writes.contains("tulkki_api_key"))
        for (row in SettingsPage.rows(state(interpreter = true))) {
            if (row.kind != SettingsRow.RowKind.SETTING) {
                continue
            }
            val key = row.key ?: continue
            Assert.assertTrue("$key is not routed by the store's read switch", reads.contains(key))
            Assert.assertTrue("$key is not routed by the store's write switch", writes.contains(key))
        }
    }

    /** Each editor opens holding the value in force, and the key is never echoed. */
    @Test
    fun everyEditorOpensHoldingWhatIsInForce() {
        val state = state(interpreter = true)
        val initials =
            SettingsPage.rows(state)
                .filter { it.kind == SettingsRow.RowKind.SETTING }
                .associate { it.key to SettingsEditors.initialFor(state, it) }
        Assert.assertEquals("the key is never carried, so the field opens empty", "", initials["tulkki_api_key"])
        Assert.assertEquals(state.baseUrl, initials["tulkki_api_base_url"])
        Assert.assertEquals(state.dailyTokenCap.toString(), initials["tulkki_daily_token_cap"])
        Assert.assertEquals(state.appLanguage, initials["tulkki_app_language"])
        Assert.assertEquals(state.studyLanguage, initials["tulkki_study_language"])
        Assert.assertEquals(
            "an instruction opens holding the wording in force, from PromptBook",
            PromptBook.template(PromptBook.Kind.TRANSLATE),
            initials["tulkki_prompt_translate"],
        )
        Assert.assertEquals(
            "UI copy opens holding the wording in force too, but from RetryWording, not PromptBook",
            state.retryWording,
            initials["tulkki_retry_wording"],
        )
    }

    @Test
    fun aRowThatCannotActSaysWhyAndARowThatCanCarriesNothing() {
        val rows = SettingsPage.rows(state(interpreter = true).copy(showSecondHalf = false))
        val concealed = rows.single { it.key == "tulkki_show_concealed_original" }
        Assert.assertFalse("nothing to decide while every message is a single half", concealed.enabled)
        Assert.assertEquals(
            SettingSummary.Plain(R.string.tulkki_show_concealed_original_second_half_off),
            concealed.enabledWhy,
        )
        Assert.assertEquals("and the row says the same thing under itself", concealed.enabledWhy, concealed.summary)

        val unblur = SettingsPage.rows(state(interpreter = true).copy(showBlurredEnglish = false)).single { it.key == "tulkki_unblur_english_on_tap" }
        Assert.assertFalse(unblur.enabled)
        Assert.assertEquals(SettingSummary.Plain(R.string.tulkki_unblur_english_on_tap_master_off), unblur.enabledWhy)

        Assert.assertThrows(IllegalArgumentException::class.java) {
            SettingsRow("tulkki_api_key", R.string.tulkki_api_key, enabled = false)
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            SettingsRow(
                "tulkki_api_key",
                R.string.tulkki_api_key,
                enabled = true,
                enabledWhy = SettingSummary.Plain(R.string.tulkki_api_key_missing),
            )
        }
    }

    private fun state(interpreter: Boolean): TulkkiSettingsState =
        TulkkiSettingsState(
            interpreter = interpreter,
            appLanguage = "fi",
            studyLanguage = "en",
            keyPresent = true,
            keyStorageAvailable = true,
            baseUrl = "https://api.deepseek.com",
            dailyTokenCap = 100_000,
            revealSuggestionFirst = false,
            showSecondHalf = true,
            showConcealedOriginal = true,
            concealOwnSecondHalf = false,
            showBlurredEnglish = true,
            unblurEnglishOnTap = true,
            showEnglishRetranslation = false,
            promptEdited =
                mapOf(
                    PromptBook.Kind.TRANSLATE to false,
                    PromptBook.Kind.REVIEW to false,
                    PromptBook.Kind.GLOSS to false,
                ),
            promptLength =
                mapOf(
                    PromptBook.Kind.TRANSLATE to 10,
                    PromptBook.Kind.REVIEW to 20,
                    PromptBook.Kind.GLOSS to 5,
                ),
        )

    /** Every `<string name="...">` in the resource, as a set. */
    private fun stringNames(xml: String): Set<String> {
        val names = LinkedHashSet<String>()
        for (match in Regex("<string name=\"([^\"]+)\"").findAll(xml)) {
            names.add(match.groupValues[1])
        }
        return names
    }

    /**
     * The keys `TranslationSettingsStore` handles, read from its source: the switch is the contract,
     * and reading it as text is what keeps this a JVM test with no `Context` and no Android class
     * loading.
     */
    private fun storeKeys(): Set<String> {
        val source = read("src/main/java/uk/xa0/tulkki/ui/TranslationSettingsStore.kt")
        val keys = LinkedHashSet<String>()
        for (match in Regex("KEY_[A-Z_]+\\s*=\\s*\"([^\"]+)\"").findAll(source)) {
            keys.add(match.groupValues[1])
        }
        return keys
    }

    /** The stored key values routed by the named methods' own bodies, through the constants they name. */
    private fun routedValues(source: String, methods: List<String>): Set<String> {
        val constants = HashMap<String, String>()
        for (match in Regex("(KEY_[A-Z_]+)\\s*=\\s*\"([^\"]+)\"").findAll(source)) {
            constants[match.groupValues[1]] = match.groupValues[2]
        }
        val values = LinkedHashSet<String>()
        for (method in methods) {
            val body = methodBody(source, method) ?: continue
            for (name in Regex("KEY_[A-Z_]+").findAll(body)) {
                constants[name.value]?.let { values.add(it) }
            }
        }
        return values
    }

    /**
     * The braces-balanced body of the method named `name`, or null when the source does not declare it.
     * The scan starts at the first `{` after the declaration and counts braces outside nothing - the
     * store's method bodies carry no braces inside strings or comments, which is what makes this a
     * reading of the switch rather than a Java parser.
     */
    private fun methodBody(source: String, name: String): String? {
        val start = source.indexOf(" $name(")
        if (start < 0) {
            return null
        }
        val open = source.indexOf('{', start)
        if (open < 0) {
            return null
        }
        var depth = 0
        for (at in open until source.length) {
            when (source[at]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(open, at + 1)
                    }
                }
            }
        }
        return null
    }

    private fun stringsXml(): String = read("src/main/res/values/strings_tulkki.xml")

    private fun read(relative: String): String = String(Files.readAllBytes(locate(relative)), StandardCharsets.UTF_8)

    /**
     * The file `relative` names, found in either layout: the Gradle working directory is the module,
     * and the repository root is found by its `settings.gradle.kts` marker rather than by walking up to the
     * first `src`, which would stop at the module.
     */
    private fun locate(relative: String): Path {
        val root = root()
        val direct = root.resolve(relative)
        if (Files.exists(direct)) {
            return direct
        }
        val directories = Files.list(root).use { stream -> stream.filter(Files::isDirectory).sorted().toList() }
        for (directory in directories) {
            val name = directory.fileName.toString()
            if (name.startsWith(".") || name == "build" || name == "src") {
                continue
            }
            val candidate = directory.resolve(relative)
            if (Files.exists(candidate)) {
                return candidate
            }
        }
        throw AssertionError("could not locate $relative under $root")
    }

    private fun root(): Path {
        var directory: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        for (level in 0 until 6) {
            if (directory != null &&
                    (Files.isRegularFile(directory.resolve("settings.gradle.kts"))
                            || Files.isRegularFile(directory.resolve("settings.gradle")))) {
                return directory
            }
            directory = directory?.parent
        }
        throw AssertionError("could not find settings.gradle.kts above ${System.getProperty("user.dir")}")
    }
}
