package uk.xa0.tulkki.crypto.sasl

import android.util.Log
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import java.util.Collections
import javax.net.ssl.SSLSocket
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.utils.SSLSockets

abstract class SaslMechanism protected constructor(
    @JvmField protected val account: AccountRef,
) {

    @JvmField
    protected var state: State = State.INITIAL

    /**
     * The priority is used to pin the authentication mechanism. If authentication fails, it MAY be
     * retried with another mechanism of the same priority, but MUST NOT be tried with a mechanism
     * of lower priority (to prevent downgrade attacks).
     *
     * @return An arbitrary int representing the priority
     */
    abstract fun getPriority(): Int

    abstract fun getMechanism(): String

    abstract fun getClientFirstMessage(sslSocket: SSLSocket?): String

    @Throws(AuthenticationException::class)
    abstract fun getResponse(challenge: String?, sslSocket: SSLSocket?): String?

    enum class State {
        INITIAL,
        AUTH_TEXT_SENT,
        RESPONSE_SENT,
        VALID_SERVER_RESPONSE,
    }

    @Throws(InvalidStateException::class)
    protected fun checkState(expected: State) {
        val current = this.state
        if (current != expected) {
            throw InvalidStateException(String.format("State was %s. Expected %s", current, expected))
        }
    }

    enum class Version {
        SASL,
        SASL_2;

        companion object {
            @JvmStatic
            fun of(element: Element): Version =
                when (Strings.nullToEmpty(element.getNamespace())) {
                    Namespace.SASL -> SASL
                    Namespace.SASL_2 -> SASL_2
                    else -> throw IllegalArgumentException("Unrecognized SASL namespace")
                }
        }
    }

    open class AuthenticationException : Exception {
        constructor(message: String) : super(message)

        constructor(inner: Exception) : super(inner)

        constructor(message: String, exception: Exception) : super(message, exception)
    }

    open class InvalidStateException : AuthenticationException {
        constructor(message: String) : super(message)

        constructor(state: State) : super("Invalid state: " + state)
    }

    class Factory(private val account: AccountRef) {

        @JvmSuppressWildcards
        private fun of(
            mechanisms: Collection<String>,
            channelBinding: ChannelBinding,
        ): SaslMechanism? {
            Preconditions.checkNotNull(channelBinding, "Use ChannelBinding.NONE instead of null")
            if (mechanisms.contains(External.MECHANISM) && account.getPrivateKeyAlias() != null) {
                return External(account)
            } else if (mechanisms.contains(ScramSha512Plus.MECHANISM) &&
                channelBinding != ChannelBinding.NONE
            ) {
                return ScramSha512Plus(account, channelBinding)
            } else if (mechanisms.contains(ScramSha256Plus.MECHANISM) &&
                channelBinding != ChannelBinding.NONE
            ) {
                return ScramSha256Plus(account, channelBinding)
            } else if (mechanisms.contains(ScramSha1Plus.MECHANISM) &&
                channelBinding != ChannelBinding.NONE
            ) {
                return ScramSha1Plus(account, channelBinding)
            } else if (mechanisms.contains(ScramSha512.MECHANISM)) {
                return ScramSha512(account)
            } else if (mechanisms.contains(ScramSha256.MECHANISM)) {
                return ScramSha256(account)
            } else if (mechanisms.contains(ScramSha1.MECHANISM)) {
                return ScramSha1(account)
            } else if (mechanisms.contains(Plain.MECHANISM)) {
                return Plain(account)
            } else if (mechanisms.contains(DigestMd5.MECHANISM)) {
                return DigestMd5(account)
            } else if (mechanisms.contains(Anonymous.MECHANISM)) {
                return Anonymous(account)
            } else {
                return null
            }
        }

        @JvmSuppressWildcards
        fun of(
            mechanisms: Collection<String>,
            bindings: Collection<ChannelBinding>,
            version: Version,
            sslVersion: SSLSockets.Version,
        ): SaslMechanism? {
            val fastMechanism = account.getFastMechanism()
            if (version == Version.SASL_2 && fastMechanism != null) {
                return fastMechanism
            }
            val channelBinding = ChannelBinding.best(bindings, sslVersion)
            return of(mechanisms, channelBinding)
        }

        fun of(mechanism: String, channelBinding: ChannelBinding): SaslMechanism? =
            of(Collections.singleton(mechanism), channelBinding)
    }

    companion object {

        @JvmStatic
        fun namespace(version: Version): String =
            if (version == Version.SASL) {
                Namespace.SASL
            } else {
                Namespace.SASL_2
            }

        /**
         * Tulkki: the parameter is nullable because the Java original's own body was, and the end
         * that changes is the one that body justifies. `null instanceof ChannelBindingMechanism` is
         * false, so the original took the `requireChannelBinding` branch and returned `null` (or
         * `mechanism`, itself `null`) - it never dereferenced the argument. The Java was a platform
         * type, so Kotlin accepted a null from `AccountRef.getQuickStartMechanism()`; declaring it
         * non-null here inserted `checkNotNullExpressionValue`, and a server whose account has no
         * quick-start mechanism answered null and crashed registration during TLS
         * (`XmppConnection.establishStream`, on the connection thread). This is the Java's platform
         * type restored, not a widening.
         */
        @JvmStatic
        fun ensureAvailable(
            mechanism: SaslMechanism?,
            sslVersion: SSLSockets.Version,
            requireChannelBinding: Boolean,
        ): SaslMechanism? {
            if (mechanism is ChannelBindingMechanism) {
                val cb = mechanism.getChannelBinding()
                if (ChannelBinding.isAvailable(cb, sslVersion)) {
                    return mechanism
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "pinned channel binding method " + cb + " no longer available",
                    )
                    return null
                }
            } else if (requireChannelBinding) {
                Log.d(Config.LOGTAG, "pinned mechanism did not provide channel binding")
                return null
            } else {
                return mechanism
            }
        }

        @JvmStatic
        fun hashedToken(saslMechanism: SaslMechanism): Boolean = saslMechanism is HashedToken

        @JvmStatic
        fun pin(saslMechanism: SaslMechanism): Boolean = !hashedToken(saslMechanism)
    }
}
