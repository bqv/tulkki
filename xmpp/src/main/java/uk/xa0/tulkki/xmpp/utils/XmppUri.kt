package uk.xa0.tulkki.xmpp.utils

import android.net.Uri
import androidx.annotation.NonNull
import androidx.annotation.VisibleForTesting
import com.google.common.base.CharMatcher
import com.google.common.collect.Collections2
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern
import uk.xa0.tulkki.libs.Jid

/**
 * A scanned or tapped XMPP URI, parsed into a JID, its action parameters and its OMEMO/OTR
 * fingerprints.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`uri` and `jid` stay `@JvmField protected` fields.** `:ui`'s `StartConversationActivity.Invite`
 *    is a Java subclass of this class, so the fields must stay Java-readable; `jid` *cannot* be a
 *    Kotlin property at all, because it would generate `getJid(): String` and collide with the
 *    existing `getJid(): Jid`.
 * 2. **`getJid`, `isValidJid`, `getBody`, `getName`, `getParameter`, `getFingerprints` stay
 *    functions**, not properties: their callers (Java in `:ui` and Kotlin elsewhere) spell them as
 *    calls, and `getJid`'s name is already taken by the field above. The two private fields they read
 *    are `private`, so Kotlin generates no accessors and no clash exists.
 * 3. **`parseParameters` and `parseFingerprints` are `@JvmStatic`** because
 *    `:app`'s `XmppUriHardeningTest` fetches both with `XmppUri.class.getDeclaredMethod(...)` and
 *    invokes them with a null receiver: the methods have to be declared static **on `XmppUri`**, not
 *    only on its `Companion`.
 * 4. **All four `split` calls are `Pattern.compile(...).split(input, limit)`.** Java's `String.split`
 *    is a *regex* split with trailing empties dropped; Kotlin's single-`String` overload is a literal
 *    split and its `Regex.split` keeps trailing empties.
 * 5. **`toLowerCase(Locale.US)` is `lowercase(Locale.US)`** and `Integer.parseInt` is `toInt()`,
 *    which throws the same `NumberFormatException`.
 * 6. **`Fingerprint.deviceId` is `internal`.** Java declared it package-private and only
 *    [getFingerprintUri], in this class, reads it - Kotlin, unlike Java, will not let the outer class
 *    read a nested class's `private` member.
 * 7. **The `switch`-free `if`/`else` chain of [parseFingerprints] keeps its `continue`s**, so the
 *    iteration and the `MAX_FINGERPRINTS` cap behave as Java's did.
 * 8. **The class is `open`**, because `:ui`'s `StartConversationActivity.Invite` really does extend
 *    it. Kotlin classes are final by default, and the full `assembleTulkkiDebug` - not the island's
 *    own javac - is what found the `cannot inherit from final XmppUri` this conversion first
 *    introduced; the fix is this one word.
 */
open class XmppUri {

    @JvmField protected var uri: Uri? = null

    @JvmField protected var jid: String? = null

    private var fingerprints: List<Fingerprint> = ArrayList()

    private var parameters: Map<String, String> = emptyMap()

    private var safeSource: Boolean = true

    constructor(uri: String) {
        try {
            parse(Uri.parse(uri))
        } catch (e: IllegalArgumentException) {
            jid =
                try {
                    Jid.of(uri).asBareJid().toString()
                } catch (e2: IllegalArgumentException) {
                    null
                }
        }
    }

    constructor(uri: Uri) {
        parse(uri)
    }

    constructor(uri: Uri, safeSource: Boolean) {
        this.safeSource = safeSource
        parse(uri)
    }

