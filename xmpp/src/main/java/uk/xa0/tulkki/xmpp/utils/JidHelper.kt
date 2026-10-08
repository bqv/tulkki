/*
 * Copyright (c) 2017, Daniel Gultsch All rights reserved.
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

import java.util.Arrays
import java.util.Locale
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid

/**
 * The two JID-derived names the app shows: a local part that falls back to the domain, and the
 * Quicksy-domain test.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`localPartOrFallback` answers non-null `String`.** The Java body dereferenced
 *    `jid.getLocal()` *before* the blacklist test (`…contains(jid.getLocal().toLowerCase(…)`), so a
 *    domain-only JID threw a `NullPointerException` there rather than returning null; the method
 *    never returned null on any path that completed. The Kotlin signature says so, and the
 *    `:data` callers that need it non-null (`MucOptions.defaultNick` returns `String`) compile
 *    unchanged. The two `Contact.kt` sites that guard with `getJid().getLocal() != null` are
 *    consistent with that reading.
 * 2. **`toLowerCase(Locale.ENGLISH)` is `lowercase(Locale.ENGLISH)`**, the same
 *    `String.toLowerCase(Locale)` call without the deprecated Kotlin spelling.
 * 3. **`isQuicksyDomain` keeps Java's null guard** even though [Config.QUICKSY_DOMAIN] is a
 *    `static final` the initialiser fills: the guard is a line of the Java body, and the constant is
 *    a platform type to Kotlin.
 * 4. **Both methods are `@JvmStatic`**, with Java callers in `:data` (`DisplayNames.java:84`) and
 *    Kotlin callers in `:data`.
 */
class JidHelper {

    companion object {

        private val LOCAL_PART_BLACKLIST: List<String> =
            Arrays.asList("xmpp", "jabber", "me")

        @JvmStatic
        fun localPartOrFallback(jid: Jid): String {
            val local = jid.getLocal() ?: throw NullPointerException()
            if (LOCAL_PART_BLACKLIST.contains(local.lowercase(Locale.ENGLISH))) {
                val domain = jid.getDomain().toString()
                val index = domain.indexOf('.')
                return if (index > 1) domain.substring(0, index) else domain
            } else {
                return local
            }
        }

        @JvmStatic
        fun isQuicksyDomain(jid: Jid): Boolean =
            Config.QUICKSY_DOMAIN != null && Config.QUICKSY_DOMAIN.equals(jid.getDomain())
    }
}
