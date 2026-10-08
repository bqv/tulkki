package uk.xa0.tulkki.data.model

import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import uk.xa0.tulkki.data.utils.InlineImageSpan

/**
 * A custom emoji, moved down out of `uk.xa0.tulkki.app.extras.EmojiSearch` with [Emoji] by 3.7
 * pair 2.
 *
 * <p>Its drawable-carrying insert is already a `:data` type's job now that [InlineImageSpan] lives in
 * `:data` too. `setupChip` - the one method here that reads `R.drawable.ic_photo_24dp` and a Material
 * theme attribute - moved to `:ui`, so this file does not add a second `R` reader to `:data`. [icon]
 * is public where it was protected, because the `:ui` that paints the chip is no longer a subclass.
 *
 * Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **Both public fields stay fields: `@JvmField`.** `:ui`'s `BindingAdapters` reads them as fields
 *    (`custom.icon` at `:180`, `custom.source` at `:183`), so Kotlin `val`s would generate
 *    `getIcon()`/`getSource()` and break that caller. **Interop debt: two annotations**, gone when
 *    `BindingAdapters` is ported.
 * 2. **[source] is a non-null `String` and [icon] is a nullable `Drawable?`.** [uniquePart] overrides
 *    [Emoji]'s `String`-returning member and returns `source`, so a nullable `source` could not satisfy
 *    the override without a `!!`; the one construction site (`Reaction.aggregated`) passes
 *    `cid.toString()`. Java handled `icon == null` explicitly and that same site passes a thumbnail
 *    that can be null, so the nullability is the Java code's own.
 * 3. **`shortcode` and `tag` stay non-property parameters, and the `shortcode` is folded to `""` at
 *    its `add`.** Java never checked either one: `tag` is guarded by `if (tag != null)`, and
 *    `shortcode` went into `shortcodes` unchecked because that list's element type was Java's
 *    platform `String`. `Emoji.shortcodes` is now `MutableList<String>` - its one reader,
 *    `EmojiSearch`'s `Lists.transform` lambda, is `String`-typed - and a custom-emoji reaction
 *    round-trips with a `null` `reaction` (`Reaction.kt:121` writes `reaction` as `null` when
 *    `cid != null`), so the one reachable `null` is folded here rather than becoming a new NPE. The
 *    only consumer is [toString], which reads `shortcodes[0]`.
 * 4. **`override` is required on all three members** - [toInsert], [uniquePart] and [toString] all
 *    redeclare members of the Java [Emoji]. Java carried `@Override` on two of the three; Kotlin
 *    demands the modifier on every one, and the JVM surface is unchanged.
 * 5. **The body is Java's, call for call.** `icon == null ? new ColorDrawable(0) : icon` is
 *    `icon ?: android.graphics.drawable.ColorDrawable(0)` (kept inline, as Java spelled it) and
 *    `shortcodes.get(0)` is `shortcodes[0]`.
 *
 * It stays a plain `class`, not a `data class`, and final: Java had identity equality (inherited from
 * [Emoji]'s `uniquePart`-based pair) and nothing in the tree extends it (`grep` finds no
 * `extends CustomEmoji`).
 */
class CustomEmoji(
    shortcode: String?,
    @JvmField val source: String,
    @JvmField val icon: Drawable?,
    tag: String?,
) : Emoji(null, 10) {

    init {
        shortcodes.add(shortcode ?: "")
        if (tag != null) tags.add(tag)
    }

    /** The body form: the shortcode text with the image drawn over it. */
    override fun toInsert(): SpannableStringBuilder {
        val builder = SpannableStringBuilder(toString())
        builder.setSpan(
            InlineImageSpan(icon ?: android.graphics.drawable.ColorDrawable(0), source),
            0,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }

    /** A custom emoji is its source, so it never collapses with a unicode one. */
    override fun uniquePart(): String = source

    override fun toString(): String = ":" + shortcodes[0] + ":"
}
