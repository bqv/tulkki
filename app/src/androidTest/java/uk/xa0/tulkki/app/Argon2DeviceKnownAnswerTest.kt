package uk.xa0.tulkki.app

import java.nio.charset.StandardCharsets
import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.signal.argon2.Argon2
import org.signal.argon2.Argon2Exception
import org.signal.argon2.MemoryCost
import org.signal.argon2.Type
import org.signal.argon2.Version

/**
 * The device half of port-16's argon2 gate: the <strong>published AAR's own binaries</strong> must
 * produce the same bytes as the source they vendor.
 *
 * <p>Why this is not a JVM test: {@code argon2kt:1.6.0} ships an AAR only - {@code classes.jar} plus
 * four bionic {@code .so} files - so the suite cannot execute it, and the source-level comparison
 * ({@code tools/argon2-vectors --against}, all seven tags and all seven encoded strings identical at
 * the app's parameters) stands in for it until this runs. This is the last gate, and it belongs to
 * whoever holds the device.
 *
 * <p>The expectations are the capture's: {@code crypto/src/test/resources/argon2/known-answer.txt},
 * taken from the in-tree C before anything moved, with the freshness of its parameters guarded on the
 * JVM by {@code Argon2BaselineFreshnessTest} and the call's configuration pinned by
 * {@code org.signal.argon2.Argon2KnownAnswerTest}. They are restated here because an instrumented test
 * cannot read another source set's resources; the tag in the assertion is the authority.
 *
 * <p><strong>How to run it (one launch, no app surface):</strong>
 * <pre>
 *   tools/build assembleTulkkiDebugAndroidTest
 *   adb install -r -t app/build/outputs/apk/androidTest/tulkki/debug/app-tulkki-debug-androidTest.apk
 *   adb shell am instrument -w -e class uk.xa0.tulkki.app.Argon2DeviceKnownAnswerTest \
 *       uk.xa0.tulkki.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 * The instrumentation package is the app's {@code applicationId} plus {@code .test}
 * ({@code uk.xa0.tulkki.test}); the runner is the one {@code app/build.gradle} already declares.
 * The parameters are the app's and must stay explicit: <strong>Argon2id, V13, t=3, m=65536 KiB,
 * p=4</strong> - argon2kt's default parallelism is 2, which would change the key silently.
 */
class Argon2DeviceKnownAnswerTest {

