package uk.xa0.tulkki.ui.projection

import com.google.gson.JsonParser
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.ui.R

/**
 * One conversation-list row, `Design: the Compose UI` §2.2's `UiConversation`, built from the read model
 * the list is gated on: `:data`'s [ConversationSnapshot] plus the snapshot of the row its pointer names.
 *
 * <p>**The pointer, never the text.** [ConversationSnapshot] carries `lastMessageId` and
 * `lastMessageAt`, and `:data` selects no body for it, so the preview is this projection's second read
 * of the one row the pointer names - which is what makes concealing the row conceal the preview by
 * construction (§2.3 invariant 4). The scalars are the snapshot's; the rules the snapshot deliberately
 * does not decide (`ConversationSnapshot`'s own comment: "the flags are the model's reading of [status]
 * and [attributes], and the projector is where that reading happens") are decided here.
 *
 * <p>**`group` is this row's addition to §2.2's field list.** §2.2's `UiConversation` names no group
 * flag, and §3.5's list carries three filters whose third is `Groups`: the three are "cheap predicates
 * over `rows`", so the fact has to be on the row. It is `Conversation.MODE_MULTI`, which the snapshot
 * already holds - no new read, and no second source for what a conversation is.
 *
 * <p>**What is a service fact and what is a column.** `archived` is the stored `status`; `muted` is the
 * `muted_till` attribute against a clock the caller passes, so the projector still reads no clock of its
 * own. The unread count is **not** a column at all - the tree asks
 * `Conversation.unreadCount(XmppConnectionService)` - so it comes from [PerProcess], the seam
 * [MessagePreview] defines, rather than from a `:data` read that does not exist.
 *
 * <p>The two languages are the pair every surface that names a language must name (§2.2): the
 * conversation's own detected-or-overridden language, the app language, and which of the two is in force.
 * [UiTranslationMode] is then the derived predicate and nothing else - off when the interpreter is off,
 * and `LanguageUnknown` when nobody has read a language yet, never an invented default.
 */
object ConversationProjection {

    /** The attribute `Conversation.isMuted` reads; its value is an instant in milliseconds. */
    private const val MUTED_TILL = "muted_till"

    /**
     * The two room attributes of §3.5's kind, spelled here because `Conversation`'s own constants are
     * package-private: `ATTRIBUTE_MEMBERS_ONLY` and `ATTRIBUTE_NON_ANONYMOUS`
     * (`data/src/main/java/uk/xa0/tulkki/data/model/Conversation.java:228-230`).
     */
    private const val MEMBERS_ONLY = "members_only"

    private const val NON_ANONYMOUS = "non_anonymous"

    /**
     * The two attributes `Conversation.getDraft()` reads, spelled here for the reason the room attributes
     * above are: `ATTRIBUTE_NEXT_MESSAGE` and `ATTRIBUTE_NEXT_MESSAGE_TIMESTAMP` are private
     * (`data/src/main/java/uk/xa0/tulkki/data/model/Conversation.java:231-232`), and the draft is a stored
     * attribute rather than a column of its own.
     */
    private const val NEXT_MESSAGE = "next_message"

    private const val NEXT_MESSAGE_TIMESTAMP = "next_message_timestamp"

