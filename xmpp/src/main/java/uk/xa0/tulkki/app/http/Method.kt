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

package uk.xa0.tulkki.app.http

import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Which upload shape an account's server offers.
 *
 * Ported from `Method.java`. It is *ours*, so it is converted
 * in place. The three Java callers read the constants as enum identities (`SlotRequester` compares
 * `method == Method.HTTP_UPLOAD_LEGACY`) and `determine` as a static, so the constants keep their
 * declaration order and `determine` keeps its `@JvmStatic` bridge in the companion. The Java's
 * `httpUpload(0)` widens an `int` literal to the `long` parameter; Kotlin does not widen, so the
 * literal is spelled `0L` - the same argument, the same call.
 */
enum class Method {
    HTTP_UPLOAD,
    HTTP_UPLOAD_LEGACY;

    companion object {
        @JvmStatic
        fun determine(account: AccountRef): Method {
            val features: XmppConnection.Features? = account.getXmppConnection()?.getFeatures()
            if (features == null) {
                return HTTP_UPLOAD
            }
            return if (features.useLegacyHttpUpload()) {
                HTTP_UPLOAD_LEGACY
            } else if (features.httpUpload(0L)) {
                HTTP_UPLOAD
            } else {
                HTTP_UPLOAD
            }
        }
    }
}
