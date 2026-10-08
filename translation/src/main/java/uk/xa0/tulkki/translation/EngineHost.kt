package uk.xa0.tulkki.translation

import android.content.Context
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message

/**
 * What the engine needs from the app it runs inside, declared here and implemented by the composition
 * root.
 *
 * <p>It exists because this module may not name outward: `:xmpp` is an island (naming one from
 * a non-island is a one-way edge, and `allow` cannot legalise an island being named the other way),
 * and `:app`/`:ui` sit above this module in the map's order, so no declaration can point
 * at them either. Everything this module used to reach directly - the running service's database
 * handle and its two conversation callbacks, the process-wide translation queue, WorkManager, the
 * reply fallback's span - is therefore a member here.
 *
 * <p><strong>The engine resolves its host from the [Context] it is already handed.</strong> The
 * one installation point is [install], called once at process start by
 * `uk.xa0.tulkki.app.TulkkiApplication`; the host it installs is an adapter over
 * `XmppConnectionService`, and every `Context` the engine is given is that service
 * (upstream hands the engine `activity.xmppConnectionService`, which is a `Context`, and
 * the island hands it `this`). Since an Android process has exactly one
 * `Application` and one such service, one slot is the whole of the lookup.
 *
 * <p>[of] fails loudly rather than returning nothing: a host that was never installed means
 * the engine would hold every outgoing message for ever or send it untranslated, and either of those
 * is worse than a crash that names the missing installation.
 *
 * <p>Pure Kotlin interface. The four statics stay statics - `install`, `of`, `installed`, `orNull`
 * are `@JvmStatic` members of the interface's companion, so Java's `EngineHost.install(...)` is
 * unchanged - and the one slot, which the Java kept in a package-private nested `Holder` class, is
 * the companion's own `@Volatile` field here (`Holder` had no reader outside this file). Every
 * member's nullability is read off `XmppTulkkiHost.kt`, the only implementor.
 */
interface EngineHost {

    /** The running service's database handle - the one `OutgoingTranslation` writes rows to. */
    fun databaseBackend(): DatabaseBackend

    /** Redraw the open conversation list, after a held message was written into a conversation. */
    fun updateConversationUi()

    /** Redraw the notification shade, after a translation changed a body it already shows. */
    fun updateNotifications()

    /**
     * The loaded conversation with this uuid, or `null` when none is.
     *
     * <p>The store writes its row either way; this is the in-memory half, and a conversation that is
     * not loaded is the ordinary case for a worker.
     */
    fun conversation(conversationUuid: String): Conversation?

    /** Persist one conversation, after a refused quick reply was kept as its draft. */
    fun updateConversation(conversation: Conversation)

    /** Hand a ready message back to the send path, when no `Sender` was given. */
    fun resendMessage(message: Message, isResend: Boolean)

    /** The reply fallback this message declares, as plain text - see [ReplySpan]. */
    fun replySpan(message: Message): ReplySpan

    /** The day's counters and the one kept failure - see [TranslationActivityPort]. */
    fun activity(): TranslationActivityPort

    /** The process-wide queue the receive path writes into. */
    fun translationService(context: Context): TranslationService

    /** Ask WorkManager to drain that queue now. */
    fun kickTranslationWork(context: Context)

    companion object {

        /** The one slot. */
        @Volatile private var installedHost: EngineHost? = null

        /**
         * The composition root's one line: install the host, at process start, before any service exists.
         *
         * <p>Called from `TulkkiApplication.onCreate`, which Android runs before every
         * `Service.onCreate` in the process.
         */
        @JvmStatic
        fun install(host: EngineHost) {
            installedHost = host
        }

        /**
         * The host, from the [Context] the engine was handed.
         *
         * @throws IllegalArgumentException when the engine was handed no Context at all
         * @throws IllegalStateException when nothing was installed - a build fault, not a runtime state
         */
        @JvmStatic
        fun of(context: Context?): EngineHost {
            if (context == null) {
                throw IllegalArgumentException(
                        "the engine is handed a Context, and this caller handed it null")
            }
            return installed()
        }

        /**
         * The installed host, for the one caller the engine reaches without a `Context`: the
         * bubble's "is this message being held?" question, which is handed a message and the app
         * language. There is one host per process and the bubble only exists inside it.
         *
         * @throws IllegalStateException when nothing was installed - a build fault, not a runtime state
         */
        @JvmStatic
        fun installed(): EngineHost =
                installedHost
                        ?: throw IllegalStateException(
                                "Tulkki's engine host was never installed: the composition root must"
                                        + " call EngineHost.install at process start, and it has not")

        /**
         * The installed host, or `null` when this process has none.
         *
         * <p>For the one caller whose work is complete without a host: [TranslationStore] writes
         * its row either way and only the in-memory redraw is skipped. That is deliberately not what
         * [of] does - a missing host where a message must be held or sent is a build fault, and
         * this accessor exists so the store can say which of the two it is asking for.
         */
        @JvmStatic fun orNull(): EngineHost? = installedHost
    }
}
