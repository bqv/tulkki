package uk.xa0.tulkki.xmpp.jingle

import com.google.common.base.Joiner
import com.google.common.collect.Multimap

/**
 * Builds a `SessionDescription.Media` line by line.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The fluent setters stay one per Java overload and return `this`**, so
 *    `SessionDescription.parse`/`of` read as Java left them.
 * 2. **`createMedia` reaches each unset field through `?: throw NullPointerException()`.** Java's
 *    fields started null and were handed straight to the `Media` constructor; Kotlin's `Media`
 *    parameters are non-null, so the same failure is spelled out at the same place (the values are
 *    always set on every path).
 * 3. `setFormats` takes `List<Int>` for Java's `List<Integer>` and joins with `Joiner.on(' ')`,
 *    which is what produced `format`.
 */
class MediaBuilder {
    private var media: String? = null
    private var port = 0
    private var protocol: String? = null
    private var format: String? = null
    private var connectionData: String? = null
    private var attributes: Multimap<String, String>? = null

    fun setMedia(media: String?): MediaBuilder {
        this.media = media
        return this
    }

    fun setPort(port: Int): MediaBuilder {
        this.port = port
        return this
    }

    fun setProtocol(protocol: String?): MediaBuilder {
        this.protocol = protocol
        return this
    }

    fun setFormats(formats: List<Int>): MediaBuilder {
        this.format = Joiner.on(' ').join(formats)
        return this
    }

    fun setFormat(format: String?): MediaBuilder {
        this.format = format
        return this
    }

    fun setConnectionData(connectionData: String?): MediaBuilder {
        this.connectionData = connectionData
        return this
    }

    fun setAttributes(attributes: Multimap<String, String>?): MediaBuilder {
        this.attributes = attributes
        return this
    }

    fun createMedia(): SessionDescription.Media =
        SessionDescription.Media(
            media ?: throw NullPointerException(),
            port,
            protocol ?: throw NullPointerException(),
            format ?: throw NullPointerException(),
            connectionData ?: throw NullPointerException(),
            attributes ?: throw NullPointerException(),
        )
}
