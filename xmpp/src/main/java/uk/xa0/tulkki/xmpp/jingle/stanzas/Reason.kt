package uk.xa0.tulkki.xmpp.jingle.stanzas

import com.google.common.base.CaseFormat
import com.google.common.base.Throwables
import uk.xa0.tulkki.xmpp.crypto.OmemoFailure
import uk.xa0.tulkki.xmpp.jingle.AbstractContentMap

/**
 * The Jingle `reason` vocabulary (XEP-0166 §7.3).
 *
 * Ported from Java by the port-14 `xmppport` lane. Decisions taken rather than inherited:
 *
 * 1. **`toString()` reads `name`, not `super.toString()`.** Java's `super.toString()` on an enum is
 *    `Enum.toString()`, which returns the constant's name; Kotlin's `name` is the same value, and
 *    `super.toString()` in an enum's own `toString()` is not what the compiler can generate.
 * 2. **The two exception branches name `AbstractContentMap`, not `RtpContentMap`.** Java resolves
 *    `RtpContentMap.UnsupportedTransportException` through the superclass's nested type; Kotlin does
 *    not inherit nested classifiers, so the declaring class is named outright. The two `is` tests
 *    and the answers they take are unchanged.
 * 3. **`of` and `ofThrowable` are `@JvmStatic` on the companion**: Java callers exist throughout
 *    `xmpp/jingle/` as static imports of `Reason.of(...)` and `Reason.ofThrowable(...)`.
 */
enum class Reason {
    ALTERNATIVE_SESSION,
    BUSY,
    CANCEL,
    CONNECTIVITY_ERROR,
    DECLINE,
    EXPIRED,
    FAILED_APPLICATION,
    FAILED_TRANSPORT,
    GENERAL_ERROR,
    GONE,
    INCOMPATIBLE_PARAMETERS,
    MEDIA_ERROR,
    SECURITY_ERROR,
    SUCCESS,
    TIMEOUT,
    UNSUPPORTED_APPLICATIONS,
    UNSUPPORTED_TRANSPORTS,
    UNKNOWN;

    override fun toString(): String =
        CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, name)

    companion object {
        @JvmStatic
        fun of(value: String): Reason =
            try {
                Reason.valueOf(CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_UNDERSCORE, value))
            } catch (e: Exception) {
                UNKNOWN
            }

        @JvmStatic
        fun of(e: RuntimeException): Reason =
            if (e is SecurityException) {
                SECURITY_ERROR
            } else if (e is AbstractContentMap.UnsupportedTransportException) {
                UNSUPPORTED_TRANSPORTS
            } else if (e is AbstractContentMap.UnsupportedApplicationException) {
                UNSUPPORTED_APPLICATIONS
            } else {
                FAILED_APPLICATION
            }

        @JvmStatic
        fun ofThrowable(throwable: Throwable): Reason {
            val root = Throwables.getRootCause(throwable)
            if (root is RuntimeException) {
                return of(root)
            }
            if (root is OmemoFailure) {
                return SECURITY_ERROR
            }
            return FAILED_APPLICATION
        }
    }
}
