@file:Suppress("DEPRECATION") // android.preference.PreferenceManager is the class the Java body used

package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.annotation.BoolRes
import androidx.annotation.IntegerRes
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.ConversationRef

/**
 * Tulkki: the service's typed view of `SharedPreferences`, lifted out of `XmppConnectionService`
 *.
 *
 * Every read the Java did, with the same names and the same defaults; the `Context` is the service
 * itself (it read its own `getApplicationContext()`), and the resource id stays a resource id, so
 * `getLongPreference` still reads `getResources().getInteger(res)`. [unreadCount] is the live count
 * over the conversation list, not the cached field of the same name elsewhere — the partition puts
 * it in this chunk, and it needs the service because `ConversationRef.unreadCount` takes it.
 */
object ServicePreferences {

    /** `PreferenceManager.getDefaultSharedPreferences(getApplicationContext())`. */
    @JvmStatic
    fun getPreferences(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /**
     * The deletion cutoff in wall-clock milliseconds, or `0` when deletion is off; the stored value
     * is a timeout in seconds, so the Java subtraction is kept as it was.
     */
    @JvmStatic
    fun getAutomaticMessageDeletionDate(context: Context): Long {
        val timeout = getLongPreference(
            context,
            XmppConnectionService.DataStatics.AUTOMATIC_MESSAGE_DELETION,
            R.integer.automatic_message_deletion
        )
        return if (timeout == 0L) timeout
        else System.currentTimeMillis() - (timeout * 1000)
    }

    /** The OMEMO auto-expiry, stored in seconds and answered in milliseconds. */
    @JvmStatic
    fun getOmemoAutoExpiry(context: Context): Long =
        getLongPreference(
            context,
            XmppConnectionService.DataStatics.OMEMO_AUTO_EXPIRY,
            R.integer.omemo_auto_expiry
        ) * 1000L

    /**
     * The stored value parsed as a long, falling back to the resource's integer on a missing or
     * malformed value. A null from the SDK's nullable `getString` takes the same fallback the
     * `NumberFormatException` catch takes; the value is never null in practice, because the Java
     * always passed a non-null default.
     */
    @JvmStatic
    fun getLongPreference(context: Context, name: String, @IntegerRes res: Int): Long {
        val defaultValue = context.resources.getInteger(res).toLong()
        val raw = preferences(context).getString(name, defaultValue.toString())
        return try {
            raw?.toLong() ?: defaultValue
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    /** The stored boolean, or the resource's boolean when the key is absent. */
    @JvmStatic
    fun getBooleanPreference(context: Context, name: String, @BoolRes res: Int): Boolean =
        preferences(context).getBoolean(name, context.resources.getBoolean(res))

    /**
     * The stored string, or the resource's string when the key is absent. Non-null: the resource's
     * string is the default, and a null from the SDK's nullable `getString` falls back to it, as the
     * Java's non-null return declared.
     */
    @JvmStatic
    fun getStringPreference(context: Context, name: String, @BoolRes res: Int): String {
        val defaultValue = context.resources.getString(res)
        return preferences(context).getString(name, defaultValue) ?: defaultValue
    }

    @JvmStatic
    fun confirmMessages(context: Context): Boolean =
        getBooleanPreference(context, "confirm_messages", R.bool.confirm_messages)

    /**
     * Tulkki: the muc name colouring, moved here from the service's `colored_muc_names()` (chunk
     * `C60`). It is the same preference read `ServicePreferences` already owns, so the service's
     * public one-line delegation keeps the name and `:ui`'s one call site unchanged.
     */
    @JvmStatic
    fun coloredMucNames(context: Context): Boolean =
        getBooleanPreference(context, "colored_muc_names", R.bool.use_colored_muc_names)

    @JvmStatic
    fun allowMessageCorrection(context: Context): Boolean =
        getBooleanPreference(context, "allow_message_correction", R.bool.allow_message_correction)

    @JvmStatic
    fun showTextFormatting(context: Context): Boolean =
        getBooleanPreference(context, "showtextformatting", R.bool.showtextformatting)

    @JvmStatic
    fun sendChatStates(context: Context): Boolean =
        getBooleanPreference(context, "chat_states", R.bool.chat_states)

    @JvmStatic
    fun useTorToConnect(context: Context): Boolean =
        getBooleanPreference(context, "use_tor", R.bool.use_tor)

    @JvmStatic
    fun useI2PToConnect(context: Context): Boolean =
        getBooleanPreference(context, "use_i2p", R.bool.use_i2p)

    @JvmStatic
    fun broadcastLastActivity(context: Context): Boolean =
        getBooleanPreference(
            context,
            XmppConnectionService.DataStatics.BROADCAST_LAST_ACTIVITY,
            R.bool.last_activity
        )

    /** The live sum of every conversation's unread count, not the cached `unreadCount` field. */
    @JvmStatic
    fun unreadCount(service: XmppConnectionService): Int {
        var count = 0
        for (conversation in service.getConversationList()) {
            count += conversation.unreadCount(service)
        }
        return count
    }

    private fun preferences(context: Context): SharedPreferences = getPreferences(context)
}
