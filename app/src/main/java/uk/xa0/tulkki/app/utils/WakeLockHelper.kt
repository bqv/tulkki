/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package uk.xa0.tulkki.app.utils

import android.os.PowerManager
import android.util.Log
import uk.xa0.tulkki.xmpp.Config

/**
 * Acquires and releases a wake lock, swallowing the failures that used to crash the call service.
 *
 * <p>Both entries are static and both tolerate a `null` lock - the Java returned early and logged -
 * so the parameter is nullable here rather than non-null. An `object` rather than a class with a
 * companion: the Java had no declared constructor, so nothing could meaningfully construct it, and
 * `grep` finds no `new WakeLockHelper` anywhere. The two `@JvmStatic`s are what keep
 * `XmppTulkkiHost`'s calls spelled the same.
 */
object WakeLockHelper {

    @JvmStatic
    fun acquire(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) {
            Log.d(Config.LOGTAG, "could not acquire WakeLock. PowerManager was null")
            return
        }
        try {
            wakeLock.acquire(2000)
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "Could not acquire WakeLock", e)
        }
    }

    @JvmStatic
    fun release(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) {
            return
        }
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "unable to release wake lock", e)
        }
    }
}
