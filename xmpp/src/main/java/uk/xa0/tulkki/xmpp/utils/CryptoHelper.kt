package uk.xa0.tulkki.xmpp.utils

import android.os.Bundle
import android.util.Base64
import android.util.Pair
import androidx.annotation.StringRes
import io.ipfs.cid.Cid
import io.ipfs.multihash.Multihash
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.text.Normalizer
import java.util.ArrayList
import java.util.regex.Pattern
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * The island's byte/hex/Base64/fingerprint helpers, the certificate name extraction and the CID
 * builders.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **Every public static is `@JvmStatic`** and the three public constant families are
 *    `@JvmField`/`const val` (`UUID_PATTERN`, `ONE`, `FILETRANSFER`), because the callers are Java
 *    across the tree (`:crypto`'s OMEMO and PGP code, `:data`, `:ui` and the island's own
 *    `XmppConnectionService`) and the constants are read as fields.
 * 2. **`Random.SECURE_RANDOM` is the same-package [Random]**, qualified rather than statically
 *    imported: Kotlin has no `import static`, and the name resolves to the island's own class
 *    because it sits in this package.
 * 3. **`isEqual` compares with `===`** - Java's `a == b` on two `char[]` parameters is reference
 *    equality, not content equality, and Kotlin's `==` would have called `equals`.
 * 4. **The four `@Throws` sets are kept** (`CertificateEncodingException`/`CertificateParsingException`
 *    on [extractJidAndName], `NoSuchAlgorithmException` on the fingerprint and multihash/CID
 *    helpers, `IOException` on the stream CID overload), because Java callers catch them.
 * 5. **`extractJidAndName` answers `Pair<Jid, String?>?`**: Java returned a `null` Pair on the two
 *    failure paths and a Pair with a `null` name on the success path, and it is the caller's `Pair`
 *    that is tested for null.
 * 6. **`prettifyFingerprint` takes `String?`** (Java's first statement was a null test) while
 *    `prettifyFingerprintCert` takes `String` (it dereferences immediately).
 * 7. **The `switch` on `Multihash.Type` keeps a `default -> throw`** rather than an exhaustive `when`,
 *    exactly as Java's `default` did.
 */
class CryptoHelper {

    companion object {

        @JvmField
        val UUID_PATTERN: Pattern =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        @JvmField val ONE: ByteArray = byteArrayOf(0, 0, 0, 1)

        private val CHARS: CharArray =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz123456789+-/#\$!?".toCharArray()
        private const val PW_LENGTH: Int = 25
        private val VOWELS: CharArray = "aeiou".toCharArray()
        private val CONSONANTS: CharArray = "bcfghjklmnpqrstvwxyz".toCharArray()
        private val hexArray: CharArray = "0123456789abcdef".toCharArray()

        const val FILETRANSFER: String = "?FILETRANSFERv1:"

        @JvmStatic
        fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        @JvmStatic
        fun createPassword(random: SecureRandom): String {
            val builder = StringBuilder(PW_LENGTH)
            for (i in 0 until PW_LENGTH) {
                builder.append(CHARS[random.nextInt(CHARS.size - 1)])
            }
            return builder.toString()
        }

        @JvmStatic
        fun pronounceable(): String {
            val rand = Random.SECURE_RANDOM.nextInt(4)
            val output = CharArray(rand * 2 + (5 - rand))
            var vowel = Random.SECURE_RANDOM.nextBoolean()
            for (i in output.indices) {
                output[i] =
                    if (vowel) {
                        VOWELS[Random.SECURE_RANDOM.nextInt(VOWELS.size)]
                    } else {
                        CONSONANTS[Random.SECURE_RANDOM.nextInt(CONSONANTS.size)]
                    }
                vowel = !vowel
            }
            return String(output)
        }

        @JvmStatic
        fun hexToBytes(hexString: String): ByteArray {
            val len = hexString.length
            val array = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                array[i / 2] =
                    ((Character.digit(hexString[i], 16) shl 4) +
                            Character.digit(hexString[i + 1], 16))
                        .toByte()
                i += 2
            }
            return array
        }

        @JvmStatic
        fun hexToString(hexString: String): String = String(hexToBytes(hexString))

        @JvmStatic
        fun concatenateByteArrays(a: ByteArray, b: ByteArray): ByteArray {
            val result = ByteArray(a.size + b.size)
            System.arraycopy(a, 0, result, 0, a.size)
            System.arraycopy(b, 0, result, a.size, b.size)
            return result
        }

        /** Escapes usernames or passwords for SASL. */
        @JvmStatic
        fun saslEscape(s: String): String {
            val sb = StringBuilder((s.length * 1.1).toInt())
            for (i in 0 until s.length) {
                when (val c = s[i]) {
                    ',' -> sb.append("=2C")
                    '=' -> sb.append("=3D")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        @JvmStatic
        fun saslPrep(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

        @JvmStatic
        fun random(length: Int): String {
            val bytes = ByteArray(length)
            Random.SECURE_RANDOM.nextBytes(bytes)
            return Base64.encodeToString(
                bytes,
                Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE,
            )
        }

        @JvmStatic
        fun prettifyFingerprint(fingerprint: String?): String {
            if (fingerprint == null) {
                return ""
            } else if (fingerprint.length < 40) {
                return fingerprint
            }
            val builder = StringBuilder(fingerprint)
            var i = 8
            while (i < builder.length) {
                builder.insert(i, ' ')
                i += 9
            }
            return builder.toString()
        }

        @JvmStatic
        fun prettifyFingerprintCert(fingerprint: String): String {
            val builder = StringBuilder(fingerprint)
            var i = 2
            while (i < builder.length) {
                builder.insert(i, ':')
                i += 3
            }
            return builder.toString()
        }

        @JvmStatic
        @Throws(
            CertificateEncodingException::class,
            IllegalArgumentException::class,
            CertificateParsingException::class,
        )
        fun extractJidAndName(certificate: X509Certificate): Pair<Jid, String?>? {
            val alternativeNames: Collection<List<*>>? =
                certificate.subjectAlternativeNames
            val emails = ArrayList<String>()
            if (alternativeNames != null) {
                for (san in alternativeNames) {
                    val type = san[0] as Int
                    if (type == 1) {
                        emails.add(san[1] as String)
                    }
                }
            }
            val x500name: X500Name = JcaX509CertificateHolder(certificate).subject
            if (emails.size == 0 && x500name.getRDNs(BCStyle.EmailAddress).size > 0) {
                emails.add(
                    IETFUtils.valueToString(
                        x500name.getRDNs(BCStyle.EmailAddress)[0].getFirst().getValue(),
                    ),
                )
            }
            val name: String? =
                if (x500name.getRDNs(BCStyle.CN).size > 0) {
                    IETFUtils.valueToString(x500name.getRDNs(BCStyle.CN)[0].getFirst().getValue())
                } else {
                    null
                }
            if (emails.size >= 1) {
                return Pair(Jid.of(emails[0]), name)
            } else if (name != null) {
                try {
                    val jid = Jid.of(name)
                    if (jid.isBareJid() && jid.getLocal() != null) {
                        return Pair(jid, null)
                    }
                } catch (e: IllegalArgumentException) {
                    return null
                }
            }
            return null
        }

        @JvmStatic
        fun extractCertificateInformation(certificate: X509Certificate): Bundle {
            val information = Bundle()
            try {
                val holder = JcaX509CertificateHolder(certificate)
                val subject = holder.subject
                try {
                    information.putString(
                        "subject_cn",
                        subject.getRDNs(BCStyle.CN)[0].getFirst().getValue().toString(),
                    )
                } catch (e: Exception) {
                    // ignored
                }
                try {
                    information.putString(
                        "subject_o",
                        subject.getRDNs(BCStyle.O)[0].getFirst().getValue().toString(),
                    )
                } catch (e: Exception) {
                    // ignored
                }

                val issuer = holder.issuer
                try {
                    information.putString(
                        "issuer_cn",
                        issuer.getRDNs(BCStyle.CN)[0].getFirst().getValue().toString(),
                    )
                } catch (e: Exception) {
                    // ignored
                }
                try {
                    information.putString(
                        "issuer_o",
                        issuer.getRDNs(BCStyle.O)[0].getFirst().getValue().toString(),
                    )
                } catch (e: Exception) {
                    // ignored
                }
                try {
                    information.putString("sha1", getFingerprintCert(certificate.encoded))
                } catch (e: Exception) {
                    // ignored
                }
                return information
            } catch (e: CertificateEncodingException) {
                return information
            }
        }

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class)
        fun getFingerprintCert(input: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-1")
            val fingerprint = md.digest(input)
            return prettifyFingerprintCert(bytesToHex(fingerprint))
        }

        @JvmStatic
        fun getFingerprint(jid: Jid, androidId: String): String =
            getFingerprint(jid.toString() + "\u0000" + androidId)

        @JvmStatic
        fun getAccountFingerprint(account: AccountRef, androidId: String): String =
            getFingerprint(account.getJid().asBareJid(), androidId)

        @JvmStatic
        fun getFingerprint(value: String): String =
            try {
                val md = MessageDigest.getInstance("SHA-1")
                bytesToHex(md.digest(value.toByteArray(StandardCharsets.UTF_8)))
            } catch (e: Exception) {
                ""
            }

        @JvmStatic
        @StringRes
        fun encryptionTypeToText(encryption: Int): Int =
            when (encryption) {
                MessageRef.ENCRYPTION_OTR -> R.string.encryption_choice_otr
                MessageRef.ENCRYPTION_AXOLOTL,
                MessageRef.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE,
                MessageRef.ENCRYPTION_AXOLOTL_FAILED -> R.string.encryption_choice_omemo
                MessageRef.ENCRYPTION_PGP -> R.string.encryption_choice_pgp
                else -> R.string.encryption_choice_unencrypted
            }

        @JvmStatic
        fun isPgpEncryptedUrl(url: String?): Boolean {
            if (url == null) {
                return false
            }
            val u = url.lowercase()
            return !u.contains(" ") &&
                (u.startsWith("https://") ||
                    u.startsWith("http://") ||
                    u.startsWith("p1s3://")) &&
                u.endsWith(".pgp")
        }

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class)
        fun multihashAlgo(type: Multihash.Type): String =
            when (type) {
                Multihash.Type.sha1 -> "sha-1"
                Multihash.Type.sha2_256 -> "sha-256"
                Multihash.Type.sha2_512 -> "sha-512"
                else -> throw NoSuchAlgorithmException("$type")
            }

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class)
        fun multihashType(algo: String): Multihash.Type {
            if (algo == "SHA-1" || algo == "sha-1" || algo == "sha1") {
                return Multihash.Type.sha1
            } else if (algo == "SHA-256" || algo == "sha-256") {
                return Multihash.Type.sha2_256
            } else if (algo == "SHA-512" || algo == "sha-512") {
                return Multihash.Type.sha2_512
            } else {
                throw NoSuchAlgorithmException(algo)
            }
        }

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class)
        fun cid(digest: ByteArray, algo: String): Cid =
            Cid.buildCidV1(Cid.Codec.Raw, multihashType(algo), digest)

        @JvmStatic
        @Throws(NoSuchAlgorithmException::class, IOException::class)
        fun cid(`in`: InputStream, algo: Array<String>): Array<Cid> {
            val buf = ByteArray(4096)
            val md = Array(algo.size) { i -> MessageDigest.getInstance(algo[i]) }
            while (true) {
                val len = `in`.read(buf)
                if (len == -1) {
                    break
                }
                for (i in md.indices) {
                    md[i].update(buf, 0, len)
                }
            }
            return Array(md.size) { i -> cid(md[i].digest(), algo[i]) }
        }

        @JvmStatic
        fun isEqual(a: CharArray?, b: CharArray?): Boolean {
            if (a == null || b == null) {
                return a === b
            }
            if (a.size != b.size) {
                return false
            }
            var result = 0
            for (i in a.indices) {
                result = result or (a[i].code xor b[i].code)
            }
            return result == 0
        }
    }
}
