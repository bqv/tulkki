package uk.xa0.tulkki.translation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.ArrayList

/**
 * The model's notes on the owner's own wording, read strictly.
 *
 * <p>The contract is the one the translation client already keeps: one JSON object with known
 * fields, so anything else - prose, a missing `notes`, a string where the list belongs, a note
 * that is not an object, a number where a remark or a slice belongs, a blank either, more notes than
 * were asked for - is an unusable answer rather than something to parse creatively. The same
 * all-or-nothing rule as [GlossParser], and for the same reason: half a review shown to a
 * learner reads as a whole one. A malformed answer is therefore a <em>stated absence</em>,
 * [Review.absent], and the bubble it belongs to draws nothing at all.
 *
 * <p>The shape, which is also the request's:
 *
 * <pre>
 * {"notes":[{"note":"&lt;a remark in the study language&gt;",
 *            "flagged":["&lt;a stretch of the owner's own text, verbatim&gt;"]}]}
 * </pre>
 *
 * <p>`flagged` is a <strong>list</strong>, not a single string. A remark may be about one word
 * or about a phrase, and it may be about more than one stretch of the text at once - a repeated word,
 * or two endings of the same mistake - and a single string would force the model to glue those
 * together with punctuation that is then not findable in the text. It is also the shape that makes
 * "no stretch at all" expressible without a sentinel: `[]` is an empty list, where an empty
 * string would be a zero-length match at the start of every text and not an absence. And the field
 * being absent is read the same way, as no underline: the remark is the note, and the stretch is a
 * pointer into the text, so a model that answers the note and forgets the pointer still gets its
 * remark read. Everything that is present, however, must be the right type - `"flagged":null`
 * and `"flagged":"talossa"` are both a wrong type and take the whole answer down with them.
 *
 * <p>The slices are kept <strong>character for character</strong>: no trimming, no case folding, no
 * unquoting. The container finds them with `indexOf` against the text the owner typed, and this
 * parser is the last place where a helpful tidy-up would silently break that match. It is also not
 * this parser's job to check that a slice is really in the text - it has never seen the text - and a
 * slice that turns out not to be there costs the underline, never the remark.
 *
 * <p>Absence never costs the message its send. This parser is called after the translation has
 * already been read out of the same answer, and it reports failure by returning `null` rather
 * than by throwing, so a chatty model cannot turn a paid-for translation into a held message. The
 * notes are commentary; the translation is the message.
 *
 * <p>Reading the same object that the translation's own parse reads means the stored copy of a
 * review - [ReviewStore] keeps exactly this shape - goes back through this parse on the way
 * out, so a stored answer cannot come back as something the model never said.
 *
 * <p>Pure Kotlin over a JSON string, so it is exercised by JVM unit tests with no network and no
 * device. The three field names are `const val`s (Java and the request template read them), `parse`
 * is `@JvmStatic`, and gson's getters are read as Kotlin properties (`isJsonArray`, `asJsonObject`,
 * `asString`) with `JsonObject.get` left a call.
 */
object ReviewParser {

    /** The field names, named here so the request and this read cannot drift apart. */
    const val FIELD_NOTES = "notes"

    /** The remark itself, inside one note. */
    const val FIELD_NOTE = "note"

    /** The stretches of the owner's own text the remark is about, inside one note. */
    const val FIELD_FLAGGED = "flagged"

    /**
     * The notes in one answer body, or `null` when the answer cannot be used.
     *
     * @param content the model's message content: the JSON object itself, not the whole envelope
     */
    @JvmStatic
    fun parse(content: String?): Review? {
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
        if (!root.has(FIELD_NOTES) || !root.get(FIELD_NOTES).isJsonArray) {
            return null
        }
        val array: JsonArray = root.getAsJsonArray(FIELD_NOTES)
        if (array.size() > Review.MAX_NOTES) {
            // More remarks than the shape asks for is an answer that ignored the contract, and a
            // container that draws them all would put an essay under a chat message.
            return null
        }
        val notes = ArrayList<Review.Note>(array.size())
        for (element in array) {
            // A note that is not an object with a non-empty string in it is a malformed answer,
            // not a value to coerce and not one to skip: skipping it would show the rest as if it
            // were the whole review.
            val note = note(element) ?: return null
            notes.add(note)
        }
        return Review.of(notes)
    }

    /** One note, or `null` when it is not the shape the request asked for. */
    private fun note(element: JsonElement?): Review.Note? {
        // A bare string - the shape before the slices existed - lands here: the contract changed, and
        // an answer to the old contract is not half of an answer to the new one.
        if (element == null || !element.isJsonObject) {
            return null
        }
        val obj = element.asJsonObject
        // Unknown fields are ignored, the way the translation's own lang/text are ignored when the
        // notes are read: this reads the fields it knows and does not invent meaning for the rest.
        val remark = string(obj.get(FIELD_NOTE))
        if (remark.isEmpty()) {
            return null
        }
        if (!obj.has(FIELD_FLAGGED)) {
            // No pointer into the text. The remark is the note, so this is a note without an
            // underline rather than a broken note.
            return Review.Note.of(remark)
        }
        val flagged = obj.get(FIELD_FLAGGED)
        if (!flagged.isJsonArray) {
            return null
        }
        val array: JsonArray = flagged.asJsonArray
        if (array.size() > Review.MAX_FLAGGED) {
            return null
        }
        val slices = ArrayList<String>(array.size())
        for (sliceElement in array) {
            val text = slice(sliceElement) ?: return null
            slices.add(text)
        }
        return Review.Note.of(remark, slices)
    }

    /** The field as a trimmed string, or empty when it is absent or not a JSON string. */
    private fun string(element: JsonElement?): String {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return ""
        }
        if (!element.asJsonPrimitive.isString) {
            // A number or a boolean where a note belongs ("notes":[0]) is malformed, not the string
            // "0" - the same refusal GlossParser makes for its own fields.
            return ""
        }
        val text = element.asString
        return if (text == null) "" else text.javaTrim()
    }

    /**
     * One flagged slice, or `null` when it is not a non-blank JSON string.
     *
     * <p>Deliberately <strong>not</strong> trimmed: the returned value is the answer's own characters,
     * because that is what has to be found in the owner's text again.
     */
    private fun slice(element: JsonElement?): String? {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        if (!element.asJsonPrimitive.isString) {
            return null
        }
        val text = element.asString
        // A blank slice is a wrong type in effect: it can underline nothing, and "" would match at
        // position 0 of any text. The model has an empty list for "no stretch at all".
        return if (text == null || text.javaTrim().isEmpty()) null else text
    }
}

/**
 * `String.trim()` with Java's own definition: everything at or below U+0020.
 */
private fun String.javaTrim(): String = trim { it <= ' ' }
