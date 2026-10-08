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

package uk.xa0.tulkki.ui

import android.app.Activity
import android.app.Fragment

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

import uk.xa0.tulkki.ui.interfaces.OnBackendConnected
import uk.xa0.tulkki.libs.Jid

abstract class XmppFragment : Fragment(), OnBackendConnected, LifecycleOwner {

    // The Java field was named `lifecycle`; the property cannot be, because it would generate
    // `getLifecycle()` beside the override below. No outside file names the field, so it is private
    // and renamed, and `getLifecycle()` is the only way in.
    private val registry: LifecycleRegistry = LifecycleRegistry(this)

    public abstract fun refresh()

    // `MutableSet`, not `Set`: Kotlin's covariant `Set` would erase to `Set<? extends Jid>`, which
    // ConversationFragment's Java `refreshForNewCaps(Set<Jid>)` does not override.
    open fun refreshForNewCaps(newCapsJids: MutableSet<Jid>) {}

    protected fun runOnUiThread(runnable: Runnable) {
        val host: Activity? = activity
        if (host != null) {
            host.runOnUiThread(runnable)
        }
    }

    override fun onStart() {
        super.onStart()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            try {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            } catch (e: IllegalStateException) {
            }
        }
        super.onDestroy()
    }

    // `LifecycleOwner.getLifecycle()` is a Java getter, so Kotlin's override of it is the property.
    override val lifecycle: Lifecycle
        get() = registry
}
