package uk.xa0.tulkki.crypto.sasl

import android.util.Log
import com.google.common.base.CaseFormat
import com.google.common.collect.BiMap
import com.google.common.collect.ImmutableBiMap
import java.util.Collections
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.cb.SaslChannelBinding
import uk.xa0.tulkki.xmpp.utils.SSLSockets

enum class ChannelBinding {
    NONE,
    TLS_EXPORTER,
    TLS_SERVER_END_POINT,
    TLS_UNIQUE;

    companion object {

        @JvmField
        val SHORT_NAMES: BiMap<ChannelBinding, String> =
            ImmutableBiMap.builder<ChannelBinding, String>()
                .apply { for (cb in ChannelBinding.values()) put(cb, shortName(cb)) }
                .build()

        @JvmStatic
        fun of(channelBinding: SaslChannelBinding?): Collection<ChannelBinding> {
            if (channelBinding == null) {
                return emptyList()
            }
            return channelBinding.getChannelBindings().mapNotNull { of(it.getType()) }
        }

        private fun of(type: String?): ChannelBinding? {
            val name = type ?: return null
            try {
                val converted = CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_UNDERSCORE, name)
                return valueOf(converted)
            } catch (e: IllegalArgumentException) {
                Log.d(Config.LOGTAG, name + " is not a known channel binding")
                return null
            }
        }

        @JvmStatic
        fun get(name: String?): ChannelBinding {
            if (name.isNullOrEmpty()) {
                return NONE
            }
            return try {
                valueOf(name)
            } catch (e: IllegalArgumentException) {
                NONE
            }
        }

        @JvmStatic
        @JvmSuppressWildcards
        fun best(bindings: Collection<ChannelBinding>, sslVersion: SSLSockets.Version): ChannelBinding {
            if (sslVersion == SSLSockets.Version.NONE) {
                return NONE
            }
            if (bindings.contains(TLS_EXPORTER) && sslVersion == SSLSockets.Version.TLS_1_3) {
                return TLS_EXPORTER
            } else if (bindings.contains(TLS_UNIQUE) &&
                listOf(
                    SSLSockets.Version.TLS_1_0,
                    SSLSockets.Version.TLS_1_1,
                    SSLSockets.Version.TLS_1_2,
                ).contains(sslVersion)
            ) {
                return TLS_UNIQUE
            } else if (bindings.contains(TLS_SERVER_END_POINT)) {
                return TLS_SERVER_END_POINT
            } else if (bindings.isEmpty()) {
                Log.w(Config.LOGTAG, "no supported bindings. making a guess")
                return fallback(sslVersion)
            } else {
                return NONE
            }
        }

        private fun fallback(version: SSLSockets.Version): ChannelBinding {
            if (version == SSLSockets.Version.TLS_1_3) {
                return TLS_EXPORTER
            } else if (listOf(
                    SSLSockets.Version.TLS_1_0,
                    SSLSockets.Version.TLS_1_1,
                    SSLSockets.Version.TLS_1_2,
                ).contains(version)
            ) {
                return TLS_UNIQUE
            } else {
                return NONE
            }
        }

        @JvmStatic
        fun isAvailable(channelBinding: ChannelBinding, sslVersion: SSLSockets.Version): Boolean =
            best(Collections.singleton(channelBinding), sslVersion) == channelBinding

        private fun shortName(channelBinding: ChannelBinding): String = when (channelBinding) {
            TLS_UNIQUE -> "UNIQ"
            TLS_EXPORTER -> "EXPR"
            TLS_SERVER_END_POINT -> "ENDP"
            NONE -> "NONE"
            else -> throw AssertionError("Missing short name for " + channelBinding)
        }

        @JvmStatic
        fun priority(channelBinding: ChannelBinding): Int {
            if (listOf(TLS_EXPORTER, TLS_UNIQUE).contains(channelBinding)) {
                return 2
            } else if (channelBinding == ChannelBinding.TLS_SERVER_END_POINT) {
                return 1
            } else {
                return 0
            }
        }
    }
}
