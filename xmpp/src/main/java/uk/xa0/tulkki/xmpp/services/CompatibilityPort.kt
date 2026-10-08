package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.net.ConnectivityManager

/**
 * Tulkki: the platform-compatibility decisions this island makes.
 *
 * <p>{@code uk.xa0.tulkki.app.utils.Compatibility} stays where it is for the `:app` and `:ui` callers
 * that use its statics; the class also implements this port, so the island asks instead of
 * naming it. The seventh member is the static {@code s()} the file used to import statically.
 */
interface CompatibilityPort {

    fun s(): Boolean

    fun isActiveNetworkMetered(connectivityManager: ConnectivityManager): Boolean

    fun getRestrictBackgroundStatus(connectivityManager: ConnectivityManager): Int

    fun runsTwentySix(): Boolean

    fun runsAndTargetsTwentySix(context: Context): Boolean

    fun hasStoragePermission(context: Context): Boolean

    fun keepForegroundService(context: Context): Boolean
}