    /**
     * One row.
     *
     * @param now the clock `muted` is read against: the moment the list is being drawn, so a mute that
     *     has expired is not still shown as one.
     * @param lastMessage the snapshot the conversation's pointer names, or `null` on an empty
     *     conversation - which has nothing to preview rather than an empty line.
     * @param facts the row-level live facts for the previewed row, [MessageFacts.NONE] when the caller
     *     knows none: [MessageFacts.strippedBody] is what keeps a reply's carried quote out of the list.
     */
    fun of(
        conversation: ConversationSnapshot,
        lastMessage: MessageSnapshot?,
        appLanguage: String,
        interpreter: Interpreter,
        words: PreviewWords,
        now: Long,
        perProcess: PerProcess = PerProcess.NONE,
        facts: MessageFacts = MessageFacts.NONE,
        relativeTime: Boolean = true,
    ): UiConversation {
        val name = name(conversation, perProcess, words)
        val language = conversationLanguage(conversation)
        // The draft is drawn only while nothing is unread, so the seam is read before the preview is
        // built rather than after it.
        val unread = perProcess.unreadCount(conversation)
        val draft = draft(conversation, lastMessage, unread)
        return UiConversation(
            id = ConversationId(conversation.id),
            name = name,
            jid = conversation.jid.orEmpty(),
            lastMessageId = conversation.lastMessageId?.let { MessageId(it) },
            lastMessageAt = conversation.lastMessageAt ?: 0L,
            preview =
                draft?.let { UiPreview.Visible(it.text, draft = true) }
                    ?: if (lastMessage == null) {
                        UiPreview.Absent
                    } else {
                        MessagePreview.of(lastMessage, name, interpreter, words, perProcess, facts)
                    },
            unread = unread,
            muted = muted(conversation, now),
            archived = conversation.status == Conversation.STATUS_ARCHIVED.toLong(),
            pinned = flag(conversation.attributes, Conversation.ATTRIBUTE_PINNED_ON_TOP),
            kind = kind(conversation),
            withSelf = perProcess.withSelf(conversation),
            ongoingCall = perProcess.ongoingCall(conversation),
            // The tree timed a row by the draft's own instant while one was being written and by the
            // newest message otherwise, and the draft's instant is the one the preview above read.
            time =
                ConversationRowTime.of(
                    draft?.at ?: (conversation.lastMessageAt ?: 0L),
                    now,
                    relativeTime,
                    words,
                ),
            // A room is not a person, so there is no availability to draw: the tree's indicator was handed
            // `conversation.getContact()`, and only a one-to-one has one. Pinning it here rather than in the
            // screen makes it a cell - a room whose seam reports ONLINE still draws nothing.
            presence =
                if (kind(conversation) == ConversationKind.ONE_TO_ONE) {
                    perProcess.presence(conversation)
                } else {
                    UiPresence.UNKNOWN
                },
            sender = sender(lastMessage, kind(conversation), words),
            tick =
                if (lastMessage == null) {
                    null
                } else {
                    ConversationRowMark.tick(lastMessage, perProcess.transfer(lastMessage))
                },
            notification = perProcess.notification(conversation),
            account = perProcess.accountLine(conversation),
            language =
                UiLanguagePair(
                    conversationLanguage = language,
                    appLanguage = appLanguage,
                    overridden = !conversation.languageOverride.isNullOrBlank(),
                ),
            translation = translationMode(interpreter, language),
        )
    }

    /**
     * The name the interface shows - `Conversation.getName`'s own chain, ported
     * (`data/src/main/java/uk/xa0/tulkki/data/model/Conversation.java:1180`), because a room's name is
     * almost never in a column: the tree composes it from the live room, its subject, its bookmark and
     * finally its participants, and only then the room's own local part.
     *
     * <p>**A room does not read the stored column at all**, which is the tree's own shape - `Conversation`
     * writes that column for a one-to-one and composes for a room - and that is why a room whose name lives
     * in its MUC options or its bookmark arrived blank and the row drew an address.
     *
     * <p>**And the row never draws a full address** (owner, looking at the phone): where there is no name at
     * all, the label is the **localpart** - `alice`, `tulkki` - for a one-to-one exactly as for a room, which
     * is the tree's own `contactJid.getLocal()`. Only a JID with no localpart leaves the name empty, because
     * an address on a row is worse than a short one.
     *
     * <p>The name is also what the bare-name rule is asked about, so the row and the preview name the
     * conversation identically; and the owner's note-to-self row is wrapped in the tree's own title, which
     * the deleted adapter applied to whatever `getName` returned.
     *
     * <p>**Named hole, not an approximation:** the tree's `printableValue(candidate, false)` also refuses a
     * **bookmark name that looks like an address**, and telling an address from a name needs the island's
     * `Jid` parser, which this module may not name. Only the blank half of that guard is here.
     */
    private fun name(
        conversation: ConversationSnapshot,
        perProcess: PerProcess,
        words: PreviewWords,
    ): String {
        val stored = conversation.name?.takeIf { it.isNotBlank() }
        // The label of last resort, and it is never an address: `Jid.getLocal()`'s part, for a room and for
        // a one-to-one alike. A JID with no `@` has no localpart at all - `getLocal()` is null for a domain -
        // so it leaves nothing, which the row draws as a bare line.
        val address = conversation.jid.orEmpty()
        val local =
            if (address.contains('@')) {
                address.substringBefore('@').takeIf { it.isNotBlank() }.orEmpty()
            } else {
                ""
            }
        val composed =
            if (conversation.mode == Conversation.MODE_MULTI.toLong()) {
                perProcess.roomName(conversation)?.firstPrintable() ?: local
            } else {
                stored ?: local
            }
        return if (perProcess.withSelf(conversation)) {
            words.get(R.string.note_to_self_conversation_title, composed)
        } else {
            composed
        }
    }

