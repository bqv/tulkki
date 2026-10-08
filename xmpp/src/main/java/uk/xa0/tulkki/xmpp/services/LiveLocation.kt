package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xml.Tag
import uk.xa0.tulkki.xml.XmlReader
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Tulkki: live-location sharing, lifted out of `XmppConnectionService`
 *.
 *
 * C08's three fields split the way the Java reads them. `mLiveLocationAndroidManager` is read by
 * nothing outside this chunk (it starts `null` and is created lazily), so it moves **whole** as this
 * object's private state, the shape C05's `mPgpEngine` took. `mOutgoingLiveSessions` stays a field of
 * the service because C23's `ForegroundServiceLifecycle` reads it for the foreground decision, so it
 * travels in **by value** and the same map object is mutated; `mLiveLocationHandler` stays because
 * C02/C19/C48 post through it, and also travels by value. C70's `mNotificationService` is the
 * nullable port the Java dereferenced bare, so it arrives nullable and the dereference keeps the
 * Java's NPE rather than becoming a `checkNotNullParameter`. Everything else these methods reach —
 * `dataStatics()`, `getBooleanPreference`, `getPgpEngine`, `sendMessage`, `sendMessagePacket`,
 * `findConversationByUuid`, `updateMessageGeoPayload`, `toggleForegroundService`,
 * `updateConversationUi` and `liveLocationHook()` — is a public service member.
 *
 * The Java's order, its `String.format(Locale.US, …)` bodies, its one-shot expiry `Runnable` (posted
 * once and cancelled through `removeCallbacks`) and its two bare `catch (SecurityException ignored)`
 * blocks are kept. `OutgoingLiveInfo` moves whole as the nested value class the session map holds.
 */
object LiveLocation {

    private var liveLocationAndroidManager: LocationManager? = null

    /** The Java's private static `OutgoingLiveInfo`; the two slots are the Java's nullable ones. */
    class OutgoingLiveInfo(
        @JvmField val sessionId: String,
        @JvmField val conversation: ConversationRef,
        @JvmField val expiresAt: Long,
        @JvmField var locationListener: LocationListener? = null,
        @JvmField var expiryRunnable: Runnable? = null,
    )

    @JvmStatic
    fun attachLocationToConversation(
        service: XmppConnectionService,
        conversation: ConversationRef,
        uri: Uri,
        subject: String?,
        callback: UiCallbackPort<MessageRef>,
    ) {
        var encryption = conversation.getNextEncryption()
        if (encryption == MessageRef.ENCRYPTION_PGP) {
            encryption = MessageRef.ENCRYPTION_DECRYPTED
        }
        val message = XmppConnectionService.dataStatics().newMessage(conversation, uri.toString(), encryption)
        if (subject != null && subject.length > 0) message.setSubject(subject)
        if (service.getBooleanPreference("show_thread_feature", R.bool.show_thread_feature)) {
            message.setThread(conversation.getThread())
        }
        XmppConnectionService.dataStatics().configurePrivateMessage(message)
        if (encryption == MessageRef.ENCRYPTION_DECRYPTED) {
            val pgpEngine = service.getPgpEngine()
                ?: throw NullPointerException("the PGP engine is not installed")
            pgpEngine.encrypt(message, callback)
        } else {
            service.sendMessage(message)
            callback.success(message)
        }
    }

    @JvmStatic
    fun startLiveLocationSharing(
        service: XmppConnectionService,
        outgoingLiveSessions: MutableMap<String, OutgoingLiveInfo>,
        liveLocationHandler: Handler,
        notificationService: NotificationPort?,
        conversation: ConversationRef,
        durationMs: Long,
        initialLat: Double,
        initialLon: Double,
        initialAccuracy: Float,
    ) {
        val sessionId = UUID.randomUUID().toString()
        val conversationUuid =
            conversation.getUuid() ?: throw NullPointerException("conversation has no uuid")
        val expiresAt = System.currentTimeMillis() + durationMs
        val expiresAtISO =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(expiresAt))

