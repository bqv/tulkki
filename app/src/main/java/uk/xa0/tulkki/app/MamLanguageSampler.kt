package uk.xa0.tulkki.app

import android.util.Log
import java.math.BigInteger
import java.util.ArrayList
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import uk.xa0.tulkki.app.generator.AbstractGenerator
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.ConversationName
import uk.xa0.tulkki.translation.LanguageSample
import uk.xa0.tulkki.translation.TranslationDecision
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.translation.TranslationStore
import uk.xa0.tulkki.translation.TulkkiMainThread
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.models.forward.Forwarded
import uk.xa0.tulkki.xmpp.models.reactions.Reactions
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.models.stanza.Message as StanzaMessage
import uk.xa0.tulkki.xmpp.services.MessageArchiveService
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.Random
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The wire half of the MAM language sample: one bounded, silent archive query per conversation, read
 * for detection and dropped.
 *
 * <p>[LanguageSample] holds the rule; this holds the plumbing, and the plumbing is where the trap is.
 * Upstream's {@code MessageArchiveService} is the catch-up path: it inserts what the archive hands
 * back into conversations, and that is exactly what this feature must never do. So the sample does not
 * go through it. It builds and sends its own MAM query and reads the result stanzas itself, before
 * upstream's message parser can recognise them - the details are in [offer].
 *
 * <h2>What this never does</h2>
 *
 * <ul>
 *   <li><b>Silent.</b> No UI, no notification, no badge, no read receipt, no chat marker, no typing
 *       indicator, no {@code <fin>}-driven catch-up. Nothing appears in the conversation. Every
 *       failure path is a {@code return}: a sample that cannot be taken is not a problem the owner
 *       should ever hear about.
 *   <li><b>Never stored, never shown.</b> The result stanzas are consumed and converted to
 *       [TranslationDecision.Candidate]s that live in a list in memory until the verdict is read,
 *       and are then dropped. No {@code Message} row is created, {@code conversation.add} is never
 *       called, no {@code translated_body} / {@code translation_lang} / {@code translation_state} is
 *       written and nothing enters the translation cache. The only thing that outlives a sample is
 *       the one language code it may write to {@code conversations.detected_language}, through the
 *       same writer the live path uses.
 *   <li><b>Never decrypted.</b> A ciphertext archived message is skipped, so an OMEMO or PGP
 *       conversation simply yields no verdict. See {@code LanguageSample.isEligible}.
 *   <li><b>Zero DeepSeek traffic.</b> Detection is {@code TextLanguage}, local and free. Nothing in
 *       this class or in [LanguageSample] can construct a client or read a key.
 *   <li><b>Never an overwrite.</b> Nothing is asked unless the language is genuinely unknown, and the
 *       two stored values are re-read before anything is written, because the query is asynchronous
 *       and the owner may have set a language - or a live message may have established one - while it
 *       was in flight.
 *   <li><b>Once, and bounded.</b> One sample per conversation per run, a global ceiling per run, one
 *       query, one page, and no pagination under any circumstance.
 * </ul>
 *
 * <h2>1:1 only, for now</h2>
 *
 * The trigger refuses anything that is not a {@code MODE_SINGLE} conversation. For a 1:1 the query is
 * {@code with=<peer>} and every archived message is either theirs or ours, which makes the
 * inbound-only rule a comparison of one bare JID. A MUC would need the same treatment through
 * {@code Version.get(account, conversation)} and a different answer to "whose message is this"
 * (occupant ids, the room's JID as the {@code from}), and none of that has been proven on a device.
 * Leaving MUC out is deliberate: a wrong answer there would be the same wrong-language bug, in a room
 * where the owner cannot correct one person's language.
 *
 * <p>Both entry points stay companion `@JvmStatic`s: `UiAppHost` calls `consider` and the island's
 * parser calls `offer`. `Pending` is a nested class of the companion, which is the home the Java's
 * `private static final class` had.
 */
class MamLanguageSampler private constructor() {

    companion object {

        private const val TAG = "Tulkki"

        /** How long a sample waits for its {@code <fin>} before giving up quietly. */
        private const val TIMEOUT_MILLIS = 20_000L

        /** The queries this app has sent and not yet finished, keyed by their query id. */
        private val PENDING = ConcurrentHashMap<String, Pending>()

        /** The chats already sampled in this run, and how many samples the run has started. */
        private val SAMPLED = HashSet<String>()

        private var started = 0

        /** One outstanding query: the conversation it is for, and the candidates read so far. */
        private class Pending(
                val service: XmppConnectionService,
                val conversationUuid: String,
                val account: Account,
                val namespace: String,
                /**
                 * The name the interface shows for this conversation, read once when the sample is
                 * asked for. It is what says a sampled body that is exactly the room's own name is a
                 * bare name and not a reading; a null here just turns that half of the rule off.
                 */
                val conversationName: String?,
                val onLanguageLanded: Runnable?) {

            val candidates = ArrayList<TranslationDecision.Candidate>()

            /**
             * How many result stanzas this sample has read. Bounded on this side too, because the
             * bound must not depend on the server honouring {@code max}: a server that ignores result
             * set management would otherwise stream its whole archive at us.
             */
            var read = 0
        }

        /**
         * The trigger. Called when a conversation is opened; the whole method is a list of reasons not
         * to ask, and the only thing it does when none of them applies is send one query.
         *
         * <p>It is deliberately cheap and deliberately quiet on every refusal: opening a conversation
         * must not become a place where the app talks to the network about anything other than what the
         * owner is looking at, and it must not log a line per conversation per resume.
         *
         * @param onLanguageLanded run on the main thread, once, only if the sample named a language and
         *     wrote it. It exists so the surface that already shows the conversation's language can
         *     redraw itself; this class never touches the interface, and nothing new is shown because of
         *     it. It may be {@code null}.
         */
        @JvmStatic
        fun consider(
                service: XmppConnectionService?,
                conversation: Conversation?,
                onLanguageLanded: Runnable?) {
            try {
                if (service == null || conversation == null) {
                    return
                }
                // A 1:1 only. See the class comment: the MUC path is not proven and is left out.
                if (conversation.getMode() != Conversation.MODE_SINGLE) {
                    return
                }
                if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                    return
                }
                // The one question this feature answers. An established detection or an override means
                // there is nothing to learn, and asking anyway would be a query for its own sake. The
                // interpreter travels with it, and off that same call answers no before either stored
                // value is read - which is the whole of this site's off behaviour: the return is above
                // the PENDING entry, the SAMPLED bookkeeping, the query and the send, so a plain client
                // sends nothing to the archive and learns nothing about a conversation's language.
                if (!LanguageSample.shouldSample(
                        conversation.getDetectedLanguage(),
                        conversation.getLanguageOverride(),
                        TranslationSettings.get(service).interpreter())) {
                    return
                }
                val account = conversation.getAccount()
                if (account == null || account.getStatus() != Account.State.ONLINE) {
                    return
                }
                val connection = account.getXmppConnection()
                if (connection == null || !connection.getFeatures().mam()) {
                    // No archive on this account: the conversation stays unknown and the picker is the
                    // way out, exactly as before.
                    return
                }
                // Two archive queries for one conversation at the same time would be one too many, and
                // upstream's is the one that matters. The next open of this conversation can try again.
                if (service.getMessageArchiveService().isCatchupInProgress(conversation)) {
                    return
                }
                // port-5: `Conversational.getUuid()` is nullable; a conversation with no uuid has no
                // sample identity to record or key the pending query on, so it is skipped.
                val conversationUuid = conversation.getUuid() ?: return
                synchronized(SAMPLED) {
                    if (SAMPLED.contains(conversationUuid) || started >= LanguageSample.MAX_PER_RUN) {
                        return
                    }
                    SAMPLED.add(conversationUuid)
                    started++
                }
                val queryId = BigInteger(50, Random.SECURE_RANDOM).toString(32)
                val version = MessageArchiveService.Version.get(account)
                PENDING.put(
                        queryId,
                        Pending(
                                service,
                                conversationUuid,
                                account,
                                version.namespace,
                                ConversationName.of(conversation),
                                onLanguageLanded))
                val packet = query(conversation, queryId, version, System.currentTimeMillis())
                service.sendIqPacket(
                        account, packet, { response -> finish(queryId, response) }, TIMEOUT_MILLIS)
                Log.d(
                        TAG,
                        "asked the archive for a language sample (up to " +
                                LanguageSample.MAX_MESSAGES +
                                " messages); it is read and dropped")
            } catch (e: RuntimeException) {
                // A sample is never worth an exception reaching the interface.
                Log.d(TAG, "could not ask for a language sample", e)
            }
        }

        /**
         * Takes one MAM result stanza if it belongs to a sample, and reports whether it did.
         *
         * <p>This is the lowest-level seam in the whole feature. A MAM query is an IQ carrying a
         * {@code queryid}, and the archived messages come back as separate {@code <message>} stanzas
         * that name that id in their {@code <result>} element. Upstream's message parser correlates
         * them by looking the id up in {@code MessageArchiveService}'s own set of queries and then
         * inserts each forwarded message into a conversation - {@code conversation.add(message)} and a
         * notification with it. The sample's ids are never in that set, so upstream has nothing to
         * insert, and this method consumes the stanzas anyway so the question never arises: it is
         * called from the top of the parser, before the lookup, and returning {@code true} ends the
         * parse for that stanza.
         *
         * <p>A result stanza left unconsumed is harmless - it is a wrapper with no body, and every
         * insertion path in the parser is behind a "has a body, a payload or a file" guard, so a stray
         * one is skipped rather than stored - but "harmless by accident of a guard elsewhere" is not a
         * property to rely on. Consuming it here is what makes the guarantee ours.
         *
         * <p>Reads one message into memory. Nothing is written.
         *
         * @param queryId the {@code queryid} of the stanza's {@code <result>}, or {@code null}
         * @param stanza the raw {@code <message>} the parser received
         * @return true when this stanza was a sample's and has been read here
         */
        @JvmStatic
        fun offer(queryId: String?, stanza: StanzaMessage): Boolean {
            if (queryId == null || PENDING.isEmpty()) {
                return false
            }
            val pending = PENDING[queryId] ?: return false
            try {
                synchronized(pending) {
                    if (pending.read >= LanguageSample.MAX_MESSAGES) {
                        // The bound holds even when the server ignores `max`. The stanza is still
                        // consumed here - it is ours - but it is not read and not kept.
                        return true
                    }
                    pending.read++
                    // Qualified: the local below would otherwise read the function back.
                    val candidate = MamLanguageSampler.candidate(pending, stanza)
                    if (candidate != null) {
                        pending.candidates.add(candidate)
                    }
                }
            } catch (e: RuntimeException) {
                // A stanza we cannot read is a stanza we do not read. The sample simply has less to go
                // on.
                Log.d(TAG, "a sampled archive message could not be read", e)
            }
            return true
        }

        /**
         * The verdict, and then nothing. The candidates are dropped with the [Pending] that held them,
         * so the sampled text does not outlive this call.
         */
        private fun finish(queryId: String, response: Iq?) {
            val pending = PENDING.remove(queryId) ?: return
            try {
                if (response == null || response.getType() != Iq.Type.RESULT) {
                    // Timed out, refused, or an error. Quietly: there is nothing to tell anyone.
                    return
                }
                if (response.findChild("fin", pending.namespace) == null) {
                    return
                }
                val candidates =
                        synchronized(pending) { ArrayList(pending.candidates) }
                val settings = TranslationSettings.get(pending.service)
                val language =
                        LanguageSample.read(candidates, settings.appLanguage(), settings.interpreter())
                if (language == null) {
                    // No majority, or nothing readable in the window. Leaving the conversation unknown
                    // is the correct answer, not a failure: the live path still learns it later.
                    return
                }
                record(pending, language)
            } catch (e: RuntimeException) {
                Log.d(TAG, "could not finish a language sample", e)
            }
        }

        /**
         * Writes the one thing a sample is allowed to write, and only into a gap.
         *
         * <p>Re-reads both stored values first. A sample is asynchronous, and the owner setting a
         * language - or a live message establishing one - while the query was in flight must win: the
         * archive is old news, and an override is the owner's own answer.
         */
        private fun record(pending: Pending, language: String) {
            val service = pending.service
            val conversation =
                    service.findConversationByUuid(pending.conversationUuid) as? Conversation ?: return
            if (!LanguageSample.shouldSample(
                    conversation.getDetectedLanguage(),
                    conversation.getLanguageOverride(),
                    TranslationSettings.get(service).interpreter())) {
                return
            }
            TranslationStore(service)
                    .recordConversationLanguage(pending.conversationUuid, language)
            Log.d(
                    TAG,
                    "the language sample named the conversation's language (" +
                            language +
                            "); the sampled messages were never stored")
            // The interface already knows how to draw a known language; it just has to be told that
            // the value it read on resume has changed underneath it.
            post(pending.onLanguageLanded)
        }

        /**
         * Runs a caller's refresh on the main thread, and never lets it take the connection down.
         *
         * <p>The posting itself is [TulkkiMainThread], the one poster in the app. What stays here is
         * this caller's own delivery policy: a refresh that throws must not take the connection down,
         * and one that cannot be posted at all is logged rather than propagated - both are decisions
         * about this listener, not about posting.
         */
        private fun post(runnable: Runnable?) {
            if (runnable == null) {
                return
            }
            try {
                TulkkiMainThread.post {
                    try {
                        runnable.run()
                    } catch (e: RuntimeException) {
                        Log.d(TAG, "a language sample's refresh failed", e)
                    }
                }
            } catch (e: RuntimeException) {
                Log.d(TAG, "a language sample's refresh could not be posted", e)
            }
        }

        /**
         * One archived message as [LanguageSample] understands it.
         *
         * <p>Every field is read from the forwarded stanza the server handed back, and each check is a
         * reason not to sample rather than a reason to. The inbound rule is the first: an archived
         * message whose sender is our own bare JID is the owner's own text in the app language.
         */
        private fun candidate(
                pending: Pending,
                stanza: StanzaMessage): TranslationDecision.Candidate? {
            val result = MessageArchiveService.Version.findResult(stanza) ?: return null
            val forwarded = result.findChild("forwarded", Namespace.FORWARD) as? Forwarded ?: return null
            val message = forwarded.getMessage() ?: return null
            val candidate = TranslationDecision.Candidate()
            candidate.conversationName = pending.conversationName
            val from = message.getFrom()
            candidate.received =
                    from != null && from.asBareJid() != pending.account.getJid().asBareJid()
            candidate.deleted =
                    message.findChild("retract", "urn:xmpp:message-retract:0") != null ||
                            message.findChild("retract", "urn:xmpp:message-retract:1") != null
            candidate.hasReplacement =
                    message.findChild("replace", "urn:xmpp:message-correct:0") != null
            candidate.hasReactions = message.getExtension(Reactions::class.java) != null
            candidate.hasFileParams =
                    message.findChild("x", Namespace.OOB) != null ||
                            message.findChild("reference", "urn:xmpp:reference:0") != null
            // The encrypted-conversation limitation, applied at the source: anything carrying an
            // encryption payload is ciphertext here, and a sample is never decrypted. An OMEMO message
            // can also carry a fallback body, which is not the text either, so the encryption element -
            // not the presence of a body - is what decides this.
            val encrypted =
                    message.getOnlyExtension(uk.xa0.tulkki.xmpp.models.pgp.Encrypted::class.java) != null ||
                            message.getOnlyExtension(
                                    uk.xa0.tulkki.xmpp.models.axolotl.Encrypted::class.java) != null
            candidate.encryption = if (encrypted) Message.ENCRYPTION_AXOLOTL else Message.ENCRYPTION_NONE
            val body = message.getBody()
            candidate.body = body?.content
            return candidate
        }

        /**
         * The query. Built here rather than through {@code IqGenerator}, because that method takes a
         * {@code MessageArchiveService.Query} - and holding one of those is precisely what makes
         * upstream insert the results. What matters is only that the shape matches XEP-0313 and
         * upstream's own query: a {@code set} query in the account's MAM namespace, a data form naming
         * the peer, a start bound, and result set management asking for the last page and no more.
         */
        private fun query(
                conversation: Conversation,
                queryId: String,
                version: MessageArchiveService.Version,
                now: Long): Iq {
            val packet = Iq(Iq.Type.SET)
            val query = packet.query(version.namespace)
            query.setAttribute("queryid", queryId)
            val data = Data()
            data.setFormType(version.namespace)
            data.put("with", conversation.getJid()!!.asBareJid().toString())
            data.put("start", AbstractGenerator.getTimestamp(LanguageSample.windowStart(now)))
            data.submit()
            query.addChild(data)
            val set = query.addChild("set", Namespace.RESULT_SET_MANAGEMENT)
            // An empty <before/> asks for the page immediately preceding the end of the archive: the
            // most recent messages, which is what "what does this person write now" means. `max` is the
            // hard bound, and the empty `after` the sampler never sends is the promise that there is no
            // second page.
            set.addChild("before").setContent(null)
            set.addChild("max").setContent(LanguageSample.MAX_MESSAGES.toString())
            return packet
        }
    }
}
