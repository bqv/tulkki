package uk.xa0.tulkki.xmpp.models

import com.google.common.base.MoreObjects

/**
 * One page of a result set: its first and last ids and an optional count. Converted from the Java
 * value class. The three public final fields stay fields (`@JvmField`), `emptyWithCount` stays a
 * static factory (`@JvmStatic`), and `toString` keeps the Guava `MoreObjects` spelling.
 */
class Page(
    @JvmField val first: String?,
    @JvmField val last: String?,
    @JvmField val count: Int?,
) {

    override fun toString(): String =
        MoreObjects.toStringHelper(this)
            .add("first", first)
            .add("last", last)
            .add("count", count)
            .toString()

    companion object {
        @JvmStatic
        fun emptyWithCount(id: String, count: Int?): Page = Page(id, id, count)
    }
}