    /** The conversation's own language: the owner's override when there is one, else what was detected. */
    private fun conversationLanguage(conversation: ConversationSnapshot): String? =
        conversation.languageOverride?.takeIf { it.isNotBlank() }
            ?: conversation.detectedLanguage?.takeIf { it.isNotBlank() }

    /**
     * Who the row's second line says is talking - `ConversationAdapter`'s own three-way rule, which is the
     * tree's answer to "why does this row have a name in the middle of it":
     *
     * * a **received** row in a one-to-one says nothing: the conversation's own name is already the first
     *   line, so repeating the sender is noise;
     * * a received row in a room says who, because the room is not one person - the row's own recorded
     *   counterpart, which is the nick the model stored;
     * * anything the owner sent and that is not a status row says "me", in the same slot.
     *
     * <p>The tree drew that name bold, and coloured it when `colored_muc_names` was on - the colour coming
     * from the *entity* (`Message.getAvatarBackgroundColor`), which a snapshot does not carry. The row draws
     * it in the theme's secondary colour instead: one value every row can have, rather than a nick colour the
     * list cannot read. The palette-indexed nick colour is §4.1's, and it belongs with the chat screen's
     * names.
     */
    private fun sender(
        lastMessage: MessageSnapshot?,
        kind: ConversationKind,
        words: PreviewWords,
    ): String? {
        if (lastMessage == null) {
            return null
        }
        val status = lastMessage.status?.toInt() ?: Message.STATUS_RECEIVED
        if (status == Message.STATUS_RECEIVED) {
            if (kind == ConversationKind.ONE_TO_ONE) {
                return null
            }
            return lastMessage.counterpart?.takeIf { it.isNotBlank() }?.let { "$it:" }
        }
        if (lastMessage.type == Message.TYPE_STATUS.toLong()) {
            return null
        }
        return words.get(R.string.me) + ":"
    }

    /**
     * The owner's draft, when the row draws one - the line a conversation the owner has started typing in
     * shows, and the case `Conversation.getDraft()` decides. Its text rides a [UiPreview.Visible] with
     * `draft = true` rather than a case of its own: these types declare exactly one `String`-typed
     * property and a second text carrier is the one thing §2.3 invariant 1 forbids. Its instant is carried
     * back to the caller too, because the tree timed the row by the draft while one was being written.
     *
     * <p>The tree's rule, key for key: the stored `next_message` is drawn only while nothing is unread (an
     * unread message outranks what the owner is still typing), and only while its own
     * `next_message_timestamp` is later than the newest message - falling back, on a conversation with no
     * message at all, to the later of the creation and the last clear. Both instants are in the row's own
     * `attributes`, so the snapshot carries them and the draft needs no second read.
     *
     * <p>**This is the owner's own text, never a received body**, which is why drawing it is not a
     * concealment question: the rule guards an original that arrived, and a draft never arrived.
     */
    private fun draft(
        conversation: ConversationSnapshot,
        lastMessage: MessageSnapshot?,
        unread: Int,
    ): Draft? {
        if (unread > 0) {
            return null
        }
        val attributes = attributes(conversation.attributes) ?: return null
        val at = attributes.get(NEXT_MESSAGE_TIMESTAMP)?.asString?.toLongOrNull() ?: return null
        if (at == 0L) {
            return null
        }
        val text = attributes.get(NEXT_MESSAGE)?.asString
        if (text.isNullOrBlank()) {
            return null
        }
        val messageTime =
            if (lastMessage == null) {
                maxOf(conversation.created ?: 0L, clearHistoryAt(attributes))
            } else {
                lastMessage.timeSent ?: 0L
            }
        return if (at > messageTime) Draft(text, at) else null
    }

