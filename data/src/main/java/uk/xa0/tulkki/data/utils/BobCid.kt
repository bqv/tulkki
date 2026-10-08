package uk.xa0.tulkki.data.utils

import android.net.Uri
import io.ipfs.cid.Cid
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import java.net.URI
import java.security.NoSuchAlgorithmException

/**
 * The `cid:` grammar of XEP-0234/BoB, moved down out of `uk.xa0.tulkki.app.extras.BobTransfer` by 3.7 pair 2.
 *
 * `BobTransfer` itself cannot move: it is a `Transferable` that names `R` and the connection service, and
 * `:data` may not. But three of the eight files that named it - `Conversation`, `Message` and `FileBackend` -
 * only ever asked it to turn a reference into a [Cid], so that reading is what moved; the class keeps its own
 * `cid` overloads as one-line forwards, because the other five referrers are `:ui` and `:xmpp` and repointing
 * them would have grown a forbidden edge rather than shrunk one.
 */
object BobCid {

    /** The cid of a `cid:` [Uri], or `null` when it is not one. */
    @JvmStatic
    fun cid(uri: Uri?): Cid? {
        if (uri == null || uri.scheme == null || uri.scheme != "cid") return null
        return cid(uri.schemeSpecificPart)
    }

    /** The cid of a `cid:` [URI], or `null` when it is not one. */
    @JvmStatic
    fun cid(uri: URI?): Cid? {
        if (uri == null || uri.scheme == null || uri.scheme != "cid") return null
        return cid(uri.schemeSpecificPart)
    }

    /**
     * The cid carried by a BoB reference (`<hash>+<algo>@bob.xmpp.org`), or `null` when it is malformed.
     */
    @JvmStatic
    fun cid(bobCid: String): Cid? {
        if (!bobCid.contains("@") || !bobCid.contains("+")) return null
        val cidParts = bobCid.split("@")[0].split("+")
        return try {
            CryptoHelper.cid(CryptoHelper.hexToBytes(cidParts[1]), cidParts[0])
        } catch (e: NoSuchAlgorithmException) {
            null
        }
    }
}
