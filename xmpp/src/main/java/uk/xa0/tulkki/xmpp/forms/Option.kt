package uk.xa0.tulkki.xmpp.forms

import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import uk.xa0.tulkki.xml.Element

/**
 * One `option` of a `jabber:x:data` field (XEP-0004), ported from Java.
 *
 * Decisions taken rather than inherited:
 *
 * 1. **`label` is non-null, and that is the whole of the port.** Java left the field null when the
 *    `<option/>` carried neither a `label` attribute nor a `<value>` child, and `toString()`
 *    answered that null; Kotlin's `Any.toString()` is declared to answer a `String`, so a nullable
 *    answer cannot be spelled at all. It does not need to be: `toString() == null` implies
 *    `getValue() == null` (the Java constructor already fell back `label == null ? value : label`),
 *    and every consumer of the label folds it through `?: value` / `?: ""` / `?: name`, which
 *    yields `""` in exactly that case. The field completes the normalization the Java constructor
 *    began. See `docs/MIGRATION.md`, "Kotlin-negative (3)".
 * 2. **`value` stays `String?`.** Java's null means "no `<value/>` child" and the readers still
 *    test it; nothing was widened.
 * 3. **`getValue`/`getIcon`/`getIconEl` stay `open fun getX()`.** The Java getters were non-final
 *    and are the island's surface; a Kotlin property would lose the `getX()` spelling.
 * 4. **`equals` keeps Java's three branches**, with `===` for the reference test, so the result is
 *    unchanged branch for branch; `hashCode` is still not overridden, as in Java.
 * 5. **`forField` stays `@JvmStatic` on the companion**, so Kotlin callers keep spelling
 *    `Option.forField(...)` and Java callers keep the static. `parseSVG` is `@JvmStatic` too, so the
 *    private static method stays on `Option` rather than moving onto the companion class.
 */
open class Option(
    @JvmField protected val value: String?,
    label: String?,
    @JvmField protected val icon: SVG?,
    @JvmField protected val iconEl: Element?,
) {

    /** The display label: the `label` attribute, else the value, else `""`. Never null. */
    @JvmField
    protected val label: String = label ?: value ?: ""

    constructor(option: Element) : this(
        option.findChildContent("value", "jabber:x:data"),
        option.getAttribute("label"),
        parseSVG(option.findChild("svg", "http://www.w3.org/2000/svg")),
        option.findChild("svg", "http://www.w3.org/2000/svg"),
    )

    constructor(value: String?, label: String?) : this(value, label, null, null)

    override fun equals(other: Any?): Boolean {
        if (other !is Option) return false

        if (value === other.value) return true
        if (value == null || other.value == null) return false
        return value == other.value
    }

    override fun toString(): String = label

    open fun getValue(): String? = value

    open fun getIcon(): SVG? = icon

    open fun getIconEl(): Element? = iconEl

    companion object {

        @JvmStatic
        fun forField(field: Element): List<Option> {
            val options = ArrayList<Option>()
            for (el in field.getChildren()) {
                if (el.getNamespace() == null || el.getNamespace() != "jabber:x:data") continue
                if (el.getName() != "option") continue
                options.add(Option(el))
            }
            return options
        }

        @JvmStatic
        private fun parseSVG(svg: Element?): SVG? {
            if (svg == null) return null
            return try {
                SVG.getFromString(svg.toString())
            } catch (e: SVGParseException) {
                null
            }
        }
    }
}
