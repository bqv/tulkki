package uk.xa0.tulkki.xml

import com.google.common.base.Optional
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.primitives.Ints
import com.google.common.primitives.Longs
import java.util.ArrayList
import java.util.Hashtable
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Message

/**
 * Tulkki: one XML element - the name, the attributes and the mixed child nodes.
 *
 * Ported from `Element.java`. It is the batch's
 * widest island surface (61 `:xmpp` callers plus 35 outside), so the Java-visible shape is kept
 * member for member and the two spellings Kotlin already uses both keep working:
 *
 * - `getName()` and the `name` accessor: `name` is a public `@JvmField val` and `getName()` an
 *   explicit method. The Java field was `private`, so this widens it to `public`; it is the one
 *   observable change, forced because Kotlin cannot call a property's getter explicitly
 *   (`XmlHelper.kt`, `ReplySpans.kt` and the jingle stanzas read `element.name`) while the same
 *   tree also writes `element.getName()` in ~30 places. No Java caller ever read the field, so the
 *   widening breaks nothing.
 * - `children` and `getChildren()`: `children` is a public `@JvmField var` (Java's field was
 *   `protected`; three Kotlin callers outside `Element`'s hierarchy - `XmlHelper.printElementNames`,
 *   `ReplySpans.of` and `RtpDescription.SourceGroup` - read `element.children`, which Kotlin would
 *   no longer allow at `protected`), and `getChildren()` keeps its immutable copy.
 *
 * `getAttribute` and `getNamespace` answer `String?`, exactly as Java did; that is what forces the
 * caller edits in `Content.kt`, `GenericDescription`, `GenericTransportInfo`,
 * `IceUdpTransportInfo`, `IbbTransportInfo`, `SocksByteStreamsTransportInfo`, `models/rsm/Set`,
 * `data/model/Message.kt`, `data/model/Conversation.kt`, `XmppAxolotlMessage.kt` and
 * `UnifiedPushBroker.kt` - each one mirrors what the Java did with the absent attribute.
 * `getContent()` stays a method rather than a `content` property: the one Kotlin
 * reader that used the property spelling, `BobTransfer.kt`, moves to `getContent()` in the same
 * commit, and ~40 other Kotlin sites already call the method. `replaceChildren` and
 * `CopyOnWriteMap`'s constructor carry `@JvmSuppressWildcards` so the old invariant descriptors
 * survive.
 */
open class Element(@JvmField val name: String) : Node {

    private var attributes: Hashtable<String, String> = Hashtable()

    @JvmField
    var children: MutableList<Element> = ArrayList()

    private var childNodes: MutableList<Node> = ArrayList()

    constructor(name: String, xmlns: String?) : this(name) {
        setAttribute("xmlns", xmlns)
    }

    open fun prependChild(child: Node): Node {
        childNodes.add(0, child)
        if (child is Element) children.add(0, child)
        return child
    }

    open fun addChild(child: Node): Node {
        childNodes.add(child)
        if (child is Element) children.add(child)
        return child
    }

    open fun addChild(name: String): Element {
        val child = Element(name)
        childNodes.add(child)
        children.add(child)
        return child
    }

    open fun addChild(name: String, xmlns: String?): Element {
        val child = Element(name)
        child.setAttribute("xmlns", xmlns)
        childNodes.add(child)
        children.add(child)
        return child
    }

    open fun addChildren(children: Collection<Node>?) {
        if (children == null) return

        this.childNodes.addAll(children)
        for (node in children) {
            if (node is Element) {
                this.children.add(node)
            }
        }
    }

    open fun removeChild(child: Node?) {
        if (child == null) return

        this.childNodes.remove(child)
        if (child is Element) this.children.remove(child)
    }

    open fun setContent(content: String?): Element {
        clearChildren()
        if (content != null) this.childNodes.add(TextNode(content))
        return this
    }

    open fun findChild(name: String): Element? {
        for (child in this.children) {
            if (child.getName() == name) {
                return child
            }
        }
        return null
    }

    open fun findChildContent(name: String): String? {
        val element = findChild(name)
        return element?.getContent()
    }

    open fun findInternationalizedChildContentInDefaultNamespace(name: String): LocalizedContent? =
        LocalizedContent.get(this, name)

    open fun findChild(name: String, xmlns: String?): Element? {
        val ns = xmlns ?: throw NullPointerException()
        for (child in getChildren()) {
            if (name == child.getName() && ns == child.getAttribute("xmlns")) {
                return child
            }
        }
        return null
    }

    open fun findChildEnsureSingle(name: String, xmlns: String?): Element? {
        val ns = xmlns ?: throw NullPointerException()
        val results = ArrayList<Element>()
        for (child in getChildren()) {
            if (name == child.getName() && ns == child.getAttribute("xmlns")) {
                results.add(child)
            }
        }
        return if (results.size == 1) results[0] else null
    }

    open fun findChildContent(name: String, xmlns: String?): String? {
        val element = findChild(name, xmlns)
        return element?.getContent()
    }

    open fun hasChild(name: String): Boolean = findChild(name) != null

    open fun hasChild(name: String, xmlns: String?): Boolean = findChild(name, xmlns) != null

    fun getChildren(): List<Element> = ImmutableList.copyOf(this.children)

    open fun setAttribute(name: String, value: Boolean) {
        setAttribute(name, if (value) "1" else "0")
    }