        var encryption = conversation.getNextEncryption()
        if (encryption == MessageRef.ENCRYPTION_PGP) {
            encryption = MessageRef.ENCRYPTION_DECRYPTED
        }
        val geoUriStr: String
        if (initialAccuracy > 0) {
            geoUriStr = String.format(Locale.US, "geo:%s,%s;u=%s", initialLat, initialLon, initialAccuracy.toInt())
        } else {
            geoUriStr = String.format(Locale.US, "geo:%s,%s", initialLat, initialLon)
        }
        val message = XmppConnectionService.dataStatics().newMessage(conversation, geoUriStr, encryption)
        message.setEphemeralTimer(0) // live location has its own lifetime; never expire via ephemeral timer
        if (service.getBooleanPreference("show_thread_feature", R.bool.show_thread_feature)) {
            message.setThread(conversation.getThread())
        }
        XmppConnectionService.dataStatics().configurePrivateMessage(message)

        val liveEl = Element("live-location", Namespace.LIVE_LOCATION)
        liveEl.setAttribute("id", sessionId)
        liveEl.setAttribute("expires", expiresAtISO)
        message.addPayload(liveEl)

        service.sendMessage(message)

        service.liveLocationHook().registerOutgoingSession(
            conversationUuid,
            sessionId,
            message.getUuid() ?: throw NullPointerException("message has no uuid"),
            expiresAt,
            initialLat,
            initialLon,
        )

        if (liveLocationAndroidManager == null) {
            liveLocationAndroidManager =
                service.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }

        val info = OutgoingLiveInfo(sessionId, conversation, expiresAt)

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                sendLiveLocationUpdate(
                    service,
                    conversation,
                    sessionId,
                    location.latitude,
                    location.longitude,
                    location.accuracy,
                )
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        info.locationListener = locationListener
        outgoingLiveSessions[conversationUuid] = info

        notificationService!!.showLiveLocationNotification(conversationUuid)
        // Must call startForeground with FOREGROUND_SERVICE_TYPE_LOCATION before
        // requestLocationUpdates — Android 14+ revokes location access otherwise.
        service.toggleForegroundService()

