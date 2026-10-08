package uk.xa0.tulkki.xmpp.models

import com.google.common.base.MoreObjects

/**
 * A result-set-management cursor: an [Order] and the id it points at. Converted from the Java value
 * class. The two public final fields stay fields (`@JvmField`) so any Java reader keeps the same
 * access, and `toString`/`equals`/`hashCode` keep the Guava `MoreObjects`/`Objects` shapes they had
 * rather than a `data class`'s different spelling.
 */
class Range(@JvmField val order: Order, @JvmField val id: String?) {

    override fun toString(): String =
        MoreObjects.toStringHelper(this).add("order", order).add("id", id).toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val range = other as Range
        return order == range.order && id == range.id
    }

    override fun hashCode(): Int = com.google.common.base.Objects.hashCode(order, id)

    enum class Order {
        NORMAL,
        REVERSE,
    }
}
