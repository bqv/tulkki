package uk.xa0.tulkki.xmpp.forms

import android.os.Bundle
import java.util.ArrayList
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * A `jabber:x:data` form (XEP-0004): the fields, the submit transition and the FORM_TYPE marker.
 *
 * Ported from Java by the port-14 `xmppport2` lane. Decisions taken rather than inherited:
 *
 * 1. **`FORM_TYPE` is `const val` on the companion**, which still compiles to
 *    `public static final String FORM_TYPE` on `Data`, so the Java callers that read it keep working.
 * 2. **The two `put` overloads stay two**, `put(String, String?)` and `put(String, Collection<String>)`,
 *    and `getFieldByName`/`parse`/`getValue` answer nullable as Java's unannotated returns allowed.
 * 3. **Java's `stream().filter(...).collect(Collectors.toList())` is Kotlin's `filter`** in
 *    `removeUnnecessaryChildren`: the same predicate, the same order, a `List<Element>` either way.
 * 4. **`getTitle` keeps the literal `"jabber:x:data"`** the Java passed, rather than naming
 *    `Namespace.DATA` (the two are the same value; the literal is what upstream wrote).
 * 5. The `:xmpp` island stays upstream-shaped, so nothing here is weakened or widened.
 */
class Data : Element("x") {

    init {
        this.setAttribute("xmlns", Namespace.DATA)
    }

    fun getFields(): List<Field> {
        val fields = ArrayList<Field>()
        for (child in getChildren()) {
            if (child.getName() == "field"
                && FORM_TYPE != child.getAttribute("var")
            ) {
                fields.add(Field.parse(child))
            }
        }
        return fields
    }

    fun getFieldByName(needle: String): Field? {
        for (child in getChildren()) {
            if (child.getName() == "field" && needle == child.getAttribute("var")) {
                return Field.parse(child)
            }
        }
        return null
    }

    fun put(name: String, value: String?): Field {
        var field = getFieldByName(name)
        if (field == null) {
            field = Field(name)
            this.addChild(field)
        }
        field.setValue(value)
        return field
    }

    fun put(name: String, values: Collection<String>) {
        var field = getFieldByName(name)
        if (field == null) {
            field = Field(name)
            this.addChild(field)
        }
        field.setValues(values)
    }

    fun submit(options: Bundle) {
        for (field in getFields()) {
            if (options.containsKey(field.getFieldName())) {
                field.setValue(options.getString(field.getFieldName()))
            }
        }
        submit()
    }

    fun submit() {
        this.setAttribute("type", "submit")
        removeUnnecessaryChildren()
        for (field in getFields()) {
            field.removeNonValueChildren()
        }
    }

    private fun removeUnnecessaryChildren() {
        replaceChildren(
            getChildren().filter { it.getName() == "field" || it.getName() == "title" }
        )
    }

    fun setFormType(formType: String?) {
        val field = this.put(FORM_TYPE, formType)
        field.setAttribute("type", "hidden")
    }

    fun getFormType(): String = getValue(FORM_TYPE) ?: ""

    fun getValue(name: String): String? {
        val field = this.getFieldByName(name)
        return field?.getValue()
    }

    fun getTitle(): String? = findChildContent("title", "jabber:x:data")

    companion object {

        const val FORM_TYPE = "FORM_TYPE"

        @JvmStatic
        fun parse(element: Element?): Data? {
            if (element == null) return null

            val data = Data()
            data.bindTo(element)
            return data
        }

        @JvmStatic
        fun create(type: String, bundle: Bundle): Data {
            val data = Data()
            data.setFormType(type)
            data.setAttribute("type", "submit")
            for (key in bundle.keySet()) {
                data.put(key, bundle.getString(key))
            }
            return data
        }
    }
}