        try {
            val manager = liveLocationAndroidManager
            if (manager != null) {
                val providers = manager.allProviders
                if (providers.contains(LocationManager.GPS_PROVIDER)) {
                    manager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        5_000L,
                        5f,
                        locationListener,
                        Looper.getMainLooper(),
                    )
                }
                if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                    manager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5_000L,
                        5f,
                        locationListener,
                        Looper.getMainLooper(),
                    )
                }
            }
        } catch (ignored: SecurityException) {
        }

        val expiryRunnable = Runnable { stopLiveLocationSharing(service, outgoingLiveSessions, liveLocationHandler, notificationService, conversationUuid) }
        info.expiryRunnable = expiryRunnable
        liveLocationHandler.postDelayed(expiryRunnable, durationMs)
        service.updateConversationUi()
    }

    private fun sendLiveLocationUpdate(
        service: XmppConnectionService,
        conversation: ConversationRef,
        sessionId: String,
        lat: Double,
        lon: Double,
        accuracy: Float,
    ) {
        val packet = Message()
        packet.setTo(
            if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                conversation.getJid()
            } else {
                (conversation.getJid() ?: throw NullPointerException("conversation has no jid"))
                    .asBareJid()
            },
        )
        packet.setType(
            if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                Message.Type.CHAT
            } else {
                Message.Type.GROUPCHAT
            },
        )
        val update = packet.addChild("live-location-update", Namespace.LIVE_LOCATION)
        update.setAttribute("id", sessionId)
        update.setAttribute("lat", lat.toString())
        update.setAttribute("lon", lon.toString())
        packet.addChild("no-store", Namespace.HINTS)
        service.sendMessagePacket(
            conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
            packet,
        )
        service.liveLocationHook().notifyOutgoingPositionUpdate(sessionId, lat, lon)
        service.updateMessageGeoPayload(
            conversation.getUuid() ?: throw NullPointerException("conversation has no uuid"),
            getLiveLocationMessageUuid(service, sessionId),
            lat,
            lon,
        )
    }

    private fun getLiveLocationMessageUuid(service: XmppConnectionService, sessionId: String): String? =
        // Pair 11 (D4): the loop over the manager's outgoing sessions is the manager's own accessor,
        // so the port answers it; the `OutgoingSession` type was a `:ui` type this island named.
        service.liveLocationHook().outgoingMessageUuid(sessionId)

    @JvmStatic
    fun stopLiveLocationSharing(
        service: XmppConnectionService,
        outgoingLiveSessions: MutableMap<String, OutgoingLiveInfo>,
        liveLocationHandler: Handler,
        notificationService: NotificationPort?,
        conversationUuid: String,
    ) {
        val info = outgoingLiveSessions.remove(conversationUuid) ?: return
        val listener = info.locationListener
        val manager = liveLocationAndroidManager
        if (listener != null && manager != null) {
            try {
                manager.removeUpdates(listener)
            } catch (ignored: SecurityException) {
            }
        }
        val expiryRunnable = info.expiryRunnable
        if (expiryRunnable != null) {
            liveLocationHandler.removeCallbacks(expiryRunnable)
        }
        service.liveLocationHook().clearOutgoingSession(conversationUuid)
        // Notify receiver that sharing has stopped
        val conversation = service.findConversationByUuid(conversationUuid)
        if (conversation != null) {
            val packet = Message()
            packet.setTo(
                if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                    conversation.getJid()
                } else {
                    (conversation.getJid()
                        ?: throw NullPointerException("conversation has no jid"))
                        .asBareJid()
                },
            )
            packet.setType(
                if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                    Message.Type.CHAT
                } else {
                    Message.Type.GROUPCHAT
                },
            )
            packet.addChild("live-location-stop", Namespace.LIVE_LOCATION).setAttribute("id", info.sessionId)
            packet.addChild("no-store", Namespace.HINTS)
            service.sendMessagePacket(
                conversation.getAccount()
                    ?: throw NullPointerException("conversation has no account"),
                packet,
            )
        }
        notificationService!!.cancelLiveLocationNotification()
        service.toggleForegroundService()
        service.updateConversationUi()
    }

    @JvmStatic
    fun sendLiveLocationStopForOrphanedSessions(
        service: XmppConnectionService,
        outgoingLiveSessions: MutableMap<String, OutgoingLiveInfo>,
        account: AccountRef,
    ) {
        val rows = (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getRecentOutgoingLiveLocationMessages(account.getUuid())
        for (row in rows) {
            val conversationUuid = row[0]
            if (outgoingLiveSessions.containsKey(conversationUuid)) continue
            val payloadsXml = row[1]
            if (payloadsXml == null) continue
            // Parse session id and expires out of the stored payload XML
            var sessionId: String? = null
            var expiresAt = 0L
            try {
                val xmlReader = XmlReader()
                xmlReader.setInputStream(
                    ByteArrayInputStream(payloadsXml.toByteArray(StandardCharsets.UTF_8)),
                )
                var tag: Tag? = xmlReader.readTag()
                while (tag != null) {
                    if ("live-location" == tag.getName()) {
                        val el = xmlReader.readElement(tag)
                        sessionId = el.getAttribute("id")
                        val expiresStr = el.getAttribute("expires")
                        if (expiresStr != null) {
                            try {
                                expiresAt = AbstractParser.parseTimestamp(expiresStr)
                            } catch (ignored: Exception) {
                            }
                        }
                        break
                    }
                    tag = xmlReader.readTag()
                }
            } catch (e: Exception) {
                Log.d(Config.LOGTAG, "Could not parse live-location payload: " + e.message)
                continue
            }
            if (sessionId == null || System.currentTimeMillis() >= expiresAt) continue
            // Session was active when app died — send stop stanza now
            val conversation = service.findConversationByUuid(conversationUuid)
            if (conversation == null || conversation.getAccount() !== account) continue
            val packet = Message()
            packet.setTo(
                if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                    conversation.getJid()
                } else {
                    (conversation.getJid()
                        ?: throw NullPointerException("conversation has no jid"))
                        .asBareJid()
                },
            )
            packet.setType(
                if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                    Message.Type.CHAT
                } else {
                    Message.Type.GROUPCHAT
                },
            )
            packet.addChild("live-location-stop", Namespace.LIVE_LOCATION).setAttribute("id", sessionId)
            packet.addChild("no-store", Namespace.HINTS)
            service.sendMessagePacket(account, packet)
            Log.d(
                Config.LOGTAG,
                "" + account.getJid().asBareJid() + ": sent live-location-stop for orphaned session " + sessionId,
            )
        }
    }
}