    protected fun parse(uri: Uri?) {
        if (uri == null) {
            return
        }
        this.uri = uri
        val scheme = uri.scheme
        val host = uri.host
        val segments = uri.pathSegments
        if ("https".equals(scheme, ignoreCase = true) && INVITE_DOMAIN.equals(host, ignoreCase = true)) {
            if (segments.size >= 2 && segments[1].contains("@")) {
                // sample : https://conversations.im/i/foo@bar.com
                jid =
                    try {
                        Jid.of(lameUrlDecode(segments[1])).toString()
                    } catch (e: Exception) {
                        null
                    }
            } else if (segments.size >= 3) {
                // sample : https://conversations.im/i/foo/bar.com
                jid = segments[1] + "@" + segments[2]
            }
            if (segments.size > 1 && "j".equals(segments[0], ignoreCase = true)) {
                this.parameters = ImmutableMap.of(ACTION_JOIN, "")
            }
            val queryParameters = parseParameters(uri.query, '&')
            this.fingerprints = parseFingerprints(queryParameters)
        } else if ("xmpp".equals(scheme, ignoreCase = true)) {
            // sample: xmpp:foo@bar.com
            this.parameters = parseParameters(uri.query, ';')
            if (uri.authority != null) {
                jid = uri.authority
            } else {
                val parts = Pattern.compile("\\?").split(uri.schemeSpecificPart, 0)
                if (parts.size > 0) {
                    jid = parts[0]
                } else {
                    return
                }
            }
            this.fingerprints = parseFingerprints(parameters)
        } else if ("imto".equals(scheme, ignoreCase = true) &&
            Arrays.asList("xmpp", "jabber").contains(uri.host)
        ) {
            // sample: imto://xmpp/foo@bar.com
            try {
                jid = Pattern.compile("/").split(URLDecoder.decode(uri.encodedPath, "UTF-8"), 0)[1].trim()
            } catch (ignored: UnsupportedEncodingException) {
                jid = null
            }
        } else {
            jid = null
        }
    }

    @NonNull
    override fun toString(): String = if (uri != null) uri.toString() else ""

    fun isSafeSource(): Boolean = safeSource

    fun isAction(action: String): Boolean =
        Collections2.transform(parameters.keys) { s ->
                CharMatcher.inRange('a', 'z').or(CharMatcher.inRange('A', 'Z')).retainFrom(s)
            }
            .contains(action)

