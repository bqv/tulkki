package uk.xa0.tulkki.xmpp

import com.google.common.collect.ImmutableList
import uk.xa0.tulkki.crypto.sasl.SaslMechanism
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * Tulkki: the SASL login in flight, out of `XmppConnection`.
 *
 * The Java held this as a `private static class` nested in `XmppConnection`. It carries the
 * mechanism, the protocol version and the inline-bind features the connection negotiated, plus the
 * one-shot `success` flag; every member the Java reads is a field, so the fields stay public
 * (`@JvmField`) and the two static helpers keep their `@JvmStatic` bridges.
 *
 * The Java's `Preconditions.checkNotNull` pair is the non-null parameter contract Kotlin enforces
 * itself; `Collections.emptyList()`/`ImmutableList.copyOf` keep their declared `List<String>`
 * answer, and `success(...)` keeps `@Throws` because the Java caller's own try/catch names the
 * checked `SaslMechanism.AuthenticationException`.
 */
internal class LoginInfo(
    @JvmField val saslMechanism: SaslMechanism,
    @JvmField val saslVersion: SaslMechanism.Version,
    inlineBindFeatures: Collection<String>?,
) {

    @JvmField
    val inlineBindFeatures: List<String> =
        if (inlineBindFeatures == null) emptyList() else ImmutableList.copyOf(inlineBindFeatures)

    @JvmField val success = AtomicBoolean(false)

    @Throws(SaslMechanism.AuthenticationException::class)
    fun success(challenge: String?, sslSocket: SSLSocket?) {
        if (Thread.currentThread().isInterrupted) {
            throw SaslMechanism.AuthenticationException("Race condition during auth")
        }
        val response = saslMechanism.getResponse(challenge, sslSocket)
        if (!response.isNullOrEmpty()) {
            throw SaslMechanism.AuthenticationException(
                "processing success yielded another response",
            )
        }
        if (success.compareAndSet(false, true)) {
            return
        }
        throw SaslMechanism.AuthenticationException("Process 'success' twice")
    }

    companion object {

        @JvmStatic
        fun mechanism(loginInfo: LoginInfo?): SaslMechanism? = loginInfo?.saslMechanism

        @JvmStatic
        fun isSuccess(loginInfo: LoginInfo?): Boolean =
            loginInfo != null && loginInfo.success.get()
    }
}