    open fun replaceChildren(children: List<@JvmSuppressWildcards Element>) {
        this.childNodes.clear()
        this.childNodes.addAll(children)
        this.children.clear()
        this.children.addAll(children)
    }

    open fun bindTo(original: Element) {
        this.attributes = original.attributes
        this.childNodes = original.childNodes
        this.children = original.children
    }

    final override fun getContent(): String =
        childNodes.joinToString("") { it.getContent() }

    open fun getLongAttribute(name: String): Long {
        val value = Longs.tryParse(Strings.nullToEmpty(attributes[name]))
        return value ?: 0L
    }

    open fun getOptionalIntAttribute(name: String): Optional<Int> {
        val value = getAttribute(name) ?: return Optional.absent()
        return Optional.fromNullable(Ints.tryParse(value))
    }

    open fun getAttributeAsJid(name: String): Jid? {
        val jid = this.getAttribute(name)
        if (jid != null && !jid.isEmpty()) {
            try {
                return Jid.of(jid)
            } catch (e: IllegalArgumentException) {
                return Jid.ofOrInvalid(jid, this is Message)
            }
        }
        return null
    }

    open fun setAttribute(name: String?, value: String?): Element {
        if (name != null && value != null) {
            this.attributes[name] = value
        }
        return this
    }

    open fun setAttribute(name: String?, value: Jid?): Element {
        if (name != null && value != null) {
            this.attributes[name] = value.toString()
        }
        return this
    }

    override fun toString(): String = toString(ImmutableMap.of())

    override fun appendToBuilder(
        parentNS: Map<String, String>,
        elementOutput: StringBuilder,
        skipEnd: Int,
    ) {
        val mutns = CopyOnWriteMap(parentNS)
        if (childNodes.size == 0) {
            val attr = getSerializableAttributes(mutns)
            val emptyTag = Tag.empty(name)
            emptyTag.setAttributes(attr)
            emptyTag.appendToBuilder(elementOutput)
        } else {
            val startTag = startTag(mutns)
            startTag.appendToBuilder(elementOutput)
            for (child in ImmutableList.copyOf(childNodes)) {
                child.appendToBuilder(mutns.toMap(), elementOutput, Math.max(0, skipEnd - 1))
            }
            if (skipEnd < 1) endTag().appendToBuilder(elementOutput)
        }
    }

    override fun toString(parentNS: ImmutableMap<String, String>): String {
        val elementOutput = StringBuilder()
        appendToBuilder(parentNS, elementOutput, 0)
        return elementOutput.toString()
    }

    open fun startTag(): Tag = startTag(CopyOnWriteMap(Hashtable()))

    open fun startTag(mutns: CopyOnWriteMap<String, String>): Tag {
        val attr = getSerializableAttributes(mutns)
        val startTag = Tag.start(name)
        startTag.setAttributes(attr)
        return startTag
    }

    open fun endTag(): Tag = Tag.end(name)

    protected open fun getSerializableAttributes(
        ns: CopyOnWriteMap<String, String>,
    ): Hashtable<String, String> {
        val result = Hashtable<String, String>(attributes.size)
        for ((key, value) in attributes) {
            if (key[0] == '{') {
                val uriIdx = key.indexOf('}')
                val uri = key.substring(1, uriIdx - 1)
                if (!ns.containsKey(uri)) {
                    result["xmlns:ns" + ns.size()] = uri
                    ns.put(uri, "ns" + ns.size())
                }
                result[ns.get(uri) + ":" + key.substring(uriIdx + 1)] = value
            } else {
                result[key] = value
            }
        }

        return result
    }

    open fun removeAttribute(name: String): Element {
        this.attributes.remove(name)
        return this
    }

    open fun setAttributes(attributes: Hashtable<String, String>): Element {
        this.attributes = attributes
        return this
    }

    open fun getAttribute(name: String): String? =
        if (this.attributes.containsKey(name)) this.attributes[name] else null

    open fun getAttributes(): Hashtable<String, String> = this.attributes

    fun getName(): String = name

    open fun clearChildren() {
        this.children.clear()
        this.childNodes.clear()
    }

    open fun setAttribute(name: String, value: Long) {
        setAttribute(name, value.toString())
    }

    open fun setAttribute(name: String, value: Int) {
        setAttribute(name, value.toString())
    }

    open fun getAttributeAsBoolean(name: String): Boolean {
        val attr = getAttribute(name)
        return attr != null &&
            (attr.equals("true", ignoreCase = true) || attr.equals("1", ignoreCase = true))
    }

    open fun getNamespace(): String? = getAttribute("xmlns")

    class CopyOnWriteMap<K, V>(
        @JvmField protected val original: @JvmSuppressWildcards Map<K, V>,
    ) {

        @JvmField
        protected var mut: Hashtable<K, V>? = null

        fun size(): Int = mut?.size ?: original.size

        fun containsKey(k: K): Boolean = mut?.containsKey(k) ?: original.containsKey(k)

        fun get(k: K): V? {
            val m = mut
            return if (m == null) original[k] else m[k]
        }

        fun put(k: K, v: V) {
            var m = mut
            if (m == null) {
                m = Hashtable(original)
                mut = m
            }
            m.put(k, v)
        }

        fun toMap(): Map<K, V> = mut ?: original
    }
}
