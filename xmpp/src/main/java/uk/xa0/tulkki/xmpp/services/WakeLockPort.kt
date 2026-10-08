package uk.xa0.tulkki.xmpp.services

import android.os.PowerManager.WakeLock

/** Tulkki: the reference-counted wake lock helpers. */
interface WakeLockPort {

    fun acquire(wakeLock: WakeLock?)

    fun release(wakeLock: WakeLock?)
}
