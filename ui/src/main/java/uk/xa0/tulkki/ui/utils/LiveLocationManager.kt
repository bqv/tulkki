package uk.xa0.tulkki.ui.utils

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class LiveLocationManager private constructor() {

    interface PositionListener {
        fun onPositionUpdate(sessionId: String, latitude: Double, longitude: Double)

        fun onSessionExpired(sessionId: String)
    }

    class IncomingSession internal constructor(
        @JvmField val sessionId: String,
        @JvmField val conversationUuid: String?,
        @JvmField val messageUuid: String?,
        lat: Double,
        lon: Double,
        @JvmField val expiresAt: Long
    ) {
        @JvmField
        @Volatile
        var latitude: Double = lat

        @JvmField
        @Volatile
        var longitude: Double = lon

        fun isExpired(): Boolean {
            return System.currentTimeMillis() > expiresAt
        }
    }

    class OutgoingSession internal constructor(
        @JvmField val sessionId: String?,
        @JvmField val conversationUuid: String?,
        @JvmField val messageUuid: String?,
        @JvmField val expiresAt: Long,
        lat: Double,
        lon: Double
    ) {
        @JvmField
        @Volatile
        var latitude: Double = lat

        @JvmField
        @Volatile
        var longitude: Double = lon

        fun isExpired(): Boolean {
            return System.currentTimeMillis() > expiresAt
        }
    }

    private val incoming = ConcurrentHashMap<String, IncomingSession>()
    private val messageToSession = ConcurrentHashMap<String, String>()
    private val outgoingByConversation = ConcurrentHashMap<String, OutgoingSession>()
    private val listeners = CopyOnWriteArraySet<PositionListener>()
    private val sessionAvatars = ConcurrentHashMap<String, Drawable>()
    private val previewRefreshTimes = ConcurrentHashMap<String, Long>()
    private val stoppedSessionIds = ConcurrentHashMap<String, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var onSessionExpiredUiCallback: Runnable? = null

    fun registerIncomingSession(
        sessionId: String?,
        conversationUuid: String?,
        messageUuid: String?,
        lat: Double,
        lon: Double,
        expiresAt: Long
    ) {
        if (sessionId == null) {
            return
        }
        val s = IncomingSession(sessionId, conversationUuid, messageUuid, lat, lon, expiresAt)
        incoming[sessionId] = s
        if (messageUuid != null) {
            messageToSession[messageUuid] = sessionId
        }
        scheduleExpiry(sessionId, expiresAt)
    }

    fun updateIncomingPosition(sessionId: String?, lat: Double, lon: Double) {
        if (sessionId == null) {
            return
        }
        val s = incoming[sessionId]
        if (s != null && !s.isExpired()) {
            s.latitude = lat
            s.longitude = lon
        }
        for (l in listeners) {
            l.onPositionUpdate(sessionId, lat, lon)
        }
    }

    fun notifyOutgoingPositionUpdate(sessionId: String?, lat: Double, lon: Double) {
        if (sessionId == null) {
            return
        }
        for (os in outgoingByConversation.values) {
            if (sessionId == os.sessionId) {
                os.latitude = lat
                os.longitude = lon
                break
            }
        }
        for (l in listeners) {
            l.onPositionUpdate(sessionId, lat, lon)
        }
    }

    fun setSessionAvatar(sessionId: String, drawable: Drawable?) {
        if (drawable != null) sessionAvatars[sessionId] = drawable
    }

    fun getSessionAvatar(sessionId: String?): Drawable? {
        return if (sessionId != null) sessionAvatars[sessionId] else null
    }

    fun isPreviewRefreshDue(sessionId: String?, throttleMs: Long): Boolean {
        if (sessionId == null) {
            return false
        }
        val now = System.currentTimeMillis()
        val last = previewRefreshTimes[sessionId]
        if (last == null || now - last >= throttleMs) {
            previewRefreshTimes[sessionId] = now
            return true
        }
        return false
    }

    fun isActiveLiveLocationMessage(messageUuid: String?): Boolean {
        if (messageUuid == null) {
            return false
        }
        val sessionId = messageToSession[messageUuid]
        if (sessionId != null) {
            val s = incoming[sessionId]
            if (s != null && !s.isExpired()) return true
        }
        for (os in outgoingByConversation.values) {
            if (messageUuid == os.messageUuid && !os.isExpired()) return true
        }
        return false
    }

    fun getSessionForMessage(messageUuid: String?): IncomingSession? {
        if (messageUuid == null) {
            return null
        }
        val sessionId = messageToSession[messageUuid] ?: return null
        val s = incoming[sessionId]
        return if (s != null && !s.isExpired()) s else null
    }

    fun getSession(sessionId: String?): IncomingSession? {
        if (sessionId == null) {
            return null
        }
        return incoming[sessionId]
    }

    fun getSessionIdForMessage(messageUuid: String?): String? {
        if (messageUuid == null) {
            return null
        }
        val sessionId = messageToSession[messageUuid]
        if (sessionId != null) return sessionId
        for (os in outgoingByConversation.values) {
            if (messageUuid == os.messageUuid && !os.isExpired()) return os.sessionId
        }
        return null
    }

    fun addListener(l: PositionListener) {
        listeners.add(l)
    }

    fun removeListener(l: PositionListener) {
        listeners.remove(l)
    }

    fun registerOutgoingSession(
        conversationUuid: String,
        sessionId: String?,
        messageUuid: String?,
        expiresAt: Long,
        lat: Double,
        lon: Double
    ) {
        val os = OutgoingSession(sessionId, conversationUuid, messageUuid, expiresAt, lat, lon)
        outgoingByConversation[conversationUuid] = os
    }

    fun getOutgoingSession(conversationUuid: String?): OutgoingSession? {
        val os = outgoingByConversation[conversationUuid]
        return if (os != null && !os.isExpired()) os else null
    }

    val allOutgoingSessions: Collection<OutgoingSession>
        get() = outgoingByConversation.values

    fun clearOutgoingSession(conversationUuid: String?) {
        if (conversationUuid != null) {
            outgoingByConversation.remove(conversationUuid)
        }
    }

    private fun scheduleExpiry(sessionId: String, expiresAt: Long) {
        val delay = expiresAt - System.currentTimeMillis()
        if (delay <= 0) {
            expireSession(sessionId)
            return
        }
        mainHandler.postDelayed({ expireSession(sessionId) }, delay)
    }

    fun setOnSessionExpiredUiCallback(callback: Runnable?) {
        onSessionExpiredUiCallback = callback
    }

    fun expireIncomingSession(sessionId: String?) {
        expireSession(sessionId)
    }

    fun isSessionStopped(sessionId: String?): Boolean {
        return sessionId != null && stoppedSessionIds.containsKey(sessionId)
    }

    private fun expireSession(sessionId: String?) {
        if (sessionId == null) {
            return
        }
        stoppedSessionIds[sessionId] = true
        val s = incoming.remove(sessionId)
        if (s != null) {
            if (s.messageUuid != null) {
                messageToSession.remove(s.messageUuid)
            }
            previewRefreshTimes.remove(sessionId)
            for (l in listeners) {
                l.onSessionExpired(sessionId)
            }
            val callback = onSessionExpiredUiCallback
            if (callback != null) {
                mainHandler.post(callback)
            }
        }
    }

    companion object {
        private val INSTANCE = LiveLocationManager()

        @JvmStatic
        fun getInstance(): LiveLocationManager {
            return INSTANCE
        }
    }
}
