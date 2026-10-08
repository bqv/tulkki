package uk.xa0.tulkki.translation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The model's answer to a gloss request, read strictly.
 *
 * <p>The contract is the same one the translation client keeps: the request asks for one JSON object
 * with four known fields, so anything else - a sentence of prose, a missing field, a number where a
 * string belongs, a field that is present and empty - is an unusable answer rather than something to
 * parse creatively. An unusable answer becomes a failure with a reason, which the sheet says out
 * loud; guessing at half an answer would put a wrong dictionary form in front of a learner.
 *
 * <p>The four fields:
 *
 * <ul>
 *   <li>`dictionary` - the form a dictionary lists the word under
 *   <li>`ending` - the ending the surface form carries (may be empty)
 *   <li>`case` - what that ending is, in the study language (may be empty)
 *   <li>`gloss` - what the word means, in the study language
 * </ul>
 *
 * <p>Pure Kotlin over a JSON string, so it is exercised by JVM unit tests with no network. The four
 * field names are `const val`s and `parse` is `@JvmStatic`, matching `ReviewParser`'s shape;
 * `surface` stays nullable because `Gloss.of` accepts it.
 */
object GlossParser {

    /** The field names, named here so the request and this read cannot drift apart. */
    const val FIELD_DICTIONARY = "dictionary"

    const val FIELD_ENDING = "ending"
    const val FIELD_CASE = "case"
    const val FIELD_GLOSS = "gloss"

    /**
     * The gloss in one answer body, or `null` when the answer cannot be used.
     *
     * @param surface the word that was asked about, kept so the sheet can show what was tapped
     * @param content the model's message content: the JSON object itself, not the whole envelope
     */
    @JvmStatic
    fun parse(surface: String?, content: String?): Gloss? {
        // Java's own `trim()` (`<= ' '`), as in the Java this replaces.
        if (content == null || content.javaTrim().isEmpty()) {
            return null
        }
        val root: JsonObject =
                try {
                    val parsed = JsonParser.parseString(content)
                    if (parsed == null || !parsed.isJsonObject) {
                        return null
                    }
                    parsed.asJsonObject
                } catch (e: RuntimeException) {
                    // Not JSON at all. The request asked for JSON, so this is a failed answer.
                    return null
                }
        val gloss =
                Gloss.of(
                        surface,
                        string(root, FIELD_DICTIONARY),
                        string(root, FIELD_ENDING),
                        string(root, FIELD_CASE),
                        string(root, FIELD_GLOSS))
        return if (gloss.isUsable()) gloss else null
    }

    /** The field as a trimmed string, or empty when it is absent or not a JSON string. */
    private fun string(root: JsonObject, field: String): String {
        val value: JsonElement? = root.get(field)
        if (value == null || value.isJsonNull || !value.isJsonPrimitive) {
            return ""
        }
        if (!value.asJsonPrimitive.isString) {
            // A number or a boolean where the field should be a string is a malformed answer, not a
            // value to coerce: "ending": 0 would otherwise read as the string "0".
            return ""
        }
        val text = value.asString
        return if (text == null) "" else text.javaTrim()
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
