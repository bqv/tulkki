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

package uk.xa0.tulkki.xmpp.utils

import java.util.HashMap
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * One replacing executor per account, created on first use.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **The map is a plain `HashMap` locked on itself**, `synchronized (this.executors)`, exactly as
 *    Java wrote it; the lock object is unchanged.
 * 2. **The executor's name is `ReplacingTaskManager::class.java.simpleName`**, which is the same
 *    `Class.getSimpleName()` string Java passed.
 * 3. **`account` is non-null.** The one caller passes `account` from
 *    `XmppConnectionService:3797` and `:4610`, and a null key would only ever have been a bug the
 *    Kotlin parameter catches earlier.
 * 4. **The `if (executor == null)` shape is kept**, so the executor is created and stored before the
 *    task is handed over, as in Java; a `getOrPut` would have been the same thing spelled shorter
 *    but less recognisably.
 */
class ReplacingTaskManager {

    private val executors: HashMap<AccountRef, ReplacingSerialSingleThreadExecutor> = HashMap()

    fun execute(account: AccountRef, runnable: Runnable) {
        synchronized(this.executors) {
            var executor = this.executors[account]
            if (executor == null) {
                executor = ReplacingSerialSingleThreadExecutor(ReplacingTaskManager::class.java.simpleName)
                this.executors[account] = executor
            }
            executor.execute(runnable)
        }
    }

    fun clear(account: AccountRef) {
        synchronized(this.executors) {
            this.executors.remove(account)
        }
    }
}
