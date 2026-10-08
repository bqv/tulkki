package uk.xa0.tulkki.xmpp.models

import com.google.common.base.Joiner
import com.google.common.base.Strings
import com.google.common.collect.Collections2
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Ordering
import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import com.google.common.io.BaseEncoding
import java.nio.charset.StandardCharsets
import java.util.Objects
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.data.Data
import uk.xa0.tulkki.xmpp.models.data.Field
import uk.xa0.tulkki.xmpp.models.data.Value
import uk.xa0.tulkki.xmpp.models.disco.info.Feature
import uk.xa0.tulkki.xmpp.models.disco.info.Identity
import uk.xa0.tulkki.xmpp.models.disco.info.InfoQuery

/**
 * XEP-0390 entity capabilities, the hashing half. Converted from the Java; the two statics keep
 * their JVM shape through `@JvmStatic`, and the private static `algorithm(InfoQuery)` is renamed
 * `algorithmInput` only because Kotlin cannot resolve a call to it beside a parameter of the same
 * name - it is private, so nothing outside observes the rename.
 *
 * Both nested types stay nested, because `IllegalInfoQueryException`'s binary name
 * (`EntityCapabilities2$IllegalInfoQueryException`) is asserted by name in
 * `app/src/test/**/StanzaHardeningTest.java`, and `EntityCaps2Hash` is spelled
 * `EntityCapabilities2.EntityCaps2Hash` from `capabilties/Capabilities.kt`. Neither constructor can
 * stay the Java one: the outer class may not call a nested class's `private`/`protected`
 * constructor from Kotlin (measured with kotlinc 2.3: "cannot access 'constructor(...)': it is
 * private in '...'"), so each gains a companion factory and keeps its constructor `private` - a
 * restructure, never a visibility widening.
 */
class EntityCapabilities2 {

    companion object {

        private val ALLOW_LIST_EXTENSIONS: Set<ExtensionFactory.Id> =
            ImmutableSet.of(
                // `id` answers `Id?` where the Java handed over a `Map.get`; the Java passed all
                // three straight into Guava's `ImmutableSet.of`, which rejects null with the same
                // `NullPointerException` these hand-overs now spell.
                ExtensionFactory.id(Identity::class.java) ?: throw NullPointerException(),
                ExtensionFactory.id(Feature::class.java) ?: throw NullPointerException(),
                ExtensionFactory.id(Data::class.java) ?: throw NullPointerException(),
            )

        private const val UNIT_SEPARATOR = '\u001f'
        private const val RECORD_SEPARATOR = '\u001e'
        private const val GROUP_SEPARATOR = '\u001d'
        private const val FILE_SEPARATOR = '\u001c'

        @JvmStatic
        @Throws(IllegalInfoQueryException::class)
        fun hash(info: InfoQuery): EntityCaps2Hash = hash(Hash.Algorithm.SHA_256, info)

        @JvmStatic
        @Throws(IllegalInfoQueryException::class)
        fun hash(algorithm: Hash.Algorithm, info: InfoQuery): EntityCaps2Hash {
            val result = algorithmInput(info)
            val hashFunction = toHashFunction(algorithm)
            return EntityCaps2Hash.ofBytes(
                algorithm,
                hashFunction.hashString(result, StandardCharsets.UTF_8).asBytes(),
            )
        }

        private fun toHashFunction(algorithm: Hash.Algorithm): HashFunction =
            when (algorithm) {
                Hash.Algorithm.SHA_1 -> Hashing.sha1()
                Hash.Algorithm.SHA_256 -> Hashing.sha256()
                Hash.Algorithm.SHA_512 -> Hashing.sha512()
                else -> throw IllegalArgumentException("Unknown hash algorithm")
            }

        @Suppress("unused")
        private fun asHex(message: String): String =
            message.toByteArray(StandardCharsets.UTF_8).joinToString(" ") {
                String.format("%02x", it)
            }

        @Throws(IllegalInfoQueryException::class)
        private fun algorithmInput(infoQuery: InfoQuery): String {
            checkElementsAllowList(infoQuery)
            return features(infoQuery.getFeatures()) +
                identities(infoQuery.getIdentities()) +
                extensions(infoQuery.getExtensions(Data::class.java))
        }

        /**
         * XEP-0390 abort conditions. The hash only identifies a disco#info unambiguously as long as
         * every part of the document feeds into it. An element the algorithm does not know about
         * would be silently skipped, which lets two different documents share one hash - and
         * because the caps cache is keyed by that hash and shared between entities, a colliding pair
         * is enough to serve one entity's features under another's key. Refuse to produce a hash in
         * that case.
         *
         * Tulkki: port-11, upstream `a9658ba076`.
         */
        @Throws(IllegalInfoQueryException::class)
        private fun checkElementsAllowList(infoQuery: InfoQuery) {
            for (id in infoQuery.getExtensionIds()) {
                if (!ALLOW_LIST_EXTENSIONS.contains(id)) {
                    throw IllegalInfoQueryException.of("InfoQuery contains invalid elements")
                }
            }
        }

        private fun identities(identities: Collection<Identity>): String =
            Ordering.natural<String>()
                .sortedCopy(Collections2.transform(identities, { identity(it) }))
                .joinToString("") + FILE_SEPARATOR

        private fun identity(identity: Identity): String =
            Strings.nullToEmpty(identity.getCategory()) +
                UNIT_SEPARATOR +
                Strings.nullToEmpty(identity.getType()) +
                UNIT_SEPARATOR +
                Strings.nullToEmpty(identity.getLang()) +
                UNIT_SEPARATOR +
                Strings.nullToEmpty(identity.getIdentityName()) +
                UNIT_SEPARATOR +
                RECORD_SEPARATOR

        private fun features(features: Collection<Feature>): String =
            Ordering.natural<String>()
                .sortedCopy(Collections2.transform(features, { feature(it) }))
                .joinToString("") + FILE_SEPARATOR

        private fun feature(feature: Feature): String =
            Strings.nullToEmpty(feature.getVar()) + UNIT_SEPARATOR

        private fun value(value: Value): String =
            Strings.nullToEmpty(value.getContent()) + UNIT_SEPARATOR

        private fun values(values: Collection<Value>): String =
            Ordering.natural<String>()
                .sortedCopy(Collections2.transform(values, { value(it) }))
                .joinToString("")

        private fun field(field: Field): String =
            Strings.nullToEmpty(field.getFieldName()) +
                UNIT_SEPARATOR +
                values(field.getExtensions(Value::class.java)) +
                RECORD_SEPARATOR

        private fun fields(fields: Collection<Field>): String =
            Ordering.natural<String>()
                .sortedCopy(Collections2.transform(fields, { field(it) }))
                .joinToString("") + GROUP_SEPARATOR

        private fun extension(data: Data): String = fields(data.getExtensions(Field::class.java))

        @Throws(IllegalInfoQueryException::class)
        private fun extensions(extensions: Collection<Data>): String {
            for (data in extensions) {
                // Tulkki: port-11, upstream `a9658ba076` - forms are ordered by FORM_TYPE, so one
                // without it has no defined position; and a multi-item form's <item/>/<reported/>
                // content never reaches the hash at all.
                if (data.getFormType().isNullOrEmpty()) {
                    throw IllegalInfoQueryException.of(
                        "A data extension is missing a form_type",
                    )
                }
                if (data.hasChild("item", Namespace.DATA)) {
                    throw IllegalInfoQueryException.of(
                        "data form extension contains item",
                    )
                }
                if (data.hasChild("reported", Namespace.DATA)) {
                    throw IllegalInfoQueryException.of(
                        "data form extension contains reported",
                    )
                }
            }
            return Ordering.natural<String>()
                .sortedCopy(Collections2.transform(extensions, { extension(it) }))
                .joinToString("") + FILE_SEPARATOR
        }
    }

    class EntityCaps2Hash private constructor(
        @JvmField val algorithm: Hash.Algorithm,
        hash: ByteArray,
    ) : EntityCapabilities.Hash(hash) {

        override fun capabilityNode(node: String): String =
            String.format(
                "%s#%s.%s",
                Namespace.ENTITY_CAPABILITIES_2,
                algorithm.toString(),
                encoded(),
            )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            if (!super.equals(other)) return false
            val that = other as EntityCaps2Hash
            return algorithm == that.algorithm
        }

        override fun hashCode(): Int = Objects.hash(super.hashCode(), algorithm)

        companion object {

            internal fun ofBytes(algorithm: Hash.Algorithm, hash: ByteArray): EntityCaps2Hash =
                EntityCaps2Hash(algorithm, hash)

            @JvmStatic
            fun of(algorithm: Hash.Algorithm, encoded: String): EntityCaps2Hash =
                EntityCaps2Hash(algorithm, BaseEncoding.base64().decode(encoded))
        }
    }

    /** Tulkki: port-11, upstream `a9658ba076` - a document this algorithm cannot account for. */
    class IllegalInfoQueryException private constructor(message: String?) : Exception(message) {

        companion object {
            internal fun of(message: String?): IllegalInfoQueryException =
                IllegalInfoQueryException(message)
        }
    }
}
