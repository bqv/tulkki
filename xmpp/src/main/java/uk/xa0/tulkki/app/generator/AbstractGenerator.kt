package uk.xa0.tulkki.app.generator

import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import uk.xa0.tulkki.xmpp.BuildConfig
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: the shared half of the three stanza generators - the identity, the feature list and the
 * timestamp.
 *
 * Ported from `AbstractGenerator.java`. It is *ours*, so it is converted in place.
 *
 * The Java-visible surface, read off the three subclasses and the outside callers:
 *
 *  * `getTimestamp` is called statically from Java and Kotlin alike (`IqGenerator.java:844,957,959`,
 *    `StanzaDispatch.kt:129`, `ConversationReload.kt:77`, `MessageArchiveService.kt:376,380`,
 *    `MucJoin.kt:116`, `MamLanguageSampler.kt:406`), so it stays a static through the companion's
 *    `@JvmStatic`. This is the member `MucJoin.kt` had to re-point when `PresenceGenerator` became
 *    Kotlin: Java reached it through the subclass, Kotlin cannot.
 *  * `mXmppConnectionService` is read as a **field** by `IqGenerator.java:88,793,794`, so it stays a
 *    `@JvmField` - exactly the shape `AbstractConnectionManager.kt` records for the same reason.
 *  * `getIdentityType`/`getIdentityName`/`getIdentityVersion`/`getCapHash` were package-private and
 *    are called by the Java subclasses (`IqGenerator.java:67,68,78,79`); Kotlin has no
 *    package-private and `internal` mangles the JVM name off that surface, so they become
 *    `protected` - the narrowest visibility a subclass caller still resolves, and a widening no
 *    other caller can observe. `getCapHash` answers **null** when SHA-1 is missing, which the Java
 *    `return null` in the `catch` proves and `PresenceGenerator.kt` reads as a nullable.
 *  * the constructor was package-private; Kotlin's is public, and no caller can tell.
 *
 * Two Java-isms kept deliberately: `toLowerCase()` is the **default** locale's, not `lowercase()`'s
 * invariant one, so it is `lowercase(Locale.getDefault())`; and the digest input uses
 * `toByteArray()` (UTF-8) where Java's `getBytes()` used the platform default, which on Android is
 * UTF-8, so the hash is unchanged.
 */
abstract class AbstractGenerator(service: XmppConnectionService) {

    private val STATIC_FEATURES = arrayOf(
        Namespace.JINGLE,
        Namespace.JINGLE_APPS_FILE_TRANSFER,
        Namespace.JINGLE_TRANSPORTS_S5B,
        Namespace.JINGLE_TRANSPORTS_IBB,
        Namespace.JINGLE_ENCRYPTED_TRANSPORT,
        Namespace.JINGLE_ENCRYPTED_TRANSPORT_OMEMO,
        "http://jabber.org/protocol/muc",
        "jabber:x:conference",
        Namespace.OOB,
        "http://jabber.org/protocol/caps",
        "http://jabber.org/protocol/disco#info",
        Namespace.PUBSUB,
        "urn:xmpp:avatar:metadata+notify",
        Namespace.NICK + "+notify",
        "urn:xmpp:ping",
        "jabber:iq:version",
        "http://jabber.org/protocol/chatstates",
        Namespace.REACTIONS,
        Namespace.USER_TUNE + "+notify",
        Namespace.PUBSUB_SOCIAL_FEED,
        Namespace.PUBSUB_SOCIAL_FEED + "+notify",
        Namespace.MICROBLOG,
        Namespace.MICROBLOG + "+notify",
        Namespace.PUBSUB_STORIES,
        Namespace.PUBSUB_STORIES + "+notify",
    )
    private val MESSAGE_CONFIRMATION_FEATURES = arrayOf(
        "urn:xmpp:chat-markers:0", "urn:xmpp:receipts",
    )
    private val MESSAGE_CORRECTION_FEATURES = arrayOf("urn:xmpp:message-correct:0")
    private val MESSAGE_RETRACTION_FEATURES = arrayOf(
        "urn:xmpp:message-retract:0",
    )
    private val PRIVACY_SENSITIVE = arrayOf(
        "urn:xmpp:time", // XEP-0202: Entity Time leaks time zone
    )
    private val OTR = arrayOf(
        "urn:xmpp:otr:0",
    )
    private val VOIP_NAMESPACES = arrayOf(
        Namespace.JINGLE_TRANSPORT_ICE_UDP,
        Namespace.JINGLE_FEATURE_AUDIO,
        Namespace.JINGLE_FEATURE_VIDEO,
        Namespace.JINGLE_APPS_RTP,
        Namespace.JINGLE_APPS_DTLS,
        Namespace.JINGLE_MESSAGE,
    )

