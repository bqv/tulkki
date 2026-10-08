package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.mam.ReceiptRequest
import uk.xa0.tulkki.xmpp.mam.SyncAnchors
import uk.xa0.tulkki.xmpp.mam.SyncEvents
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnAdvancedStreamFeaturesLoaded
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.utils.Random
import java.math.BigInteger

/**
 * MAM: the catch-up at stream features, paging, `<fin>` handling and postponed receipts.
 *
 * <p><strong>Why a shell at all.</strong> The callers are Java in other packages -
 * [XmppConnectionService], `MessageParser`, `IqParser`, `MamLanguageSampler`, `MessageAdapter`,
 * `ConversationFragment` - and the file protocol gives this lane the `services` package only, so
 * the surface they compile against is frozen. The class keeps the two query collections, the two
 * sync seams and the Java-facing forwarders, plus the three protocol types whose Java names must
 * stay nested ([Version], [PagingOrder], [Query]). Every other responsibility has moved to its own
 * class: [MamCatchup] (when a pass is earned and how far back it reaches), [MamPaging] (one query's
 * life from minting to `<fin>` to kill) and [MamPostponedReceipts] (the receipt flush a query owes).
 *
 * <p>Two Kotlin traps the Java original does not share, both kept deliberately: every `==` it
 * wrote on an [AccountRef] or a [ConversationRef] was reference identity, so every one is `===`
 * here; and [Version.MAM_0]'s namespace is a public field, not a getter, because `MessageParser`
 * reads `.namespace` and Java does not synthesise that from a Kotlin `val`.
 */
