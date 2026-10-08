package uk.xa0.tulkki.xmpp.jingle.stanzas

import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.primitives.Longs
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The in-band bytestream transport (XEP-0261).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **The two constructors stay two**: the private `(name, xmlns)` one the companion builds
 *    through, and the public `(transportId, blockSize)` one. Kotlin keeps both as secondary
 *    constructors rather than merging them.
 * 2. **`upgrade` is `@JvmStatic`**: `FileTransferContentMap`, `JingleFileTransferConnection` and
 *    `InbandBytestreamsTransport` call it statically.
 * 3. **`getBlockSize` answers `Long?`** because Java's `Longs.tryParse` can answer null.
 */
class IbbTransportInfo : GenericTransportInfo {

    private constructor(name: String, xmlns: String) : super(name, xmlns)

    constructor(transportId: String, blockSize: Int) :
        super("transport", Namespace.JINGLE_TRANSPORTS_IBB) {
        Preconditions.checkNotNull(transportId, "Transport ID can not be null")
        Preconditions.checkArgument(blockSize > 0, "Block size must be larger than 0")
        this.setAttribute("block-size", blockSize)
        this.setAttribute("sid", transportId)
    }

    fun getTransportId(): String? = this.getAttribute("sid")

    fun getBlockSize(): Long? {
        val blockSize = this.getAttribute("block-size")
        return if (blockSize.isNullOrEmpty()) null else Longs.tryParse(blockSize)
    }

    companion object {
        @JvmStatic
        fun upgrade(element: Element): IbbTransportInfo {
            Preconditions.checkArgument(
                "transport" == element.getName(),
                "Name of provided element is not transport",
            )
            Preconditions.checkArgument(
                Namespace.JINGLE_TRANSPORTS_IBB == element.getNamespace(),
                "Element does not match ibb transport namespace",
            )
            val transportInfo = IbbTransportInfo("transport", Namespace.JINGLE_TRANSPORTS_IBB)
            transportInfo.bindTo(element)
            return transportInfo
        }
    }
}