    private companion object {
        const val T = 3
        const val M_KIB = 65536
        const val P = 4
        const val OUT = 32

        /** S1 = 00..1f, S2 = 20..3f, S3 = a0..bf, S4/S5 = ASCII, exactly as the capture's driver built them. */
        fun salt1(): ByteArray {
            val s = ByteArray(32)
            for (i in 0 until 32) {
                s[i] = i.toByte()
            }
            return s
        }

        fun salt2(): ByteArray {
            val s = ByteArray(32)
            for (i in 0 until 32) {
                s[i] = (0x20 + i).toByte()
            }
            return s
        }

        fun salt3(): ByteArray {
            val s = ByteArray(32)
            for (i in 0 until 32) {
                s[i] = (0xA0 + i).toByte()
            }
            return s
        }

        fun app(): Argon2 =
            Argon2.Builder(Version.V13)
                .type(Type.Argon2id)
                .memoryCost(MemoryCost.MiB(M_KIB / 1024))
                .parallelism(P)
                .iterations(T)
                .hashLength(OUT)
                .build()

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                out.append(Character.forDigit((b.toInt() shr 4) and 0xf, 16))
                out.append(Character.forDigit(b.toInt() and 0xf, 16))
            }
            return out.toString()
        }
    }

    private fun check(
        label: String,
        password: ByteArray,
        salt: ByteArray,
        expectedTag: String,
        expectedEncoded: String,
    ) {
        val result = app().hash(password, salt)
        assertEquals(label + ": the tag", expectedTag, hex(result.hash))
        assertEquals(label + ": the encoded string", expectedEncoded, result.encoded)
        assertEquals(
            label + ": and it verifies back",
            true,
            Argon2.verify(result.encoded, password, Type.Argon2id))
    }

    @Test
    fun everyCapturedVectorIsReproducedOnDevice() {
        check(
            "V1",
            ByteArray(0),
            salt1(),
            "7d8aeb76f74eaba0f5b1708d66319b2bc5c12bc262df52536dd0ce62f93bc7b6",
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\$" +
                "fYrrdvdOq6D1sXCNZjGbK8XBK8Ji31JTbdDOYvk7x7Y")
        check(
            "V2",
            "0123456789abcdef0123456789abcdef".toByteArray(StandardCharsets.US_ASCII),
            salt1(),
            "d78a45e67056b11d02ea311b88ae6d4ffcee8fdb83f9ec4d373a3de4fdb317c6",
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\$" +
                "14pF5nBWsR0C6jEbiK5tT/zuj9uD+exNNzo95P2zF8Y")
        check(
            "V3",
            "salasana-\u00e4\u00f6\u00e5-\u65e5\u672c\u8a9e-\uD83D\uDD10".toByteArray(StandardCharsets.UTF_8),
            salt2(),
            "741077296d572b777e230edfded0ec8ef0ac3257c484aeafb23ace7ccaeea5f4",
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8\$" +
                "dBB3KW1XK3d+Iw7f3tDsjvCsMlfEhK6vsjrOfMrupfQ")
        check(
            "V4",
            "correct horse battery staple".toByteArray(StandardCharsets.US_ASCII),
            "0123456789abcdef0123456789abcdef".toByteArray(StandardCharsets.US_ASCII),
            "b7d5f94a21635fd43604b240e4548b011d05768a9da8636498071ef4e3ee08d4",
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY\$" +
                "t9X5SiFjX9Q2BLJA5FSLAR0FdoqdqGNkmAce9OPuCNQ")
        check(
            "V5",
            "salasana".toByteArray(StandardCharsets.US_ASCII),
            "12345678".toByteArray(StandardCharsets.US_ASCII),
            "4115e2445f85891e35818b383040a25828cafa4012fb6fc7440329ba5ff1b852",
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$MTIzNDU2Nzg\$" +
                "QRXiRF+FiR41gYs4MECiWCjK+kAS+2/HRAMpul/xuFI")
        check(
            "V6",
            "tulkki".toByteArray(StandardCharsets.US_ASCII),
            Arrays.copyOf(salt3(), 16),
            "5f60a9530e98ccc13143eb88f7d62d18330020ad2628876f1a244aae49f01f81",
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$oKGio6SlpqeoqaqrrK2urw\$" +
                "X2CpUw6YzMExQ+uI99YtGDMAIK0mKIdvGiRKrknwH4E")
        check(
            "V7",
            byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()),
            salt1(),
            "e42755cb4556eddab3d02e25161ec9dcc6d4b9b3dd752ee9c43d20328c5e61bd",
            "\$argon2id\$v=19\$m=65536,t=3,p=4\$AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\$" +
                "5CdVy0VW7dqz0C4lFh7J3MbUubPddS7pxD0gMoxeYb0")
    }

    /** N1: a 7-byte salt is refused, not hashed. The buffer property is proven on the JVM seam. */
    @Test
    fun aSevenByteSaltIsRefusedOnDeviceToo() {
        val sevenByteSalt = "1234567".toByteArray(StandardCharsets.US_ASCII)
        try {
            app().hash("salasana".toByteArray(StandardCharsets.US_ASCII), sevenByteSalt)
            fail("a 7-byte salt must not produce a tag")
        } catch (e: Argon2Exception) {
            // The exception carries the C's numeric code in its message (`Argon failed -6: ...`),
            // which is the whole of what the app's public API reports for a refusal.
            assertTrue(
                "the C's ARGON2_SALT_TOO_SHORT, named in the message: " + e.message,
                e.message!!.contains("Argon failed -6"))
        }
    }

    /** The wrong password must not verify, whatever the AAR does internally. */
    @Test
    fun aWrongPasswordDoesNotVerify() {
        val salt = salt1()
        val password = "salasana".toByteArray(StandardCharsets.US_ASCII)
        val result = app().hash(password, salt)
        assertEquals(
            false,
            Argon2.verify(
                result.encoded,
                "not the password".toByteArray(StandardCharsets.UTF_8),
                Type.Argon2id))
    }
}