    /** The draft's own two numbers: the text the row draws and the instant it was written at. */
    private data class Draft(val text: String, val at: Long)

    /** `last_clear_history`, the `time:ref` instant, or zero: the draft's own comparison needs it. */
    private fun clearHistoryAt(attributes: com.google.gson.JsonObject): Long =
        attributes
            .get(Conversation.ATTRIBUTE_LAST_CLEAR_HISTORY)
            ?.asString
            ?.split(":")
            ?.firstOrNull()
            ?.toLongOrNull() ?: 0L

    /**
     * The same instant, out of a snapshot the caller has rather than out of this object's own projection.
     *
     * <p>It is public because both conversation-list hosts need it for the **order** and neither is a
     * projection: `ConversationOrder.sortableTime` takes the number as an argument for the reason its own
     * comment gives - the tree's reader is `MamReference`, an island type `:ui` may not import - and the
     * column is on the snapshot, so this is the one reader that both can call without that import. It is
     * here rather than beside the order because reading a stored attribute is what this object does, and a
     * second JSON reader in `:ui` is how the two would drift.
     */
    @JvmStatic
    fun lastClearHistoryAt(conversation: ConversationSnapshot): Long =
        attributes(conversation.attributes)?.let { clearHistoryAt(it) } ?: 0L

    /** The stored mute, read against the caller's clock; an unreadable attribute means "not muted". */
    private fun muted(conversation: ConversationSnapshot, now: Long): Boolean =
        now < mutedTill(conversation.attributes)

    /**
     * The conversation's kind: §3.5's filters and the long-press menu's titles both ask it, and both are
     * the same two stored facts. [Conversation.MODE_MULTI] is a group only when the room is private and
     * non-anonymous, the predicate `MucOptions.isPrivateAndNonAnonymous` reads out of the same attributes
     * - so the menu and the filter can never call one conversation two things.
     */
    private fun kind(conversation: ConversationSnapshot): ConversationKind {
        if (conversation.mode != Conversation.MODE_MULTI.toLong()) {
            return ConversationKind.ONE_TO_ONE
        }
        val attributes = attributes(conversation.attributes) ?: return ConversationKind.CHANNEL
        return if (flag(attributes, MEMBERS_ONLY) && flag(attributes, NON_ANONYMOUS)) {
            ConversationKind.GROUP
        } else {
            ConversationKind.CHANNEL
        }
    }

    /** One stored attribute as a boolean: `Conversation.getBooleanAttribute` asks the same question. */
    private fun flag(attributes: String?, name: String): Boolean = flag(attributes(attributes), name)

    private fun flag(attributes: com.google.gson.JsonObject?, name: String): Boolean =
        attributes?.get(name)?.asString?.toBoolean() ?: false

    /**
     * The `muted_till` instant, or zero. A stored attribute is not a trusted shape - the column is a raw
     * `String` the model owns - so anything unreadable falls back to "no mute" rather than throwing, the
     * same rule the settings store keeps (§3.4 item 1).
     */
    private fun mutedTill(attributes: String?): Long =
        attributes(attributes)?.get(MUTED_TILL)?.asString?.toLongOrNull() ?: 0L

    /**
     * The stored attributes as an object, or `null`. `Conversation` writes one JSON object of strings and
     * reads it with `getAttribute`/`getBooleanAttribute`/`getLongAttribute`; this is the one place `:ui`
     * opens it, and a column that is blank or unreadable is simply empty.
     */
    private fun attributes(attributes: String?): com.google.gson.JsonObject? {
        if (attributes.isNullOrBlank()) {
            return null
        }
        return try {
            val root = JsonParser.parseString(attributes)
            if (root.isJsonObject) root.asJsonObject else null
        } catch (e: RuntimeException) {
            // A column the model writes as one JSON object may hold anything after a bad upgrade.
            null
        }
    }

    /** §2.2's `On | Off | LanguageUnknown`: the interpreter's switch first, then the missing language. */
    private fun translationMode(interpreter: Interpreter, language: String?): UiTranslationMode =
        when {
            !interpreter.enabled() -> UiTranslationMode.OFF
            language == null -> UiTranslationMode.LANGUAGE_UNKNOWN
            else -> UiTranslationMode.ON
        }
}
