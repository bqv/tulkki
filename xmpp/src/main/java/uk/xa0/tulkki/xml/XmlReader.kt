package uk.xa0.tulkki.xml

import android.util.Log
import android.util.Xml
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.ExtensionFactory
import uk.xa0.tulkki.xmpp.models.StreamElement

private const val XML_ELEMENT_MAX_DEPTH = 128

/**
 * Tulkki: the pull-parser driver that turns the wire into `Tag`/`Element`.
 *
 * Ported from `XmlReader.java`. The Java-visible
 * surface is kept member for member: the public and generic `readElement` overloads, the private
 * depth-recursive one, the checked `IOException` clauses (`@Throws`) and the nested
 * `XmlMaxDepthReachedException`, which keeps its binary name and its message.
 *
 * The private `InputStream` is the one renaming: Java's `is` is a Kotlin keyword, so it becomes
 * `inputStream` - invisible to every caller, and the null guard it carries is kept, including the
 * `readTag` loop that stops when the stream is dropped mid-read. `setInputStream` keeps Java's
 * explicit null check so a null still answers `IOException`, not an `Intrinsics` NPE.
 */
class XmlReader : Closeable {

    private val parser: XmlPullParser = Xml.newPullParser()
    private var inputStream: InputStream? = null

    init {
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        } catch (e: XmlPullParserException) {
            Log.d(Config.LOGTAG, "error setting namespace feature on parser")
        }
    }

    @Throws(IOException::class)
    fun setInputStream(inputStream: InputStream?) {
        if (inputStream == null) {
            throw IOException()
        }
        this.inputStream = inputStream
        try {
            parser.setInput(InputStreamReader(this.inputStream))
        } catch (e: XmlPullParserException) {
            throw IOException("error resetting parser")
        }
    }

    @Throws(IOException::class)
    fun reset() {
        val stream = this.inputStream ?: throw IOException()
        try {
            parser.setInput(InputStreamReader(stream))
        } catch (e: XmlPullParserException) {
            throw IOException("error resetting parser")
        }
    }

    override fun close() {
        this.inputStream = null
    }

    @Throws(IOException::class)
    fun readTag(): Tag? {
        try {
            while (this.inputStream != null && parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = Tag.start(parser.name)
                        val xmlns = parser.namespace
                        for (i in 0 until parser.attributeCount) {
                            val prefix = parser.getAttributePrefix(i)
                            val ns = parser.getAttributeNamespace(i)
                            val name: String = if ("xml" == prefix) {
                                "xml:" + parser.getAttributeName(i)
                            } else if (ns != null && !ns.isEmpty()) {
                                "{" + ns + "}" + parser.getAttributeName(i)
                            } else {
                                parser.getAttributeName(i)
                            }
                            tag.setAttribute(name, parser.getAttributeValue(i))
                        }
                        if (xmlns != null) {
                            tag.setAttribute("xmlns", xmlns)
                        }
                        return tag
                    }

                    XmlPullParser.END_TAG -> return Tag.end(parser.name)

                    XmlPullParser.TEXT -> return Tag.no(parser.text)
                }
            }
        } catch (throwable: Throwable) {
            throw IOException(
                "xml parser mishandled " +
                    throwable.javaClass.simpleName +
                    "(" +
                    throwable.message +
                    ")",
                throwable,
            )
        }
        return null
    }

    @Throws(IOException::class)
    fun <T : StreamElement> readElement(current: Tag, clazz: Class<T>): T {
        val element = readElement(current)
        if (clazz.isInstance(element)) {
            return clazz.cast(element)
        }
        throw IOException(
            String.format("Read unexpected {%s}%s", element.getNamespace(), element.getName())
        )
    }

    @Throws(IOException::class)
    fun readElement(currentTag: Tag): Element = readElement(currentTag, 0)

    @Throws(IOException::class)
    private fun readElement(currentTag: Tag, depth: Int): Element {
        if (depth >= XML_ELEMENT_MAX_DEPTH) {
            throw XmlMaxDepthReachedException()
        }
        val namespace = currentTag.getAttributes()["xmlns"]
        val name = currentTag.getName()
        val element = ExtensionFactory.create(name, namespace)
        element.setAttributes(currentTag.getAttributes())
        var nextTag = this.readTag() ?: throw IOException("interrupted mid tag")
        while (!nextTag.isEnd(element.getName())) {
            if (nextTag.isNo()) {
                if (nextTag.getName() != null) {
                    element.addChild(TextNode(nextTag.getName()))
                }
            } else {
                val child = this.readElement(nextTag, depth + 1)
                element.addChild(child)
            }
            nextTag = this.readTag() ?: throw IOException("interrupted mid tag")
        }
        return element
    }

    class XmlMaxDepthReachedException : IOException("Reached maximum depth of XML stream")
}
