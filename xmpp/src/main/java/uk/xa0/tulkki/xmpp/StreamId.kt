package uk.xa0.tulkki.xmpp

import com.google.common.base.MoreObjects
import uk.xa0.tulkki.xmpp.utils.Resolver

/**
 * Tulkki: the resumable stream's id and location, out of `XmppConnection`.
 *
 * The Java held this as a `private static class` nested in `XmppConnection`; both members are read
 * as fields (`streamId.id`, `streamId.location`), so both stay `@JvmField`, and `toString` keeps
 * the Java's Guava `toStringHelper` shape.
 */
internal class StreamId(@JvmField val id: String, @JvmField val location: Resolver.Result?) {

    override fun toString(): String =
        MoreObjects.toStringHelper(this)
            .add("id", id)
            .add("location", location)
            .toString()
}
