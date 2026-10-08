package uk.xa0.tulkki.data.messages

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * The list's own read (S5-6): one conversation row **with its last-message pointer already
 * projected**, in one statement.
 *
 * <p>`docs/MIGRATION.md`, "Design: the Compose UI" §2.3 invariant 4 is the rule: "The
 * conversation-list preview is a pointer to the row, never a copy... `UiConversation` carries
 * `lastMessageId` + `lastMessageAt` ... The list is one joined query per emission, not N+1." The
 * entity's own columns arrive through [row] - `@Embedded`, so this type spells none of them a second
 * time - and the two pointer columns are the correlated subqueries
 * [ConversationQueries.LIST_FOR_ACCOUNT] projects.
 *
 * <p>It supersedes S5-3's `ConversationPointer`, whose own comment said it was "a DAO result, not
 * the read model `:ui` will see (that arrives in S5-6)": a `Flow` cannot join two reads into one
 * emission, so the pointer query and the row read are one statement now. Both reads of the list
 * ([ConversationSnapshots.readAccount] and [ConversationSnapshots.watchAccount]) run exactly it, so
 * the synchronous and the streaming list cannot disagree.
 *
 * <p>No body column is selected, and none may be added: the preview is the projector's second read
 * of the one row [lastMessageId] names, which is what makes concealing the row conceal the preview
 * by construction. `MessagesDaoTest` asserts the statement carries no `body`.
 */
internal data class ConversationRow(
    @Embedded val row: ConversationEntity,
    /** The newest row's id, or null on an empty conversation. */
    @ColumnInfo(name = "lastMessageId") val lastMessageId: String?,
    /** The newest row's instant, the list's only denormalised scalar. */
    @ColumnInfo(name = "lastMessageAt") val lastMessageAt: Long?,
)