class MessageArchiveService internal constructor(
        internal val mXmppConnectionService: XmppConnectionService
) : OnAdvancedStreamFeaturesLoaded {

    internal val queries = HashSet<Query>()

    internal val pendingQueries = ArrayList<Query>()

    /**
     * Tulkki: the sync engine's two island seams, installed by the composition root. `SyncEvents` is
     * what the engine learns from - a session, a `<fin>`, an abort, a stored message, CSI. `SyncAnchors`
     * is what this service asks instead of reading the database for a derivation.
     */
    internal var syncEvents: SyncEvents? = null

    internal var syncAnchors: SyncAnchors? = null

    private val paging = MamPaging(this)

    private val catchup = MamCatchup(this, paging)

    fun installSyncEvents(events: SyncEvents) {
        this.syncEvents = events
    }

    fun installSyncAnchors(anchors: SyncAnchors) {
        this.syncAnchors = anchors
    }

    fun catchupMUC(conversation: ConversationRef) = catchup.catchupMUC(conversation)

    fun query(conversation: ConversationRef): Query? = paging.query(conversation)

    fun isCatchingUp(conversation: ConversationRef): Boolean = catchup.isCatchingUp(conversation)

    fun query(conversation: ConversationRef, end: Long, allowCatchup: Boolean): Query? =
            paging.query(conversation, end, allowCatchup)

    fun query(
            conversation: ConversationRef,
            start: MamReference,
            end: Long,
            allowCatchup: Boolean
    ): Query? = paging.query(conversation, start, end, allowCatchup)

    fun executePendingQueries(account: AccountRef) = paging.executePendingQueries(account)

    fun inCatchup(account: AccountRef): Boolean = catchup.inCatchup(account)

    fun isCatchupInProgress(conversation: ConversationRef): Boolean =
            catchup.isCatchupInProgress(conversation)

    fun queryInProgress(
            conversation: ConversationRef,
            callback: OnMoreMessagesLoaded?
    ): Boolean = paging.queryInProgress(conversation, callback)

    fun queryInProgress(conversation: ConversationRef): Boolean =
            paging.queryInProgress(conversation, null)

    fun processFinLegacy(fin: Element, from: Jid) = paging.processFinLegacy(fin, from)

    fun kill(conversation: ConversationRef) = paging.kill(conversation)

    fun findQuery(id: String?): Query? = paging.findQuery(id)

    override fun onAdvancedStreamFeaturesAvailable(account: AccountRef) =
            catchup.onAdvancedStreamFeaturesAvailable(account)

    /**
     * The MAM protocol version this server speaks, and the feature probes that answer it.
     *
     * <p>`legacy` and `namespace` are `@JvmField`s because Java reads the fields directly
     * (`MessageParser` at `.namespace`); a Kotlin `val` would expose only `getNamespace()`.
     */
    enum class Version(@JvmField val namespace: String, @JvmField val legacy: Boolean) {
        MAM_0("urn:xmpp:mam:0", true),
        MAM_1("urn:xmpp:mam:1", false),
        MAM_2("urn:xmpp:mam:2", false);

        companion object {

            /**
             * Tulkki: 3.7 C5-D - the account-wide form, the ref-typed half of the pair. It stays a real
             * one-argument overload: `:app`'s `MamLanguageSampler` and `XmppConnectionService.fetchMamPreferences`
             * both ask it with a single argument, and [Query]'s own constructor does too.
             */
            @JvmStatic
            fun get(account: AccountRef): Version = get(account, null)

            @JvmStatic
            fun get(account: AccountRef, conversation: ConversationRef?): Version =
                    if (conversation == null || conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                        get((account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).getFeatures().getAccountFeatures())
                    } else {
                        get(conversation.getMucOptions().getFeatures())
                    }

            private fun get(features: List<String>): Version {
                val values = values()
                for (i in values.indices.reversed()) {
                    for (feature in features) {
                        if (values[i].namespace == feature) {
                            return values[i]
                        }
                    }
                }
                return MAM_0
            }

            @JvmStatic
            fun has(features: List<String>): Boolean {
                for (feature in features) {
                    for (version in values()) {
                        if (version.namespace == feature) {
                            return true
                        }
                    }
                }
                return false
            }

            @JvmStatic
            fun findResult(packet: Message): Element? {
                for (version in values()) {
                    val result = packet.findChild("result", version.namespace)
                    if (result != null) {
                        return result
                    }
                }
                return null
            }
        }
    }

    enum class PagingOrder {
        NORMAL,
        REVERSE
    }

    /**
     * One archive page and the state the paging walks on. The Java class was a non-static inner
     * class, but it never read an outer member, so it is nested here and no longer captures the
     * service; its JVM name is unchanged, which is what `IqGenerator`, `MessageParser`, `MamFin`
     * and `MessageAdapter` compile against.
     *
     * <p>The two receipt sets keep their own `equals`/`hashCode` semantics, and [takePostponedReceipts]
     * is the only door the flush uses into them.
     */
    class Query internal constructor(
            private val account: AccountRef,
            @JvmField val version: Version,
            startRef: MamReference,
            private val end: Long
    ) {

        private val queryId: String = BigInteger(50, Random.SECURE_RANDOM).toString(32)
        private var reference: String? = null
        private var start: Long = 0
        private var conversation: ConversationRef? = null
        private var pagingOrder: PagingOrder = PagingOrder.NORMAL
        private var callbackRef: OnMoreMessagesLoaded? = null
        private var catchup: Boolean = true
        private var pendingReceiptRequests = HashSet<ReceiptRequest>()
        private var receiptRequests = HashSet<ReceiptRequest>()
        private var totalCount = 0
        private var actualCount = 0
        private var actualInThisQuery = 0

        init {
            // Java set the reference *instead of* the start, so a query built from a reference has a
            // zero start; `IqGenerator` reads both.
            if (startRef.getReference() != null) {
                reference = startRef.getReference()
            } else {
                start = startRef.getTimestamp()
            }
        }

        internal constructor(
                conversation: ConversationRef,
                start: MamReference,
                end: Long,
                catchup: Boolean,
                order: PagingOrder
        ) : this(
                conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
                Version.get(
                    conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
                    conversation,
                ),
                if (catchup) start else start.timeOnly(),
                end) {
            this.conversation = conversation
            this.pagingOrder = order
            this.catchup = catchup
        }

        internal constructor(
                conversation: ConversationRef,
                start: MamReference,
                end: Long,
                catchup: Boolean
        ) : this(
                conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
                Version.get(
                    conversation.getAccount() ?: throw NullPointerException("conversation has no account"),
                    conversation,
                ),
                if (catchup) start else start.timeOnly(),
                end) {
            this.conversation = conversation
            this.pagingOrder = if (catchup) PagingOrder.NORMAL else PagingOrder.REVERSE
            this.catchup = catchup
        }

        internal constructor(account: AccountRef, start: MamReference, end: Long)
                : this(account, Version.get(account), start, end)

        private fun page(reference: String?): Query {
            val query = Query(this.account, this.version, MamReference(this.start, reference), this.end)
            query.conversation = conversation
            query.totalCount = totalCount
            query.actualCount = actualCount
            query.pendingReceiptRequests = pendingReceiptRequests
            query.receiptRequests = receiptRequests
            query.callbackRef = callbackRef
            query.catchup = catchup
            return query
        }

        /**
         * Tulkki: one method each, and the queue stores the value type directly. Set membership is
         * still the value's own `equals`/`hashCode`, unchanged.
         */
        fun removePendingReceiptRequest(receiptRequest: ReceiptRequest) {
            if (!this.pendingReceiptRequests.remove(receiptRequest)) {
                this.receiptRequests.add(receiptRequest)
            }
        }

        fun addPendingReceiptRequest(receiptRequest: ReceiptRequest) {
            this.pendingReceiptRequests.add(receiptRequest)
        }

        fun isLegacy(): Boolean = version.legacy

        fun safeToExtractTrueCounterpart(): Boolean = muc() && !isLegacy()

        fun next(reference: String?): Query {
            val query = page(reference)
            query.pagingOrder = PagingOrder.NORMAL
            return query
        }

        internal fun prev(reference: String?): Query {
            val query = page(reference)
            query.pagingOrder = PagingOrder.REVERSE
            return query
        }

        fun getReference(): String? = reference

        fun getPagingOrder(): PagingOrder = this.pagingOrder

        fun getQueryId(): String = queryId

        fun getWith(): Jid? = conversation?.getJid()?.asBareJid()

        fun muc(): Boolean =
                conversation != null && conversation?.getMode() == ConversationalRef.MODE_MULTI

        fun getStart(): Long = start

        fun isCatchup(): Boolean = catchup

        fun setCallback(callback: OnMoreMessagesLoaded?) {
            this.callbackRef = callback
        }

        fun callback(done: Boolean) {
            val callback = this.callbackRef
            if (callback != null) {
                callback.onMoreMessagesLoaded(actualCount, conversation)
                if (done) {
                    callback.informUser(R.string.no_more_history_on_server)
                }
            }
        }

        fun getEnd(): Long = end

        fun getConversation(): ConversationRef? = conversation

        fun getAccount(): AccountRef = this.account

        fun incrementMessageCount() {
            this.totalCount++
        }

        fun incrementActualMessageCount() {
            this.actualInThisQuery++
            this.actualCount++
        }

        internal fun getTotalCount(): Int = this.totalCount

        internal fun getActualMessageCount(): Int = this.actualCount

        fun getActualInThisQuery(): Int = this.actualInThisQuery

        fun validFrom(from: Jid?): Boolean =
                if (muc()) {
                    getWith() == from
                } else {
                    (from == null) || account.getJid().asBareJid().equals(from.asBareJid())
                }

        /** Java's `processFin` set the reference directly on a freshly built query. */
        internal fun setReference(reference: String?) {
            this.reference = reference
        }

        /** The flush's only door into the two receipt sets; it preserves the set's own semantics. */
        internal fun takePostponedReceipts(): List<ReceiptRequest> {
            this.pendingReceiptRequests.removeAll(this.receiptRequests)
            val drained = ArrayList(this.pendingReceiptRequests)
            this.pendingReceiptRequests.clear()
            return drained
        }

        internal fun hasCallback(): Boolean = this.callbackRef != null

        override fun toString(): String {
            val builder = StringBuilder()
            if (this.muc()) {
                builder.append("to=")
                builder.append(this.getWith().toString())
            } else {
                builder.append("with=")
                if (this.getWith() == null) {
                    builder.append("*")
                } else {
                    builder.append(getWith().toString())
                }
            }
            if (this.start != 0L) {
                builder.append(", start=")
                builder.append(uk.xa0.tulkki.app.generator.AbstractGenerator.getTimestamp(this.start))
            }
            if (this.end != 0L) {
                builder.append(", end=")
                builder.append(uk.xa0.tulkki.app.generator.AbstractGenerator.getTimestamp(this.end))
            }
            builder.append(", order=").append(pagingOrder.toString())
            if (this.reference != null) {
                if (this.pagingOrder == PagingOrder.NORMAL) {
                    builder.append(", after=")
                } else {
                    builder.append(", before=")
                }
                builder.append(this.reference)
            }
            builder.append(", catchup=").append(catchup)
            builder.append(", ns=").append(version.namespace)
            return builder.toString()
        }
    }
}
