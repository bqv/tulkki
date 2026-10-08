package uk.xa0.tulkki.libs

import java.io.Serializable
import java.net.IDN
import java.util.regex.Pattern
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Domainpart
import org.jxmpp.jid.parts.Localpart
import org.jxmpp.jid.parts.Resourcepart
import org.jxmpp.stringprep.XmppStringprepException

/**
 * One XMPP address, parsed and stringprepped through jxmpp.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was `uk.xa0.tulkki.xmpp.Jid` - the island's own value
 * type, named by every module. `:libs` is the one module in the order that every side may reach, so
 * a wire-shaped value type can live there and the island's refs can name it without naming the
 * island: the move is what let `ReactionRef` and `StoryRef` leave `uk.xa0.tulkki.xmpp.refs`. Two
 * island dependencies had to go with it, because `:libs` sits below `:xmpp` and may name nothing of
 * it: `IP` moved beside this file (the IPv4/IPv6 literal test `ofUserInput` needs), and
 * `Invalid.hasValidFrom(Stanza)` - the one member that named a wire type - moved to its only caller,
 * `MessageParser`. No signature this class declares changed.
 *
 * Converted from Java by lane `B` (2026-10-08). The port keeps Java's exact shape because the type
 * is named from every module: the `of*` factories are a `@JvmStatic` companion, `getLocal`,
 * `getDomain` and `getResource` are properties (so Kotlin's synthetic-property spellings keep
 * compiling beside the function calls Java makes), and `CharSequence` is implemented the Kotlin
 * way - `length`/`get`/`subSequence`, whose JVM names are `length()`/`charAt`/`subSequence`.
 *
 * Two nullabilities are load-bearing and stay nullable, because the Java answered `null` and the
 * callers rely on it (`DisplayNames`' `jid.local ?: jid.domain.toString()` for a domain-only JID,
 * and `RawBlockable`'s resource for a full one): `local` and `resource`. At the handful of Kotlin
 * call sites where the Java would have thrown instead - `lowercase`, `trim`, `sorted`, a SASL
 * username - the null is rejected with `?: throw NullPointerException()`, the tree's spelling for
 * the same NPE at the same moment.
 */
abstract class Jid : Comparable<Jid>, Serializable, CharSequence {

    abstract fun isFullJid(): Boolean

    abstract fun isBareJid(): Boolean

    abstract fun isDomainJid(): Boolean

    abstract fun asBareJid(): Jid

    abstract fun withResource(resource: CharSequence): Jid

    abstract fun getLocal(): String?

    abstract fun getDomain(): Jid

    abstract fun getResource(): String?

    private class InternalRepresentation(private val inner: org.jxmpp.jid.Jid) : Jid() {

        override fun isFullJid(): Boolean = inner.isEntityFullJid() || inner.isDomainFullJid()

        override fun isBareJid(): Boolean = inner.isDomainBareJid() || inner.isEntityBareJid()

        override fun isDomainJid(): Boolean = inner.isDomainBareJid() || inner.isDomainFullJid()

        override fun asBareJid(): Jid = InternalRepresentation(inner.asBareJid())

        override fun withResource(resource: CharSequence): Jid {
            val localpart = inner.getLocalpartOrNull()
            try {
                val resourcepart = Resourcepart.from(resource.toString())
                return if (localpart == null) {
                    InternalRepresentation(JidCreate.domainFullFrom(inner.getDomain(), resourcepart))
                } else {
                    InternalRepresentation(
                        JidCreate.fullFrom(localpart, inner.getDomain(), resourcepart),
                    )
                }
            } catch (e: XmppStringprepException) {
                throw IllegalArgumentException(e)
            }
        }

        override fun getLocal(): String? {
            val localpart = inner.getLocalpartOrNull()
            return localpart?.toString()
        }

        override fun getDomain(): Jid = InternalRepresentation(inner.asDomainBareJid())

        override fun getResource(): String? {
            val resourcepart = inner.getResourceOrNull()
            return resourcepart?.toString()
        }

        override fun toString(): String = inner.toString()

        override val length: Int
            get() = inner.length

        override fun get(index: Int): Char = inner[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            inner.subSequence(startIndex, endIndex)

        override fun compareTo(other: Jid): Int =
            if (other is InternalRepresentation) {
                inner.compareTo(other.inner)
            } else {
                0
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as InternalRepresentation
            return inner == that.inner
        }

        override fun hashCode(): Int = inner.hashCode()
    }

    class Invalid internal constructor(private val value: String) : Jid() {

        override fun toString(): String = value

        override fun isFullJid(): Boolean = throw AssertionError("Not implemented")

        override fun isBareJid(): Boolean = throw AssertionError("Not implemented")

        override fun isDomainJid(): Boolean = throw AssertionError("Not implemented")