    fun getJid(): Jid? {
        try {
            val current = this.jid
            return if (current == null) null else Jid.ofUserInput(current)
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    fun isValidJid(): Boolean {
        val current = jid ?: return false
        try {
            Jid.ofUserInput(current)
            return true
        } catch (e: IllegalArgumentException) {
            return false
        }
    }

    fun getBody(): String? = parameters["body"]

    fun getName(): String? = parameters["name"]

    fun getParameter(key: String): String? = parameters[key]

    fun parameterString(): String {
        val s = StringBuilder()
        for (param in parameters.entries) {
            if (param.value == null || param.value.isEmpty()) continue

            s.append(";")
            s.append(param.key)
            s.append("=")
            s.append(param.value)
        }
        return s.toString()
    }

    fun displayParameterString(): String {
        val s = StringBuilder()
        for (param in parameters.entries) {
            if (param.value == null || param.value.isEmpty()) continue
            if (param.key.startsWith(OMEMO_URI_PARAM)) continue

            s.append(";")
            s.append(param.key)
            s.append("=")
            s.append(param.value)
        }
        return s.toString()
    }

    fun getFingerprints(): List<Fingerprint> = this.fingerprints

    fun hasFingerprints(): Boolean = fingerprints.isNotEmpty()

    enum class FingerprintType {
        OMEMO,
        OTR,
    }

    class Fingerprint {
        @JvmField val type: FingerprintType

        @JvmField val fingerprint: String

        internal val deviceId: Int

        constructor(type: FingerprintType, fingerprint: String) : this(type, fingerprint, 0)

        constructor(type: FingerprintType, fingerprint: String, deviceId: Int) {
            this.type = type
            this.fingerprint = fingerprint
            this.deviceId = deviceId
        }

        @NonNull
        override fun toString(): String =
            type.toString() + ": " + fingerprint + (if (deviceId != 0) " $deviceId" else "")
    }

    companion object {

        const val ACTION_JOIN: String = "join"
        const val ACTION_MESSAGE: String = "message"
        const val ACTION_REGISTER: String = "register"
        const val ACTION_ROSTER: String = "roster"
        const val PARAMETER_PRE_AUTH: String = "preauth"
        const val PARAMETER_IBR: String = "ibr"
        private const val OMEMO_URI_PARAM: String = "omemo-sid-"
        private const val OTR_URI_PARAM: String = "otr-fingerprint"

        const val INVITE_DOMAIN: String = "monocles.chat"

        /**
         * Tulkki: port-11, upstream `6f83bfb248` - an OMEMO identity fingerprint as it travels in a URI:
         * the hex of the Curve25519 public key WITHOUT the leading {@code 05} type byte, i.e. exactly 64
         * hex characters. Callers put the {@code 05} back before storing it.
         */
        private const val FINGERPRINT_HEX_LENGTH: Int = 64

        /**
         * Tulkki: port-11, upstream `739765f8d5` - upper bound on how many fingerprints one URI may
         * carry. A legitimate code holds this device's key per stack plus one per verified other device
         * of the same account - a couple of dozen at the very outside. Without a cap a single scanned
         * code could ask us to write hundreds of rows into the identities table.
         */
        private const val MAX_FINGERPRINTS: Int = 64

        @JvmStatic
        @VisibleForTesting
        fun parseParameters(query: String?, separator: Char): Map<String, String> {
            // Tulkki: port-11, upstream `739765f8d5` - keeps the first occurrence of a repeated key
            // instead of throwing the way ImmutableMap.Builder does: the input is a scanned code or a
            // link from a web page, and a duplicate parameter must not take down whatever is parsing
            // it. First wins so a later copy cannot override an earlier value.
            val parameters = LinkedHashMap<String, String>()
            val pairs =
                if (query == null) {
                    arrayOf<String>()
                } else {
                    Pattern.compile(separator.toString()).split(query, 0)
                }
            for (pair in pairs) {
                val parts = Pattern.compile("=").split(pair, 2)
                if (parts.isEmpty()) {
                    continue
                }
                val key = parts[0].lowercase(Locale.US)
                val value: String
                if (parts.size == 2) {
                    value =
                        try {
                            URLDecoder.decode(parts[1], "UTF-8")
                        } catch (e: UnsupportedEncodingException) {
                            ""
                        }
                } else {
                    value = ""
                }
                if (!parameters.containsKey(key)) {
                    parameters[key] = value
                }
            }
            return ImmutableMap.copyOf(parameters)
        }

        /**
         * Whether {@code value} can actually be an OMEMO fingerprint.
         *
         * <p>Nothing checked this before, so a scanned code could carry arbitrary text and it was stored
         * verbatim as a VERIFIED identity row - junk that never matches a real key, but which pollutes
         * the trust table and makes the app report a successful verification. Hex is checked explicitly
         * rather than with a regex so the accepted alphabet is obvious; the value has already been
         * lower-cased by the caller.
         */
        private fun isValidFingerprint(value: String?): Boolean {
            if (value == null || value.length != FINGERPRINT_HEX_LENGTH) {
                return false
            }
            for (i in 0 until value.length) {
                val c = value[i]
                if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        @VisibleForTesting
        fun parseFingerprints(parameters: Map<String, String>): List<Fingerprint> {
            val builder = ImmutableList.Builder<Fingerprint>()
            var count = 0
            for (parameter in parameters.entries) {
                val key = parameter.key
                val value = parameter.value.lowercase(Locale.US)
                val type: FingerprintType
                val id: Int
                if (key.startsWith(OMEMO_URI_PARAM)) {
                    type = FingerprintType.OMEMO
                    id = parseDeviceId(key.substring(OMEMO_URI_PARAM.length))
                } else if ("omemo" == key) {
                    type = FingerprintType.OMEMO
                    id = 0
                } else {
                    continue
                }
                if (id < 0 || !isValidFingerprint(value)) {
                    // invalid device id or not a fingerprint at all
                    continue
                }
                if (++count > MAX_FINGERPRINTS) {
                    break
                }
                builder.add(Fingerprint(type, value, id))
            }
            return builder.build()
        }

        /** The device id of an {@code omemo-sid-<id>} parameter, or -1 when it is not one. */
        private fun parseDeviceId(suffix: String): Int =
            try {
                val id = suffix.toInt()
                if (id < 0) -1 else id
            } catch (e: NumberFormatException) {
                -1
            }

        @JvmStatic
        fun getFingerprintUri(
            base: String,
            fingerprints: List<Fingerprint>,
            separator: Char,
        ): String {
            val builder = StringBuilder(base)
            builder.append('?')
            for (i in fingerprints.indices) {
                val type = fingerprints[i].type
                if (type == FingerprintType.OMEMO) {
                    builder.append(OMEMO_URI_PARAM)
                    builder.append(fingerprints[i].deviceId)
                } else if (type == FingerprintType.OTR) {
                    builder.append(OTR_URI_PARAM)
                }
                builder.append('=')
                builder.append(fingerprints[i].fingerprint)
                if (i != fingerprints.size - 1) {
                    builder.append(separator)
                }
            }
            return builder.toString()
        }

        private fun lameUrlDecode(url: String): String =
            url.replace("%23", "#").replace("%25", "%")

        @JvmStatic
        fun lameUrlEncode(url: String): String =
            url.replace("%", "%25").replace("#", "%23")
    }
}
