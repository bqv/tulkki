package uk.xa0.tulkki.xmpp.jingle.stanzas

import android.util.Log
import com.google.common.base.CaseFormat
import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.io.BaseEncoding
import com.google.common.primitives.Longs
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.jingle.Jingle
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The Jingle file-transfer description (XEP-0234).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **The public fields stay fields.** `Checksum.hashes`, `SessionInfo.name`, `File.size/name/
 *    mediaType/hashes` and `Hash.hash/algorithm` are all `@JvmField`, because Java reads them as
 *    fields (`JingleFileTransferConnection:380,389,406,987,995,1253` reads `file.size`/`file.name`;
 *    `asElement` reads `hash.algorithm`/`hash.hash`). `name`/`algorithm`/`mediaType` are nullable, as
 *    Java's unannotated fields were.
 * 2. **`SessionInfo` is a Kotlin `sealed class`.** Java's `sealed ... permits Checksum, Received` is
 *    the same restriction, and `JingleFileTransferConnection` still pattern-matches
 *    `FileTransferDescription.Checksum`/`Received`.
 * 3. **`Strings.isNullOrEmpty` becomes Kotlin's `isNullOrEmpty()` where the guard must smart-cast**
 *    (`getFile`, `findHashes`, `Algorithm.of`): the two predicates are identical, and Kotlin's carries
 *    the contract that lets the value be non-null on the surviving path.
 * 4. **`hash.algorithm.toString()` keeps its `NullPointerException`.** Java dereferences a field that
 *    can hold null; a nullable Kotlin receiver would silently print `"null"`, so the null is spelled
 *    out as `?: throw NullPointerException()`. `!!` is not used.
 * 5. **`getSessionInfo` is `@JvmStatic`** (`JingleFileTransferConnection:558`), `upgrade` and `of`
 *    likewise; `element.getNamespace().equals(...)` is kept as a member call (a constant receiver
 *    would have made a null namespace answer `false` where Java throws).
 */
class FileTransferDescription private constructor() :
    GenericDescription("description", Namespace.JINGLE_APPS_FILE_TRANSFER) {

    fun getFile(): File {
        val fileElement = this.findChild("file", Namespace.JINGLE_APPS_FILE_TRANSFER)
        if (fileElement == null) {
            Log.d(Config.LOGTAG, "no file? $this")
            throw IllegalStateException("file transfer description has no file")
        }
        val name = fileElement.findChildContent("name")
        val sizeAsString = fileElement.findChildContent("size")
        val mediaType = fileElement.findChildContent("mediaType")
        if (name.isNullOrEmpty() || sizeAsString.isNullOrEmpty()) {
            throw IllegalStateException("File definition is missing name and/or size")
        }
        val size = Longs.tryParse(sizeAsString) ?: throw IllegalStateException("Invalid file size")
        val hashes = findHashes(fileElement.getChildren())
        return File(size, name, mediaType, hashes)
    }

    sealed class SessionInfo(@JvmField val name: String?) {
        abstract fun asElement(): Element
    }

    class Checksum(name: String?, @JvmField val hashes: List<Hash>) : SessionInfo(name) {

        override fun toString(): String =
            MoreObjects.toStringHelper(this).add("hashes", hashes).toString()

        override fun asElement(): Element {
            val checksum = Element("checksum", Namespace.JINGLE_APPS_FILE_TRANSFER)
            checksum.setAttribute("name", name)
            val file = checksum.addChild("file", Namespace.JINGLE_APPS_FILE_TRANSFER)
            for (hash in hashes) {
                val element = file.addChild("hash", Namespace.HASHES)
                val algorithm = hash.algorithm ?: throw NullPointerException()
                element.setAttribute(
                    "algo",
                    CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, algorithm.toString()),
                )
                element.setContent(BaseEncoding.base64().encode(hash.hash))
            }
            return checksum
        }
    }

    class Received(name: String?) : SessionInfo(name) {

        override fun asElement(): Element {
            val element = Element("received", Namespace.JINGLE_APPS_FILE_TRANSFER)
            element.setAttribute("name", name)
            return element
        }
    }

    class File(
        @JvmField val size: Long,
        @JvmField val name: String,
        @JvmField val mediaType: String?,
        @JvmField val hashes: List<Hash>,
    ) {

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("size", size)
                .add("name", name)
                .add("mediaType", mediaType)
                .add("hashes", hashes)
                .toString()
    }

    class Hash(@JvmField val hash: ByteArray, @JvmField val algorithm: Algorithm?) {

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("hash", hash)
                .add("algorithm", algorithm)
                .toString()
    }

    enum class Algorithm {
        SHA_1,
        SHA_256;

        companion object {
            @JvmStatic
            fun of(value: String?): Algorithm? {
                if (value.isNullOrEmpty()) {
                    return null
                }
                return Algorithm.valueOf(
                    CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_UNDERSCORE, value)
                )
            }
        }
    }

    companion object {
        @JvmStatic
        fun of(fileDescription: File): FileTransferDescription {
            val description = FileTransferDescription()
            val file = description.addChild("file", Namespace.JINGLE_APPS_FILE_TRANSFER)
            file.addChild("name").setContent(fileDescription.name)
            file.addChild("size").setContent(fileDescription.size.toString())
            if (fileDescription.mediaType != null) {
                file.addChild("mediaType").setContent(fileDescription.mediaType)
            }
            return description
        }

        @JvmStatic
        fun getSessionInfo(jingle: Jingle): SessionInfo? {
            Preconditions.checkNotNull(jingle)
            Preconditions.checkArgument(
                jingle.getAction() == Jingle.Action.SESSION_INFO,
                "jingle packet is not a session-info",
            )
            val checksum = jingle.findChild("checksum", Namespace.JINGLE_APPS_FILE_TRANSFER)
            if (checksum != null) {
                val file = checksum.findChild("file", Namespace.JINGLE_APPS_FILE_TRANSFER)
                val name = checksum.getAttribute("name")
                if (file == null || name.isNullOrEmpty()) {
                    return null
                }
                return Checksum(name, findHashes(file.getChildren()))
            }
            val received = jingle.findChild("received", Namespace.JINGLE_APPS_FILE_TRANSFER)
            if (received != null) {
                val name = received.getAttribute("name")
                if (name.isNullOrEmpty()) {
                    return Received(name)
                }
            }
            return null
        }

        private fun findHashes(elements: List<Element>): List<Hash> {
            val hashes = ImmutableList.builder<Hash>()
            for (child in elements) {
                if ("hash" == child.getName() && Namespace.HASHES == child.getNamespace()) {
                    val algorithm: Algorithm?
                    try {
                        algorithm = Algorithm.of(child.getAttribute("algo"))
                    } catch (e: IllegalArgumentException) {
                        continue
                    }
                    val content = child.getContent()
                    if (content.isNullOrEmpty()) {
                        continue
                    }
                    if (BaseEncoding.base64().canDecode(content)) {
                        hashes.add(Hash(BaseEncoding.base64().decode(content), algorithm))
                    }
                }
            }
            return hashes.build()
        }

        @JvmStatic
        fun upgrade(element: Element): FileTransferDescription {
            Preconditions.checkArgument(
                "description" == element.getName(),
                "Name of provided element is not description",
            )
            Preconditions.checkArgument(
                element.getNamespace().equals(Namespace.JINGLE_APPS_FILE_TRANSFER),
                "Element does not match a file transfer namespace",
            )
            val description = FileTransferDescription()
            description.bindTo(element)
            return description
        }
    }
}
