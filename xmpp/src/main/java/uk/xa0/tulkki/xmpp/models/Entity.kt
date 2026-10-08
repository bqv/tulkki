package uk.xa0.tulkki.xmpp.models

import org.jxmpp.jid.Jid

/**
 * Tulkki: an addressed entity, and the two kinds of it disco knows. Converted from the Java.
 *
 * Nothing in the tree names this class or its nested `DiscoItem`/`Presence` (measured with `grep`
 * over every module and test source), so it is kept as a faithful conversion rather than deleted.
 *
 * Both nested constructors were `private` and the outer class's static factories called them, which
 * Kotlin does not allow (measured with kotlinc 2.3), so each nested class gains a companion factory
 * and keeps its `private` constructor.
 */
abstract class Entity private constructor(@JvmField val address: Jid) {

    class DiscoItem private constructor(address: Jid) : Entity(address) {

        companion object {
            internal fun of(address: Jid): DiscoItem = DiscoItem(address)
        }
    }

    class Presence private constructor(address: Jid) : Entity(address) {

        companion object {
            internal fun of(address: Jid): Presence = Presence(address)
        }
    }

    companion object {

        @JvmStatic
        fun presence(address: Jid): Presence = Presence.of(address)

        @JvmStatic
        fun discoItem(address: Jid): DiscoItem = DiscoItem.of(address)
    }
}
