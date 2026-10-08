package uk.xa0.tulkki.parser

import gnu.inet.encoding.Punycode
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeSet
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Stanza
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element

/**
 * Tulkki: the parser base - timestamps, the participant splice and the error-string pair.
 *
 * Ported from `AbstractParser.java`.
 * It is an island file: the Java-visible surface survives member for member, because the Java
 * subclasses and the hub call static members through the class name.
 *
 * - The two protected fields stay **fields**: `mXmppConnectionService` and `account` are read
 *   directly by `PresenceParser`/`IqParser`/`MessageParser` (Java in this commit), so each is a
 *   protected `@JvmField val`. A plain `protected val` would emit only a getter and the Java
 *   subclasses would stop compiling.
 * - Every static method keeps its bridge through `@JvmStatic`, because `XmppConnectionService.java`
 *   writes `uk.xa0.tulkki.parser.AbstractParser.parseTimestamp(...)`, `SlotRequester.java` writes
 *   `IqParser.extractErrorMessage(...)` (inherited static), and `Story.kt`/`Comment.kt`/`Post.kt`
 *   call the `String` overload.
 * - **Nullability is read off the behaviour.** `parseTimestamp(Element, Long)` answers `Long?`: two
 *   of its three callers pass a `null` default and read the `null` back. `parseTimestamp(Element)`
 *   answers a primitive `long` and keeps that: its only nullable path is the default it was handed,
 *   so the Kotlin form re-uses that same value instead of an `!!`. The `String` overloads take a
 *   nullable parameter because the Java explicitly tests for `null` and throws its own exception -
 *   `parseTimestamp` a `ParseException`, `getTimestamp` an `IllegalArgumentException`.
 * - `avatarData`/`extractErrorMessage`/`errorMessage` answer `String?` as the Java did; `parseItem`
 *   answers a non-null `MucOptionsRef.UserRef`.
 * - Java's in-place reassignment of the `fullJid` parameter becomes a local `var` with the parameter
 *   read once; the order of the `nick` reads is preserved exactly.
 */
