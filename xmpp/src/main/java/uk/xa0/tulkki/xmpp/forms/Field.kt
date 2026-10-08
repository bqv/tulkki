package uk.xa0.tulkki.xmpp.forms

import java.util.ArrayList
import uk.xa0.tulkki.xml.Element

/**
 * One `field` of a `jabber:x:data` form (XEP-0004): its var, values, label, type and options.
 *
 * Ported from Java by the port-14 `xmppport2` lane. Decisions taken rather than inherited:
 *
 * 1. **The two constructors stay two**: the private no-arg one is the primary, and the public
 *    `Field(String)` secondary delegates to it and sets `var`; `parse` still uses the private one.
 * 2. **`setValue` takes `String?`**: `Element.setContent` is Java and its own body tests for null
 *    (a null clears the children without adding a text node), so the null was meaningful.
 * 3. **`setValues` and `removeNonValueChildren` keep Java's stream chains as Kotlin's `map`/`filter`**
 *    — same predicate, same order, a `List<Element>` either way.
 * 4. **`getValue`/`getFieldName`/`getLabel`/`getType` answer nullable** and `getValues` a `List<String>`,
 *    as Java's unannotated signatures allowed.
 * 5. **`Option` is Kotlin now too**; `getOptions` is an unchanged call to `Option.forField(this)`,
 *    and `Option`'s label is non-null (`docs/MIGRATION.md`, "Kotlin-negative (3)").
 */
class Field private constructor() : Element("field") {

    constructor(name: String) : this() {
        this.setAttribute("var", name)
    }

    fun getFieldName(): String? = this.getAttribute("var")

    fun setValue(value: String?) {
        replaceChildren(listOf(Element("value").setContent(value)))
    }

    fun setValues(values: Collection<String>) {
        replaceChildren(values.map { Element("value").setContent(it) })
    }

    fun removeNonValueChildren() {
        replaceChildren(getChildren().filter { it.getName() == "value" })
    }

    fun getValue(): String? = findChildContent("value")

    fun getValues(): List<String> {
        val values = ArrayList<String>()
        for (child in getChildren()) {
            if ("value" == child.getName()) {
                values.add(child.getContent())
            }
        }
        return values
    }

    fun getLabel(): String? = getAttribute("label")

    fun getType(): String? = getAttribute("type")

    fun isRequired(): Boolean = hasChild("required")

    fun getOptions(): List<Option> = Option.forField(this)

    companion object {

        @JvmStatic
        fun parse(element: Element): Field {
            val field = Field()
            field.bindTo(element)
            return field
        }
    }
}
