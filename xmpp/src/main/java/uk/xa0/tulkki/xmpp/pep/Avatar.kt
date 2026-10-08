package uk.xa0.tulkki.xmpp.pep

import android.util.Base64
import io.ipfs.cid.Cid
import java.security.NoSuchAlgorithmException
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

/**
 * A PEP/vCard avatar: the metadata half of XEP-0084 and the vCard-temp photo hash.
 *
 * Ported from Java by the port-14 `xmppport2` lane. Decisions taken rather than inherited:
 *
 * 1. **The eight public fields stay Java fields**, so they are `@JvmField var`. The island's Java
 *    callers read *and write* them (`FileBackend.java:1942-2068` assigns `sha1sum`, `image`, `type`,
 *    `width`, `height`, `size`; `MessageParser:365` and `PresenceParser:176` assign `owner`;
 *    `XmppConnectionService:6594` switches on `origin`), and a Kotlin `var` without `@JvmField`
 *    would have replaced each with a getter/setter pair and broken every one of those sites.
 * 2. **`Origin` stays a nested enum**, so `Avatar.Origin.PEP`/`Avatar.Origin.VCARD` keep resolving.
 * 3. **The two parsers are `@JvmStatic`**, because Java calls `Avatar.parseMetadata(items)` and
 *    `Avatar.parsePresence(element)` as statics.
 * 4. **`equals` keeps Java's exact dereference.** Java wrote
 *    `other.getFilename().equals(this.getFilename())`: the receiver's own null threw, while the
 *    argument was tolerated by `String.equals`. The `!!` on the receiver keeps that asymmetry; a
 *    null-safe `==` would have turned a throw into `false`.
 * 5. **`getFilename` answers `String?` and `cid` answers `Cid?`**, as Java's unannotated returns
 *    allowed. `parsePresence` takes a nullable element, Java's first statement being a null test.
 * 6. `Integer.parseInt`/`Long.parseLong` are Kotlin's `toInt`/`toLong`, which throw the identical
 *    `NumberFormatException`; the catch is unchanged.
 */
class Avatar {

    enum class Origin {
        PEP,
        VCARD
    }

    @JvmField
    var type: String? = null

    @JvmField
    var sha1sum: String? = null

    @JvmField
    var image: String? = null

    @JvmField
    var height: Int = 0

    @JvmField
    var width: Int = 0

    @JvmField
    var size: Long = 0

    @JvmField
    var owner: Jid? = null

    @JvmField
    var origin: Origin = Origin.PEP // default to maintain compat

    fun getImageAsBytes(): ByteArray = Base64.decode(image, Base64.DEFAULT)

    fun getFilename(): String? = sha1sum

    override fun equals(other: Any?): Boolean {
        if (other != null && other is Avatar) {
            return other.getFilename()!!.equals(this.getFilename())
        }
        return false
    }

    fun cid(): Cid? {
        val hash = sha1sum ?: return null
        return try {
            CryptoHelper.cid(CryptoHelper.hexToBytes(hash), "sha-1")
        } catch (e: NoSuchAlgorithmException) {
            null
        }
    }

    companion object {

        @JvmStatic
        fun parseMetadata(items: Element): Avatar? {
            val item = items.findChild("item") ?: return null
            val metadata = item.findChild("metadata") ?: return null
            val primaryId = item.getAttribute("id") ?: return null
            for (child in metadata.getChildren()) {
                if (child.getName() == "info" && primaryId == child.getAttribute("id")) {
                    val avatar = Avatar()
                    val height = child.getAttribute("height")
                    val width = child.getAttribute("width")
                    val size = child.getAttribute("bytes")
                    try {
                        if (height != null) {
                            avatar.height = height.toInt()
                        }
                        if (width != null) {
                            avatar.width = width.toInt()
                        }
                        if (size != null) {
                            avatar.size = size.toLong()
                        }
                    } catch (e: NumberFormatException) {
                        return null
                    }
                    avatar.type = child.getAttribute("type")
                    val hash = child.getAttribute("id")
                    if (!isValidSHA1(hash)) {
                        return null
                    }
                    avatar.sha1sum = hash
                    avatar.origin = Origin.PEP
                    return avatar
                }
            }
            return null
        }

        @JvmStatic
        fun parsePresence(x: Element?): Avatar? {
            val hash = x?.findChildContent("photo") ?: return null
            if (!isValidSHA1(hash)) {
                return null
            }
            val avatar = Avatar()
            avatar.sha1sum = hash
            avatar.origin = Origin.VCARD
            return avatar
        }

        private fun isValidSHA1(s: String?): Boolean =
            s != null && s.matches(Regex("[a-fA-F0-9]{40}"))
    }
}
