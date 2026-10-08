package uk.xa0.tulkki.xml

import androidx.annotation.NonNull
import java.util.Hashtable
import java.util.Map.Entry
import uk.xa0.tulkki.xmpp.utils.XmlHelper

/**
 * Tulkki: one XML token - start, end, empty or text - with its attributes.
 *
 * Ported from `Tag.java`. The four `public static
 * final int` constants stay `const val`s on the companion (Java reads `Tag.NO` etc.), the four
 * factory methods stay `@JvmStatic` (Java calls `Tag.start(...)` and friends), and the protected
 * `type`, `name` and `attributes` stay protected `@JvmField`s because that is the Java-visible
 * field shape. `getName`/`getAttributes` keep their explicit methods; `name` is a field, not a
 * property, so the two do not collide. `@NonNull` on `toString` is carried over as Kotlin's
 * non-null return.
 */
open class Tag protected constructor(
    @JvmField protected var type: Int,
    @JvmField protected var name: String,
) {

    @JvmField
    protected var attributes: Hashtable<String, String> = Hashtable<String, String>()

    companion object {

        const val NO = -1
        const val START = 0
        const val END = 1
        const val EMPTY = 2

        @JvmStatic
        fun no(text: String): Tag = Tag(NO, text)

        @JvmStatic
        fun start(name: String): Tag = Tag(START, name)

        @JvmStatic
        fun end(name: String): Tag = Tag(END, name)

        @JvmStatic
        fun empty(name: String): Tag = Tag(EMPTY, name)
    }

    open fun getName(): String = name

    open fun identifier(): String = String.format("%s#%s", name, attributes["xmlns"])

    open fun getAttribute(attrName: String): String? = attributes[attrName]

    open fun setAttribute(attrName: String, attrValue: String): Tag {
        attributes[attrName] = attrValue
        return this
    }

    open fun setAttributes(attributes: Hashtable<String, String>) {
        this.attributes = attributes
    }

    open fun isStart(needle: String?): Boolean {
        if (needle == null) {
            return false
        }
        return type == START && needle == name
    }

    open fun isStart(name: String?, namespace: String?): Boolean =
        isStart(name) && namespace != null && namespace == getAttribute("xmlns")

    open fun isEnd(needle: String?): Boolean {
        if (needle == null) return false
        return type == END && needle == name
    }

    open fun isNo(): Boolean = type == NO

    open fun appendToBuilder(tagOutput: StringBuilder) {
        tagOutput.append('<')
        if (type == END) {
            tagOutput.append('/')
        }
        tagOutput.append(name)
        if (type != END) {
            for ((key, value) in attributes) {
                tagOutput.append(' ')
                tagOutput.append(key)
                tagOutput.append("=\"")
                XmlHelper.appendEncodedEntities(value, tagOutput)
                tagOutput.append('"')
            }
        }
        if (type == EMPTY) {
            tagOutput.append('/')
        }
        tagOutput.append('>')
    }

    @NonNull
    override fun toString(): String {
        val tagOutput = StringBuilder()
        appendToBuilder(tagOutput)
        return tagOutput.toString()
    }

    open fun getAttributes(): Hashtable<String, String> = attributes
}