    @JvmField
    protected var mXmppConnectionService: XmppConnectionService = service

    protected fun getIdentityVersion(): String = BuildConfig.VERSION_NAME

    protected fun getIdentityName(): String = BuildConfig.APP_NAME

    protected fun getIdentityType(): String =
        if ("chromium" == Build.BRAND) {
            "pc"
        } else {
            mXmppConnectionService.getString(R.string.default_resource).lowercase(Locale.getDefault())
        }

    protected fun getCapHash(account: AccountRef): String? {
        val s = StringBuilder()
        s.append("client/")
            .append(getIdentityType())
            .append("//")
            .append(getIdentityName())
            .append('<')
        val md: MessageDigest = try {
            MessageDigest.getInstance("SHA-1")
        } catch (e: NoSuchAlgorithmException) {
            return null
        }

        for (feature in getFeatures(account)) {
            s.append(feature).append('<')
        }
        val sha1 = md.digest(s.toString().toByteArray())
        return Base64.encodeToString(sha1, Base64.NO_WRAP)
    }

    fun getFeatures(account: AccountRef): List<String> {
        val connection: XmppConnection? = account.getXmppConnection()
        val features = ArrayList(Arrays.asList(*STATIC_FEATURES))
        features.add("http://jabber.org/protocol/xhtml-im")
        features.add("urn:xmpp:bob")
        features.add(Namespace.EPHEMERAL)
        if (Config.MESSAGE_DISPLAYED_SYNCHRONIZATION) {
            features.add(Namespace.MDS_DISPLAYED + "+notify")
        }
        if (mXmppConnectionService.confirmMessages()) {
            features.addAll(Arrays.asList(*MESSAGE_CONFIRMATION_FEATURES))
        }
        if (mXmppConnectionService.allowMessageCorrection()) {
            features.addAll(Arrays.asList(*MESSAGE_CORRECTION_FEATURES))
            features.addAll(Arrays.asList(*MESSAGE_RETRACTION_FEATURES))
        }
        if (Config.supportOmemo()) {
            features.add(OmemoSessionPort.PEP_DEVICE_LIST_NOTIFY)
        }
        if (!mXmppConnectionService.useTorToConnect() && !account.isOnion() && !mXmppConnectionService.useI2PToConnect() && !account.isI2P()) {
            features.addAll(Arrays.asList(*PRIVACY_SENSITIVE))
            features.addAll(Arrays.asList(*VOIP_NAMESPACES))
            features.add(Namespace.JINGLE_TRANSPORT_WEBRTC_DATA_CHANNEL)
        }
        if (Config.supportOtr()) {
            features.addAll(Arrays.asList(*OTR))
        }
        if (mXmppConnectionService.broadcastLastActivity()) {
            features.add(Namespace.IDLE)
        }
        if (connection != null && connection.getFeatures().bookmarks2()) {
            features.add(Namespace.BOOKMARKS2 + "+notify")
        } else {
            features.add(Namespace.BOOKMARKS + "+notify")
        }

        Collections.sort(features)
        return features
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        @JvmStatic
        fun getTimestamp(time: Long): String {
            DATE_FORMAT.timeZone = TimeZone.getTimeZone("UTC")
            return DATE_FORMAT.format(time)
        }
    }
}
