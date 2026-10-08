package uk.xa0.tulkki.app

import java.lang.reflect.Method
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.xmpp.utils.XmppUri

/**
 * Tulkki: a scanned code or a web link must not take the parser down, and what it carries must be
 * a fingerprint - the hardening half of upstream's `XmppUri` work (port-11's group F: commits
 * `739765f8d5`, `6f83bfb248`, `3b1d2393f4`).
 *
 * Not reachable through the public API off-device: `XmppUri`'s constructors take an
 * `android.net.Uri` and call `Uri.parse`, an Android stub on the JVM, so the cells drive the two
 * pure methods the parsing is built from. Both are non-public - `parseParameters` is private and
 * `parseFingerprints` is package-private with upstream's own `@VisibleForTesting` - so the calls go
 * through reflection rather than widening production visibility.
 *
 * What only a device can settle: that a real `xmpp:`/`https:` URI, scanned or tapped, reaches
 * these methods with the query string they expect (the `Uri.parse` step and the two call sites in
 * the constructor's `parse`). Nothing here proves that; it proves the rules those call sites rely on.
 */
class XmppUriHardeningTest {

    @Test
    fun aRepeatedQueryParameterKeepsTheFirstValue() {
        val parameters = parseParameters("omemo-sid-1=AA&omemo-sid-1=BB&join", '&')

        Assert.assertEquals(
            "the duplicate collapses to one entry, and `join` is the other",
            2,
            parameters.size)
        Assert.assertEquals(
            "the first occurrence wins, exactly as it arrived (`parseFingerprints` is what " +
                "lower-cases a value)",
            "AA",
            parameters["omemo-sid-1"])
        Assert.assertTrue("and the rest of the query survives", parameters.containsKey("join"))
    }

    @Test
    fun onlyATrueFingerprintIsAccepted() {
        Assert.assertEquals(
            "arbitrary text is not a fingerprint",
            0,
            parseFingerprints(mapOf("omemo-sid-1" to "not-a-fingerprint")).size)
        Assert.assertEquals(
            "63 hex characters is not the 64 a Curve25519 key without its type byte has",
            0,
            parseFingerprints(mapOf("omemo-sid-1" to "a".repeat(63))).size)
        Assert.assertEquals(
            "non-hex characters are refused even at the right length",
            0,
            parseFingerprints(mapOf("omemo-sid-1" to "z".repeat(64))).size)
        Assert.assertEquals(
            "exactly 64 hex characters is a fingerprint",
            1,
            parseFingerprints(mapOf("omemo-sid-1" to "a".repeat(64))).size)
    }

    @Test
    fun oneUriMayCarryOnlyABoundedNumberOfFingerprints() {
        val parameters = LinkedHashMap<String, String>()
        val keys = ArrayList<String>()
        for (i in 0 until 65) {
            keys.add("omemo-sid-$i")
        }
        for (key in keys) {
            parameters[key] = "a".repeat(64)
        }

        Assert.assertEquals(
            "a scanned code cannot ask for unbounded identity rows",
            64,
            parseFingerprints(parameters).size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseParameters(query: String, separator: Char): Map<String, String> {
        val method: Method =
            XmppUri::class.java.getDeclaredMethod(
                "parseParameters", String::class.java, Character.TYPE)
        method.isAccessible = true
        return method.invoke(null, query, separator) as Map<String, String>
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFingerprints(parameters: Map<String, String>): List<*> {
        val method: Method =
            XmppUri::class.java.getDeclaredMethod("parseFingerprints", Map::class.java)
        method.isAccessible = true
        return method.invoke(null, parameters) as List<*>
    }
}
