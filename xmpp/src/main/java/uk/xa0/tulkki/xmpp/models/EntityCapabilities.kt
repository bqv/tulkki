package uk.xa0.tulkki.xmpp.models

import com.google.common.base.Strings
import com.google.common.collect.ComparisonChain
import com.google.common.collect.Ordering
import com.google.common.hash.Hashing
import com.google.common.io.BaseEncoding
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Comparator
import uk.xa0.tulkki.xmpp.models.data.Data
import uk.xa0.tulkki.xmpp.models.data.Field
import uk.xa0.tulkki.xmpp.models.disco.info.Feature
import uk.xa0.tulkki.xmpp.models.disco.info.Identity
import uk.xa0.tulkki.xmpp.models.disco.info.InfoQuery

/**
 * XEP-0115 entity capabilities, the legacy hashing half. Converted from the Java.
 *
 * The two sort keys whose Java accessor is now a Kotlin `String?` (`Data.getFormType`,
 * `Identity.getLang`/`getIdentityName`) keep the Java comparator's own null behaviour: `comparing`
 * dereferences the first operand, so a null key throws `NullPointerException` at the same
 * comparison and a length-one collection never compares at all.
 *
 * `EntityCapsHash`'s constructor was `protected` in Java, which let its same-package outer class
 * call it; Kotlin's `protected` is subclass-only, so the constructor is `private` and a companion
 * factory constructs it, exactly where the Java's `hash` did. No visibility widens.
 */
class EntityCapabilities {

    companion object {

        @JvmStatic
        fun hash(info: InfoQuery): EntityCapsHash {
            val s = StringBuilder()
            val orderedIdentities =
                Ordering.from(
                        Comparator<Identity> { a, b ->
                            ComparisonChain.start()
                                .compare(blankNull(a.getCategory()), blankNull(b.getCategory()))
                                .compare(blankNull(a.getType()), blankNull(b.getType()))
                                .compare(blankNull(a.getLang()), blankNull(b.getLang()))
                                .compare(
                                    blankNull(a.getIdentityName()),
                                    blankNull(b.getIdentityName()),
                                )
                                .result()
                        },
                    )
                    .sortedCopy(info.getIdentities())

            for (id in orderedIdentities) {
                s.append(blankNull(id.getCategory()))
                    .append("/")
                    .append(blankNull(id.getType()))
                    .append("/")
                    .append(blankNull(id.getLang()))
                    .append("/")
                    .append(blankNull(id.getIdentityName()))
                    .append("<")
            }

            // `Feature.getVar()` is a Kotlin `String?`; its Java reader treated it as a `String`
            // and `clean` dereferenced it, so the null is stated where the Java first used it.
            val features =
                info.getFeatures().map { it.getVar() ?: throw NullPointerException() }.sorted()
            for (feature in features) {
                s.append(clean(feature)).append("<")
            }

            val extensions =
                Ordering.from(
                        Comparator<Data> { lhs, rhs ->
                            (lhs.getFormType() ?: throw NullPointerException()).compareTo(
                                rhs.getFormType() ?: throw NullPointerException(),
                            )
                        },
                    )
                    .sortedCopy(info.getExtensions(Data::class.java))

            for (extension in extensions) {
                // `Data.getFormType()` is a Kotlin `String?`; the Java handed it to `clean`,
                // whose `replace` is where it dereferenced it.
                s.append(clean(extension.getFormType() ?: throw NullPointerException()))
                    .append("<")
                val fields =
                    Ordering.from(
                            Comparator<Field> { lhs, rhs ->
                                Strings.nullToEmpty(lhs.getFieldName())
                                    .compareTo(Strings.nullToEmpty(rhs.getFieldName()))
                            },
                        )
                        .sortedCopy(extension.getFields())
                for (field in fields) {
                    s.append(Strings.nullToEmpty(field.getFieldName())).append("<")
                    val values = Ordering.natural<String>().sortedCopy(field.getValues())
                    for (value in values) {
                        s.append(blankNull(value)).append("<")
                    }
                }
            }
            return EntityCapsHash.ofBytes(
                Hashing.sha1().hashString(s.toString(), StandardCharsets.UTF_8).asBytes(),
            )
        }

        private fun clean(s: String): String = s.replace("<", "&lt;")

        private fun blankNull(s: String?): String = if (s == null) "" else clean(s)
    }

    abstract class Hash protected constructor(@JvmField val hash: ByteArray) {

        fun encoded(): String = BaseEncoding.base64().encode(hash)

        abstract fun capabilityNode(node: String): String

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val otherHash = other as Hash
            return Arrays.equals(hash, otherHash.hash)
        }

        override fun hashCode(): Int = Arrays.hashCode(hash)
    }

    class EntityCapsHash private constructor(hash: ByteArray) : Hash(hash) {

        override fun capabilityNode(node: String): String =
            String.format("%s#%s", node, encoded())

        companion object {

            internal fun ofBytes(hash: ByteArray): EntityCapsHash = EntityCapsHash(hash)

            @JvmStatic
            fun of(encoded: String): EntityCapsHash =
                EntityCapsHash(BaseEncoding.base64().decode(encoded))
        }
    }
}
