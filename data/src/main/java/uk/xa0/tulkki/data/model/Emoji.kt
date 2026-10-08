package uk.xa0.tulkki.data.model

import android.text.SpannableStringBuilder
import java.util.ArrayList
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uk.xa0.tulkki.libs.EmojiRef

/**
 * One emoji: a unicode string, a sort order, and the tag/emoticon/shortcode lists the search reads.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **The five fields stay Java-visible fields - `@JvmField`.** `BindingAdapters:196`, `:198` reads
 *    `emoji.unicode` as a *field*, and `CustomEmoji` and `:app`'s `EmojiSearch` mutate
 *    `shortcodes`/`tags` (`clear()`/`addAll`); Kotlin `val`s would generate `getUnicode()` and break
 *    the Java reader. `order` and `emoticon` were `protected` fields and stay protected fields -
 *    nothing outside this file reads either. **Interop debt: five `@JvmField`s.**
 * 2. **`unicode` is a non-null `String`, and the constructor parameter is the nullable one.** Java
 *    declared the field `String` while two call sites pass `null`: `CustomEmoji`'s `Emoji(null, 10)`
 *    (whose own `uniquePart`/`toString` are overridden, so its unicode is read by nothing) and
 *    `Reaction.aggregated`'s non-custom branch, which is only reached when `cid == null` - and
 *    `Reaction`'s own serialiser writes `reaction` as non-null in exactly that branch. The consumers
 *    that read the field require the non-null: `:ui`'s `MessageProjection:497` assigns it to
 *    `UiReaction.emoji`, a `String`, and `BindingAdapters:196`/`:198` passes it to `setText`.
 *    Folding the impossible `null` to `""` in the one place it could enter keeps every reader's
 *    contract; it is the only behaviour the field could not carry in Kotlin.
 * 3. **`shortcodes` carries non-null elements; `tags` likewise.** Java's `List<String>` was a
 *    platform type, so Kotlin's `CustomEmoji` could add its `String? shortcode` unchecked - and it
 *    does, because a custom-emoji reaction round-trips with a `null` `reaction`
 *    (`Reaction.kt:121` writes `reaction` as `null` when `cid != null`). `CustomEmoji` now folds that
 *    `null` to `""` at its own `add`, so the list answers `String` to `EmojiSearch`'s `String`-typed
 *    `Lists.transform` lambda and to `toString`. **Interop debt: none.**
 * 4. **The class and the two members `CustomEmoji` overrides are `open`.** Java's class was not
 *    final and neither were [toInsert]/[uniquePart]; [toString] is `open` as an override already.
 *    The two match helpers stay final - nothing in the tree overrides them.
 * 5. **[compareTo] keeps Java's three branches**, and both `order` reads stay on an instance of this
 *    same class, which Kotlin's `protected` permits.
 * 6. **No statics, so no `@JvmStatic`.** The two constructors are Java's two: the public
 *    `(String?, int)` and the JSON one, which delegates to the first so the three lists are Java's
 *    own `ArrayList`s. `JSONException` is unchecked in Kotlin, and no caller could catch it by type.
 */
open class Emoji(
    text: String?,
    @JvmField protected val order: Int,
) : Comparable<Emoji>, EmojiRef {

    @JvmField
    val unicode: String = text ?: ""

    @JvmField
    val tags: MutableList<String> = ArrayList()

    @JvmField
    protected val emoticon: MutableList<String> = ArrayList()

    @JvmField
    val shortcodes: MutableList<String> = ArrayList()

    constructor(o: JSONObject) : this(o.getString("unicode"), o.getInt("order")) {
        val rawTags = o.getJSONArray("tags")
        for (i in 0 until rawTags.length()) {
            tags.add(rawTags.getString(i))
        }
        val rawEmoticon = o.getJSONArray("emoticon")
        for (i in 0 until rawEmoticon.length()) {
            emoticon.add(rawEmoticon.getString(i))
        }
        val rawShortcodes = o.getJSONArray("shortcodes")
        for (i in 0 until rawShortcodes.length()) {
            shortcodes.add(rawShortcodes.getString(i))
        }
    }

    fun emoticonMatch(q: String): Boolean {
        for (emote in emoticon) {
            if (emote == q || emote == ":" + q) return true
        }
        return false
    }

    fun shortcodeMatch(q: String): Boolean {
        for (shortcode in shortcodes) {
            if (shortcode == q) return true
        }
        return false
    }

    open fun toInsert(): SpannableStringBuilder = SpannableStringBuilder(unicode)

    override fun toString(): String = unicode

    open fun uniquePart(): String = unicode

    override fun compareTo(other: Emoji): Int {
        if (equals(other)) return 0
        if (order == other.order) return uniquePart().compareTo(other.uniquePart())
        return order - other.order
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Emoji) return false

        return uniquePart() == other.uniquePart()
    }

    override fun hashCode(): Int = uniquePart().hashCode()
}