abstract class AbstractParser protected constructor(
    service: XmppConnectionService,
    account: AccountRef,
) {
    @JvmField
    protected val mXmppConnectionService: XmppConnectionService = service

    @JvmField
    protected val account: AccountRef = account

    companion object {

        @JvmStatic
        fun parseTimestamp(element: Element, d: Long?): Long? = parseTimestamp(element, d, false)

        @JvmStatic
        fun parseTimestamp(element: Element, d: Long?, ignoreCsiAndSm: Boolean): Long? {
            var min = Long.MAX_VALUE
            var returnDefault = true
            val to: Jid? =
                if (ignoreCsiAndSm && element is Stanza) {
                    element.getTo()
                } else {
                    null
                }
            for (child in element.getChildren()) {
                if ("delay" == child.getName() && "urn:xmpp:delay" == child.getNamespace()) {
                    val f: Jid? =
                        if (to == null) {
                            null
                        } else {
                            Jid.Invalid.getNullForInvalid(child.getAttributeAsJid("from"))
                        }
                    // The Java's `f != null` already implies `to != null` (the null `to` branch is
                    // what produced a null `f`); stating it keeps the smart cast and the behaviour.
                    if (f != null && to != null && (to.asBareJid() == f || to.getDomain() == f)) {
                        continue
                    }
                    val stamp = child.getAttribute("stamp")
                    if (stamp != null) {
                        try {
                            min = Math.min(min, parseTimestamp(stamp))
                            returnDefault = false
                        } catch (t: Throwable) {
                            // ignore
                        }
                    }
                }
            }
            return if (returnDefault) d else min
        }

        @JvmStatic
        fun parseTimestamp(element: Element): Long {
            val now = System.currentTimeMillis()
            // The Java returned the boxed `Long` unboxed; its only null path is `return d`, and `d`
            // here is this very `now`, so the fallback is the same value, not a new policy.
            return parseTimestamp(element, now) ?: now
        }

        // Formats tried in order. Formats without an explicit timezone token (Z/z) are parsed
        // as UTC to avoid device-locale-dependent results.
        private val TIMESTAMP_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ssZ", // ISO 8601 / RFC 3339 / XEP-0082  e.g. 2024-01-15T10:30:00+0530
            "yyyy-MM-dd'T'HH:mm:ss", // ISO 8601 without timezone        e.g. 2024-01-15T10:30:00
            "EEE, dd MMM yyyy HH:mm:ss Z", // RFC 2822 with numeric offset      e.g. Mon, 15 Jan 2024 10:30:00 +0000
            "EEE, dd MMM yyyy HH:mm:ss z", // RFC 2822 with named timezone      e.g. Mon, 15 Jan 2024 10:30:00 GMT
            "dd MMM yyyy HH:mm:ss Z", // RFC 2822 without weekday          e.g. 15 Jan 2024 10:30:00 +0000
            "dd MMM yyyy HH:mm:ss z", // RFC 2822 without weekday, named   e.g. 15 Jan 2024 10:30:00 GMT
            "yyyy-MM-dd HH:mm:ssZ", // SQL-style with offset             e.g. 2024-01-15 10:30:00+0000
            "yyyy-MM-dd HH:mm:ss", // SQL-style without timezone        e.g. 2024-01-15 10:30:00
            "yyyy-MM-dd", // date only                         e.g. 2024-01-15
        )

        /**
         * Parses a timestamp string in any of the commonly used formats (ISO 8601 / RFC 3339,
         * RFC 2822, XEP-0082, SQL-style, date-only). Handles arbitrary-precision fractional
         * seconds and all numeric timezone offset forms (+HH:MM, +HHMM, Z).
         */
        @JvmStatic
        @Throws(ParseException::class)
        fun parseTimestamp(raw: String?): Long {
            if (raw == null) {
                throw ParseException("null timestamp", 0)
            }
            val normalized = normalizeTimezoneOffset(raw.trim())

            // ISO 8601 allows arbitrary-precision fractional seconds; SimpleDateFormat only handles
            // milliseconds. Strip the fraction, convert it to ms, and re-add it after parsing.
            var extraMillis = 0L
            var forParsing = normalized
            if (normalized.length > 19 && normalized[19] == '.') {
                val tzStart = findTimezoneOffset(normalized)
                val fraction = normalized.substring(19, tzStart)
                try {
                    extraMillis = Math.round(("0" + fraction).toDouble() * 1000)
                } catch (ignored: NumberFormatException) {
                }
                forParsing = normalized.substring(0, 19) + normalized.substring(tzStart)
            }

            for (format in TIMESTAMP_FORMATS) {
                try {
                    val sdf = SimpleDateFormat(format, Locale.US)
                    sdf.isLenient = false
                    // Formats without an explicit timezone token default to UTC.
                    if (!format.endsWith("Z") && !format.endsWith("z")) {
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = sdf.parse(forParsing)
                    if (date != null) {
                        return date.time + extraMillis
                    }
                } catch (ignored: ParseException) {
                }
            }
            throw ParseException("Unparseable timestamp: " + raw, 0)
        }

        /**
         * Normalises the timezone suffix of a timestamp string:
         *  Z           -> +0000
         *  +HH:MM      -> +HHMM  (RFC 3339 colon form, any offset)
         *  -HH:MM      -> -HHMM
         * All other forms are returned unchanged.
         */
        private fun normalizeTimezoneOffset(timestamp: String): String {
            if (timestamp.endsWith("Z")) {
                return timestamp.substring(0, timestamp.length - 1) + "+0000"
            }
            val len = timestamp.length
            if (len >= 6) {
                val sign = timestamp[len - 6]
                val colon = timestamp[len - 3]
                if ((sign == '+' || sign == '-') && colon == ':') {
                    return timestamp.substring(0, len - 3) + timestamp.substring(len - 2)
                }
            }
            return timestamp
        }

        /**
         * Returns the start index of a ±HHMM timezone suffix in a normalized timestamp,
         * or the string length if no such suffix is present.
         */
        private fun findTimezoneOffset(normalized: String): Int {
            val len = normalized.length
            if (len >= 5) {
                val sign = normalized[len - 5]
                if (sign == '+' || sign == '-') {
                    return len - 5
                }
            }
            return len
        }

        @JvmStatic
        @Throws(ParseException::class)
        fun getTimestamp(input: String?): Long {
            if (input == null) {
                throw IllegalArgumentException("timestamp should not be null")
            }
            return parseTimestamp(input)
        }

        @JvmStatic
        fun avatarData(items: Element): String? {
            val item = items.findChild("item") ?: return null
            return item.findChildContent("data", "urn:xmpp:avatar:data")
        }

        @JvmStatic
        fun parseItem(conference: ConversationRef, item: Element): MucOptionsRef.UserRef =
            parseItem(conference, item, null, null, null, Element("hats", "urn:xmpp:hats:0"))

        @JvmStatic
        fun parseItem(
            conference: ConversationRef,
            item: Element,
            fullJidIn: Jid?,
            occupantId: Element?,
            nicknameIn: String?,
            hatsEl: Element?,
        ): MucOptionsRef.UserRef {
            var fullJid = fullJidIn
            val conferenceJid =
                conference.getJid() ?: throw NullPointerException("conference has no jid")
            val local = conferenceJid.getLocal()
            val domain = conferenceJid.getDomain().toString()
            val affiliation = item.getAttribute("affiliation")
            val role = item.getAttribute("role")
            var nick = item.getAttribute("nick")
            if (nick != null && fullJid == null) {
                try {
                    fullJid = Jid.of(local, domain, nick)
                } catch (e: IllegalArgumentException) {
                    fullJid = null
                }
            }
            val realJid = item.getAttributeAsJid("jid")
            if (fullJid != null) {
                nick = fullJid.getResource()
            }
            var nickname: String? = null
            if (nick != null && nicknameIn != null) {
                nickname = if (nick == nicknameIn) nick else null
            }
            try {
                if (nickname == null &&
                    nicknameIn != null &&
                    nick != null &&
                    Punycode.decode(nick) == nicknameIn
                ) {
                    nickname = nicknameIn
                }
            } catch (e: Exception) {
            }
            val hats: MutableSet<MucOptionsRef.HatRef> =
                TreeSet(XmppConnectionService.dataStatics().hatOrder())
            if (hatsEl != null) {
                for (hat in hatsEl.getChildren()) {
                    if ("hat" == hat.getName() &&
                        ("urn:xmpp:hats:0" == hat.getNamespace() ||
                            "xmpp:prosody.im/protocol/hats:1" == hat.getNamespace())
                    ) {
                        hats.add(XmppConnectionService.dataStatics().newHat(hat))
                    }
                }
            }
            val user =
                XmppConnectionService.dataStatics()
                    .newUser(
                        conference.getMucOptions(),
                        fullJid,
                        occupantId?.getAttribute("id"),
                        nickname,
                        if (hatsEl == null) null else hats,
                    )
            if (Jid.Invalid.isValid(realJid)) {
                user.setRealJid(realJid)
            }
            user.setAffiliation(affiliation)
            user.setRole(role)
            return user
        }

        @JvmStatic
        fun extractErrorMessage(packet: Element): String? {
            val error = packet.findChild("error")
            if (error != null && error.getChildren().isNotEmpty()) {
                val errorNames = orderedElementNames(error.getChildren())
                val text = error.findChildContent("text")
                if (text != null && text.trim().isNotEmpty()) {
                    return prefixError(errorNames) + text
                } else if (errorNames.isNotEmpty()) {
                    return prefixError(errorNames) + errorNames[0].replace("-", " ")
                }
            }
            return null
        }

        @JvmStatic
        fun errorMessage(packet: Element): String? {
            val error = packet.findChild("error")
            if (error != null && error.getChildren().isNotEmpty()) {
                val errorNames = orderedElementNames(error.getChildren())
                val text = error.findChildContent("text")
                if (text != null && text.trim().isNotEmpty()) {
                    return text
                } else if (errorNames.isNotEmpty()) {
                    return errorNames[0].replace("-", " ")
                }
            }
            return null
        }

        private fun prefixError(errorNames: List<String>): String {
            if (errorNames.isNotEmpty()) {
                return errorNames[0] + '\u001f'
            }
            return ""
        }

        private fun orderedElementNames(children: List<Element>): List<String> {
            val names = ArrayList<String>()
            for (child in children) {
                val name = child.getName()
                if (name != "text") {
                    if ("urn:ietf:params:xml:ns:xmpp-stanzas" == child.getNamespace()) {
                        names.add(name)
                    } else {
                        names.add(0, name)
                    }
                }
            }
            return names
        }
    }

    protected fun updateLastseen(account: AccountRef, from: Jid) {
        val contact = account.getRoster().getContact(from)
        contact.setLastResource(if (from.isBareJid()) "" else from.getResource())
    }
}
