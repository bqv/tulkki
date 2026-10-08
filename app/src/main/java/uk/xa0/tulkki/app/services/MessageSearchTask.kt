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

package uk.xa0.tulkki.app.services

import android.os.SystemClock
import android.util.Log
import uk.xa0.tulkki.app.utils.Cancellable
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.messages.ConversationSnapshots
import uk.xa0.tulkki.data.messages.MessageSnapshots
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.IndividualMessage
import uk.xa0.tulkki.data.model.StubConversation
import uk.xa0.tulkki.data.utils.MessageUtils
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.ReplacingSerialSingleThreadExecutor

/**
 * Tulkki: one message search, run on a replacing single-thread executor, ported from Java by the
 * `applast` lane.
 *
 * <p>The threading and cancellation contract is the Java's, unchanged. [EXECUTOR] is the one static
 * executor and it *replaces* rather than queues: a second [search] drops whatever the first left
 * queued and cancels it if it is a [Cancellable] (which this task is), so the newest search is the
 * only one that runs. The in-flight body re-reads the plain, non-volatile `isCancelled` flag at the
 * same two points the Java does - once after the database read and once per hit - and a cancelled
 * run returns without calling the hook, exactly as before; the flag is deliberately *not* made
 * `@Volatile`, because Java's plain field is the behaviour being kept.
 *
 * <p>Interop surface kept for Java and Kotlin callers alike: [search] and [cancelRunningTasks] stay
 * `@JvmStatic` companion members, so the two spellings the tree uses -
 * `uk.xa0.tulkki.xmpp.services.MessageSearchPort`'s SAM and `MessageSearchTask.cancelRunningTasks()` in
 * `UiAppHost` - both resolve as they did.
 */
class MessageSearchTask
private constructor(
    private val xmppConnectionService: XmppConnectionService,
    private val term: List<String>,
    private val uuid: String?,
    private val onSearchResultsAvailable: uk.xa0.tulkki.xmpp.services.SearchResultsHook,
) : Runnable, Cancellable {

    private var isCancelled = false

    override fun cancel() {
        this.isCancelled = true
    }

    override fun run() {
        val startTimestamp = SystemClock.elapsedRealtime()
        try {
            val conversationCache = HashMap<String?, Conversational>()
            // Tulkki: 3.7 pair 9, part 16 - the continuation carries the island's refs now; the
            // objects added below are still `Message`s, so the widening is free.
            val result = ArrayList<MessageRef>()
            // S5-6: the search read is `:data`'s now. `MessageSnapshots.search` runs the production
            // statement (`DatabaseBackend.buildMessageSearchQuery`) through the DAO and answers read
            // models, so this task holds no `Cursor` and names no `DatabaseBackend`; the conversation a
            // stub needs comes from `ConversationSnapshots`, the read model, not from three extra
            // cursor columns.
            val context = xmppConnectionService.applicationContext
            val conversationSnapshots = ConversationSnapshots.get(context)
            val hits = MessageSnapshots.get(context).search(term, uuid)
            val dbTimer = SystemClock.elapsedRealtime()
            if (isCancelled) {
                Log.d(Config.LOGTAG, "canceled search task")
                return
            }
            // The cursor this replaces walked the statement's rows backwards (`moveToLast`, then
            // `moveToPrevious`), so its result was oldest first; the read model answers newest first.
            for (i in hits.size - 1 downTo 0) {
                if (isCancelled) {
                    Log.d(Config.LOGTAG, "canceled search task")
                    return
                }
                val hit = hits[i]
                val body = hit.bodies.original
                // Java's `hit.getOob() != null && hit.getOob() > 0`; `oob` is a `Long?` in another
                // module, so it is folded once here rather than smart-cast twice.
                val oob = (hit.oob ?: 0L) > 0L
                if (MessageUtils.treatAsDownloadable(body, oob)) {
                    continue
                }
                val conversationUuid = hit.conversationId
                val conversation =
                        conversationCache[conversationUuid]
                                ?: findOrGenerateStub(conversationUuid, conversationSnapshots).also {
                                    conversationCache[conversationUuid] = it
                                }
                val message = IndividualMessage.fromSnapshot(hit, conversation)
                if (message != null) {
                    result.add(message)
                }
            }
            val stopTimestamp = SystemClock.elapsedRealtime()
            Log.d(
                    Config.LOGTAG,
                    "found ${result.size} messages in ${stopTimestamp - startTimestamp}ms" +
                            " (db was ${dbTimer - startTimestamp}ms)")
            onSearchResultsAvailable.onSearchResultsAvailable(term, result)
        } catch (e: Exception) {
            Log.d(Config.LOGTAG, "exception while searching ", e)
            onSearchResultsAvailable.onSearchResultsAvailable(term, ArrayList())
        }
    }

    private fun findOrGenerateStub(
            conversationUuid: String?,
            conversationSnapshots: ConversationSnapshots,
    ): Conversational {
        val conversation =
                xmppConnectionService.findConversationByUuid(conversationUuid) as Conversation?
        if (conversation != null) {
            return conversation
        }
        // Java handed these nullable row fields straight to Kotlin parameters that are non-null and
        // guard themselves (`ConversationSnapshots.read(String)`, `AccountRegistry.findAccountByUuid`,
        // `Jid.of(CharSequence)`) - a `null` was that parameter's own `NullPointerException`. The three
        // explicit guards keep the same failure rather than buying a `!!`; the fields a stored row
        // carries are non-null, so this is Java's reachable-on-corruption shape, not a live branch.
        val uuid = conversationUuid ?: throw NullPointerException()
        val row = conversationSnapshots.read(uuid)
        if (row == null) {
            throw Exception("Unable to generate stub for $conversationUuid")
        }
        val account =
                AccountRegistry.get().findAccountByUuid(row.accountId ?: throw NullPointerException())
        val jid: Jid? = Jid.of(row.jid ?: throw NullPointerException())
        if (account != null && jid != null) {
            val mode = row.mode
            return StubConversation(
                    account,
                    uuid,
                    jid.asBareJid(),
                    if (mode == null) Conversation.MODE_SINGLE else mode.toInt())
        }
        throw Exception("Unable to generate stub for ${row.jid}")
    }

    private fun executeInBackground() {
        EXECUTOR.execute(this)
    }

    companion object {

        private val EXECUTOR =
                ReplacingSerialSingleThreadExecutor(MessageSearchTask::class.java.name)

        @JvmStatic
        fun search(
                xmppConnectionService: XmppConnectionService,
                term: List<String>,
                uuid: String?,
                onSearchResultsAvailable: uk.xa0.tulkki.xmpp.services.SearchResultsHook,
        ) {
            MessageSearchTask(xmppConnectionService, term, uuid, onSearchResultsAvailable)
                    .executeInBackground()
        }

        @JvmStatic
        fun cancelRunningTasks() {
            EXECUTOR.cancelRunningTasks()
        }
    }
}
