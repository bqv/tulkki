package uk.xa0.tulkki.xmpp.models.data

import uk.xa0.tulkki.annotation.XmlElement
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension

/**
 * XEP-0004 data forms: the `<x type='form'/>`. Its name and namespace are carried by the class
 * already, because there is no `package-info` to inherit them from; the (`x`, `jabber:x:data`)
 * pair comes from the `@XmlElement` annotation.
 *
 * The Guava shapes are substituted where the declared signature allows it: `Iterables.find` with a
 * default becomes `firstOrNull`, `Iterables.getFirst` becomes `firstOrNull`, and the
 * `Collections2.filter` view becomes `filter`, keeping the declared `Collection<Field>` signature.
 * `addField`'s name parameter stays nullable because upstream passes the possibly-absent `var`
 * attribute straight through.
 */
@XmlElement(name = "x", namespace = Namespace.DATA)
class Data : Extension(Data::class.java) {

    /**
     * The form's FORM_TYPE, or null when the form does not carry one. A remote party decides
     * whether the field is present at all, so its absence must read back as null - callers such as
     * [submit] already treat it that way - instead of throwing out of whatever was parsing the
     * stanza.
     *
     * Tulkki: port-11, upstream `a9658ba076`. `Iterables.find` without a default throws
     * `NoSuchElementException`, so a peer that simply omitted the field crashed the parse.
     */
    fun getFormType(): String? {
        val fields = getExtensions(Field::class.java)
        val formTypeField = fields.firstOrNull { FORM_TYPE == it.getFieldName() }
        return formTypeField?.getValues()?.firstOrNull()
    }

    fun getFields(): Collection<Field> =
        getExtensions(Field::class.java).filter { FORM_TYPE != it.getFieldName() }

    private fun addField(name: String?, value: Any?) {
        addField(name, value, null)
    }

    private fun addField(name: String?, value: Any?, type: String?) {
        if (value == null) {
            throw IllegalArgumentException("Null values are not supported on data fields")
        }
        val field = addExtension(Field())
        if (name != null) {
            field.setFieldName(name)
        }
        if (type != null) {
            field.setType(type)
        }
        if (value is Collection<*>) {
            for (subValue in value) {
                if (subValue is String) {
                    val valueExtension = field.addExtension(Value())
                    valueExtension.setContent(subValue)
                } else {
                    throw IllegalArgumentException(
                        String.format(
                            "%s is not a supported field value",
                            subValue!!.javaClass.simpleName,
                        ),
                    )
                }
            }
        } else {
            val valueExtension = field.addExtension(Value())
            when (value) {
                is String -> valueExtension.setContent(value)
                is Int -> valueExtension.setContent(value.toString())
                is Boolean -> valueExtension.setContent(if (value) "1" else "0")
                else -> throw IllegalArgumentException(
                    String.format("%s is not a supported field value", value.javaClass.simpleName),
                )
            }
        }
    }

    private fun setFormType(formType: String) {
        addField(FORM_TYPE, formType, FIELD_TYPE_HIDDEN)
    }

    fun submit(values: Map<String, Any?>): Data {
        val formType = getFormType()
        val submit = Data()
        submit.setType(FORM_TYPE_SUBMIT)
        if (formType != null) {
            submit.setFormType(formType)
        }
        for (existingField in getFields()) {
            val fieldName = existingField.getFieldName()
            val submittedValue = fieldName?.let { values[it] }
            if (submittedValue != null) {
                submit.addField(fieldName, submittedValue)
            } else {
                submit.addField(fieldName, existingField.getValues())
            }
        }
        return submit
    }

    private fun setType(type: String) {
        setAttribute("type", type)
    }

    companion object {

        private const val FORM_TYPE = "FORM_TYPE"
        private const val FIELD_TYPE_HIDDEN = "hidden"
        private const val FORM_TYPE_SUBMIT = "submit"

        @JvmStatic
        fun of(formType: String, values: Map<String, Any?>): Data {
            val data = Data()
            data.setType(FORM_TYPE_SUBMIT)
            data.setFormType(formType)
            for ((key, value) in values) {
                data.addField(key, value)
            }
            return data
        }
    }
}
