package uk.xa0.tulkki.xml

import com.google.common.collect.ImmutableMap
import uk.xa0.tulkki.xmpp.utils.XmlHelper

/**
 * Tulkki: a text leaf of the XML tree.
 *
 * Ported from `TextNode.java`. The constructor
 * parameter stays nullable on purpose: Java rejected `null` with `IllegalArgumentException("null
 * TextNode is not allowed")` and a non-null Kotlin parameter would have replaced that with an
 * `Intrinsics` NPE - the explicit check is the behaviour, so it is kept. `content` stays a
 * protected `@JvmField` for the Java-visible field the old class had, and both `toString` overloads
 * and `appendToBuilder` keep their descriptors and route through the same `XmlHelper` calls.
 */
open class TextNode(content: String?) : Node {

    @JvmField
    protected var content: String

    init {
        if (content == null) {
            throw IllegalArgumentException("null TextNode is not allowed")
        }
        this.content = content
    }

    override fun getContent(): String = content

    override fun appendToBuilder(
        parentNS: Map<String, String>,
        elementOutput: StringBuilder,
        skipEnd: Int,
    ) {
        XmlHelper.appendEncodedEntities(content, elementOutput)
    }

    override fun toString(): String = XmlHelper.encodeEntities(content)

    override fun toString(ns: ImmutableMap<String, String>): String = toString()
}
