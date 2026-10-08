package uk.xa0.tulkki.xmpp.jingle

import com.google.common.collect.ArrayListMultimap

/**
 * Builds a `SessionDescription` from the values `SessionDescription.parse`/`of` collect.
 *
 * Ported from Java by lane `C`. Decisions taken rather than inherited:
 *
 * 1. **The fluent setters stay one per Java overload and return `this`.**
 * 2. **`attributes` and `media` stay nullable**, because Java's fields started null and
 *    `createSessionDescription` handed them straight to the `SessionDescription` constructor; the
 *    caller always sets both.
 * 3. **`setMedia` takes the read-only `List`**, the permissive spelling for a Kotlin caller passing
 *    an `ImmutableList`; the descriptor is `java.util.List`, as Java's was.
 */
class SessionDescriptionBuilder {
    private var version = 0
    private var name: String? = null
    private var connectionData: String? = null
    private var attributes: ArrayListMultimap<String, String>? = null
    private var media: List<SessionDescription.Media>? = null

    fun setVersion(version: Int): SessionDescriptionBuilder {
        this.version = version
        return this
    }

    fun setName(name: String?): SessionDescriptionBuilder {
        this.name = name
        return this
    }

    fun setConnectionData(connectionData: String?): SessionDescriptionBuilder {
        this.connectionData = connectionData
        return this
    }

    fun setAttributes(attributes: ArrayListMultimap<String, String>?): SessionDescriptionBuilder {
        this.attributes = attributes
        return this
    }

    fun setMedia(media: List<SessionDescription.Media>?): SessionDescriptionBuilder {
        this.media = media
        return this
    }

    fun createSessionDescription(): SessionDescription =
        SessionDescription(
            version,
            name,
            connectionData,
            attributes ?: throw NullPointerException(),
            media ?: throw NullPointerException(),
        )
}
