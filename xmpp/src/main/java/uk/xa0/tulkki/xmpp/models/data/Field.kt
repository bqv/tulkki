package uk.xa0.tulkki.xmpp.models.data

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0004 data forms: one `<field/>`. The package namespace moves onto the class, because Kotlin
 * cannot annotate a package; the derived (`field`, `jabber:x:data`) pair comes from the
 * `@XmlElement` annotation.
 *
 * The Guava `Collections2.transform(getExtensions(Value.class), Element::getContent)` view becomes
 * `map`, with the declared `Collection<String>` signature kept; `Element.getContent()` is non-null
 * in the ported tree, so the elements are real strings.
 */
@XmlElement(namespace = Namespace.DATA)
class Field : Extension(Field::class.java) {

    fun getFieldName(): String? = getAttribute("var")

    fun getValues(): Collection<String> =
        getExtensions(Value::class.java).map { it.getContent() }

    fun setFieldName(name: String) {
        setAttribute("var", name)
    }

    fun setType(type: String) {
        setAttribute("type", type)
    }
}