        /**
         * Best effort bare form of an address we could not parse. Callers reach this from stanza
         * handling, where the address is chosen by a remote party, so throwing would turn a
         * malformed attribute into a crash. Falls back to returning this instance when even the
         * part before the resource separator does not parse.
         *
         * <p>Upstream takes the first segment with
         * {@code Iterables.getFirst(Splitter.on('/').split(value), null)}; the plain
         * {@code indexOf}/{@code substring} below is that same segment - measured against Guava
         * 33.3.1-jre, the version the build resolves, which answers {@code ""} for the empty input
         * (so its {@code bare == null} arm is unreachable, and the empty segment reaches
         * {@code Jid.of("")}'s {@code IllegalArgumentException}) and {@code "a@b"} for
         * {@code "a@b/room"}, exactly as the substring does. Both roads answer {@code this}, so the
         * rewrite keeps the port out of Guava's imports at no behavioural cost;
         * {@code JidInvalidBareFormTest} pins all three outcomes.
         */
        override fun asBareJid(): Jid {
            val separator = value.indexOf('/')
            val bare = if (separator == -1) value else value.substring(0, separator)
            try {
                return Jid.of(bare).asBareJid()
            } catch (e: IllegalArgumentException) {
                return this
            }
        }

        override fun withResource(resource: CharSequence): Jid =
            throw AssertionError("Not implemented")

        override fun getLocal(): String? = throw AssertionError("Not implemented")

        override fun getDomain(): Jid = throw AssertionError("Not implemented")

        override fun getResource(): String? = throw AssertionError("Not implemented")

        override val length: Int
            get() = value.length

        override fun get(index: Int): Char = value[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            value.subSequence(startIndex, endIndex)

        override fun compareTo(other: Jid): Int = throw AssertionError("Not implemented")

        companion object {

            @JvmStatic
            fun getNullForInvalid(jid: Jid?): Jid? = if (jid is Invalid) null else jid

            @JvmStatic
            fun isValid(jid: Jid?): Boolean = jid !is Invalid
        }
    }

    companion object {

        private val HOSTNAME_PATTERN: Pattern =
            Pattern.compile(
                "^(?=.{1,253}$)(?:xn--)?[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.(?:xn--)?[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
            )

        @JvmStatic
        fun of(local: CharSequence?, domain: CharSequence, resource: CharSequence?): Jid {
            if (local == null) {
                return if (resource == null) {
                    ofDomain(domain)
                } else {
                    ofDomainAndResource(domain, resource)
                }
            }
            if (resource == null) {
                return ofLocalAndDomain(local, domain)
            }
            try {
                return InternalRepresentation(
                    JidCreate.entityFullFrom(
                        Localpart.from(local.toString()),
                        Domainpart.from(domain.toString()),
                        Resourcepart.from(resource.toString()),
                    ),
                )
            } catch (e: XmppStringprepException) {
                throw IllegalArgumentException(e)
            }
        }

        @JvmStatic
        fun ofDomain(domain: CharSequence): Jid {
            try {
                return InternalRepresentation(JidCreate.domainBareFrom(domain))
            } catch (e: XmppStringprepException) {
                throw IllegalArgumentException(e)
            }
        }

        @JvmStatic
        fun ofLocalAndDomain(local: CharSequence, domain: CharSequence): Jid {
            try {
                return InternalRepresentation(
                    JidCreate.bareFrom(
                        Localpart.from(local.toString()),
                        Domainpart.from(domain.toString()),
                    ),
                )
            } catch (e: XmppStringprepException) {
                throw IllegalArgumentException(e)
            }
        }

        @JvmStatic
        fun ofDomainAndResource(domain: CharSequence, resource: CharSequence): Jid {
            try {
                return InternalRepresentation(
                    JidCreate.domainFullFrom(
                        Domainpart.from(domain.toString()),
                        Resourcepart.from(resource.toString()),
                    ),
                )
            } catch (e: XmppStringprepException) {
                throw IllegalArgumentException(e)
            }
        }

        @JvmStatic
        fun of(input: CharSequence): Jid {
            if (input is Jid) {
                return input
            }
            try {
                return InternalRepresentation(JidCreate.from(input))
            } catch (e: XmppStringprepException) {
                throw IllegalArgumentException(e)
            }
        }

        @JvmStatic
        fun ofUserInput(input: CharSequence): Jid {
            val jid = of(input)
            val domain = jid.getDomain().toString()
            if (domain.isEmpty()) {
                throw IllegalArgumentException("Domain can not be empty")
            }
            val codedDomain = IDN.toASCII(domain)
            if (HOSTNAME_PATTERN.matcher(codedDomain).matches() || IP.matches(codedDomain)) {
                return jid
            }
            throw IllegalArgumentException("Invalid hostname")
        }

        @JvmStatic
        fun ofOrInvalid(input: String): Jid = ofOrInvalid(input, false)

        /**
         * @param jid a string representation of the jid to parse
         * @param fallback indicates whether an attempt should be made to parse a bare version of
         *   the jid
         * @return an instance of Jid; may be Jid.Invalid
         */
        @JvmStatic
        fun ofOrInvalid(jid: String, fallback: Boolean): Jid {
            try {
                return Jid.of(jid)
            } catch (e: IllegalArgumentException) {
                return invalidOf(jid, fallback)
            }
        }

        private fun invalidOf(jid: String, fallback: Boolean): Jid {
            val pos = jid.indexOf('/')
            if (fallback && pos >= 0 && jid.length >= pos + 1) {
                if (jid.substring(pos + 1).trim { it <= ' ' }.isEmpty()) {
                    return Jid.of(jid.substring(0, pos))
                }
            }
            return Invalid(jid)
        }
    }
}
