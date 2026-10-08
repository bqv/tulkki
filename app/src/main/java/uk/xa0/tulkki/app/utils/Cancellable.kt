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

/**
 * Kept for the one `:app` implementer, `MessageSearchTask`, which the island's
 * `SerialSingleThreadExecutor` stops through an `instanceof`.
 *
 * <p>3.7 pair 4. The contract itself is
 * [uk.xa0.tulkki.xmpp.utils.SerialSingleThreadExecutor.Cancellable] now, because an island file
 * naming this interface was a `:xmpp` -> `:app` site; this is the `:app`-facing spelling
 * of the same contract, so `MessageSearchTask` and its callers do not have to name the island.
 *
 * <p>The port keeps the two-type shape exactly: this is still a *sub*interface of the island's, and
 * the redeclared `cancel()` is still an abstract override rather than an inheritance, so
 * `MessageSearchTask`'s `implements uk.xa0.tulkki.app.utils.Cancellable` is unchanged even though
 * the file it names is now Kotlin.
 */
interface Cancellable : uk.xa0.tulkki.xmpp.utils.SerialSingleThreadExecutor.Cancellable {
    override fun cancel()
}
