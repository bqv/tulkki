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

import com.google.common.collect.ImmutableMap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.function.Consumer
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import uk.xa0.tulkki.parser.AbstractParser
import uk.xa0.tulkki.parser.IqParser
import uk.xa0.tulkki.xmpp.IqResponseException
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.libs.DownloadableFileRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * Tulkki: asks an account's server for an HTTP upload slot and turns the answer into a [Slot].
 *
 * Ported from `SlotRequester.java`. It is *ours*, so it is converted in place; every method keeps the
 * descriptor the Java had.
 *
 * The Java-visible surface, read off the one caller (`HttpUploadConnection.java`):
 *
 *  * `Slot`'s three members are read as **fields** there (`slot.put`, `slot.get`,
 *    `slot.headers`), so they stay `@JvmField`s and generate no getters. `get` could not carry one
 *    anyway - the accessor would be `getGet()` - so the field is also the only faithful shape.
 *  * `Slot`'s constructor was `private`, called from the enclosing class. Kotlin has no Java's
 *    outer-class access to a nested class's private members (measured: `cannot access
 *    'constructor(a: Int)': it is private in 'Outer.Inner'`), so it becomes `internal` - public in
 *    the bytecode, and no Java caller constructs a `Slot` either way. The private
 *    `Slot(HttpUrl, HttpUrl, Map)` overload is dropped and its one call site writes
 *    `Headers.of(map)`, which is the same call the constructor made.
 *  * `name` and `mime` are **nullable**: `IqGenerator.requestHttpUploadSlot` reads
 *    `name == null ? convertFilename(...) : name`, and `mime == null` already picks
 *    `application/octet-stream` here. `host` is nullable too - `findDiscoItemByFeature` answers null
 *    when the server advertises no such feature and the Java never checked it.
 *  * `HttpUrl.get(String)` is deprecated; `String.toHttpUrl()` is its Kotlin spelling and throws the
 *    same `IllegalArgumentException` the `try`/`catch` exists for. `toHttpUrlOrNull()` would answer
 *    null instead and change which branch reports the failure. `Headers.of` is the same kind of
 *    name: okhttp 4 deprecates it at `DeprecationLevel.ERROR`, so the Kotlin reads `headersOf(...)`
 *    and `map.toHeaders()` - the calls it always made, found by leg 1 rather than by inspection.
 *  * the third `sendIqPacket` argument is a `java.util.function.Consumer<Iq>`, so the Java lambda
 *    becomes a SAM-constructed `Consumer` and its early `return;`s become `return@Consumer`.
 */
class SlotRequester(private val service: XmppConnectionService) {

    fun request(
        method: Method,
        account: AccountRef,
        file: DownloadableFileRef,
        name: String?,
        mime: String?,
    ): ListenableFuture<Slot> =
        if (method == Method.HTTP_UPLOAD_LEGACY) {
            requestHttpUploadLegacy(
                account,
                (account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).findDiscoItemByFeature(Namespace.HTTP_UPLOAD_LEGACY),
                file,
                mime,
            )
        } else {
            requestHttpUpload(
                account,
                (account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).findDiscoItemByFeature(Namespace.HTTP_UPLOAD),
                file,
                name,
                mime,
            )
        }

    private fun requestHttpUploadLegacy(
        account: AccountRef,
        host: Jid?,
        file: DownloadableFileRef,
        mime: String?,
    ): ListenableFuture<Slot> {
        val future = SettableFuture.create<Slot>()
        val request = service.getIqGenerator().requestHttpUploadLegacySlot(host, file, mime)
        service.sendIqPacket(account, request, Consumer { packet ->
            if (packet.getType() == Iq.Type.RESULT) {
                val slotElement = packet.findChild("slot", Namespace.HTTP_UPLOAD_LEGACY)
                if (slotElement != null) {
                    try {
                        val putUrl = slotElement.findChildContent("put")
                        val getUrl = slotElement.findChildContent("get")
                        if (getUrl != null && putUrl != null) {
                            val slot = Slot(
                                putUrl.toHttpUrl(),
                                getUrl.toHttpUrl(),
                                headersOf("Content-Type", mime ?: "application/octet-stream"),
                            )
                            future.set(slot)
                            return@Consumer
                        }
                    } catch (e: IllegalArgumentException) {
                        future.setException(e)
                        return@Consumer
                    }
                }
            }
            future.setException(IqResponseException(AbstractParser.extractErrorMessage(packet)))
        })
        return future
    }

    private fun requestHttpUpload(
        account: AccountRef,
        host: Jid?,
        file: DownloadableFileRef,
        fname: String?,
        mime: String?,
    ): ListenableFuture<Slot> {
        val future = SettableFuture.create<Slot>()
        val request = service.getIqGenerator().requestHttpUploadSlot(host, file, fname, mime)
        service.sendIqPacket(account, request, Consumer { packet ->
            if (packet.getType() == Iq.Type.RESULT) {
                val slotElement = packet.findChild("slot", Namespace.HTTP_UPLOAD)
                if (slotElement != null) {
                    try {
                        val put = slotElement.findChild("put")
                        val get = slotElement.findChild("get")
                        val putUrl = put?.getAttribute("url")
                        val getUrl = get?.getAttribute("url")
                        // putUrl != null already implies put != null; the extra test is what lets
                        // Kotlin smart-cast `put` for the loop below without `!!`.
                        if (put != null && get != null && putUrl != null && getUrl != null) {
                            val headers = ImmutableMap.builder<String, String>()
                            for (child in put.getChildren()) {
                                if ("header" == child.getName()) {
                                    val name = child.getAttribute("name")
                                    val value = child.getContent()
                                    if (name != null
                                        && HttpUploadConnection.WHITE_LISTED_HEADERS.contains(name)
                                        && value != null
                                        && !value.trim().contains("\n")
                                    ) {
                                        headers.put(name, value.trim())
                                    }
                                }
                            }
                            headers.put("Content-Type", mime ?: "application/octet-stream")
                            val slot = Slot(putUrl.toHttpUrl(), getUrl.toHttpUrl(), headers.build().toHeaders())
                            future.set(slot)
                            return@Consumer
                        }
                    } catch (e: IllegalArgumentException) {
                        future.setException(e)
                        return@Consumer
                    }
                }
            }
            future.setException(IqResponseException(AbstractParser.extractErrorMessage(packet)))
        })
        return future
    }

    class Slot internal constructor(
        @JvmField val put: HttpUrl,
        @JvmField val get: HttpUrl,
        @JvmField val headers: Headers,
    )
}
