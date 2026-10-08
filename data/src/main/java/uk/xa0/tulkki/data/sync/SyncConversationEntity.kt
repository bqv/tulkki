package uk.xa0.tulkki.data.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import uk.xa0.tulkki.data.messages.ConversationEntity

/**
 * `sync_conversation` — one row per conversation: its own anchor and its own sweep floor.
 *
 * <p>The per-conversation half of "Design: synchronisation" §1.2's three facts. `account_uuid` is
 * denormalised so the account sweep is one indexed query; the index name is spelled here because
 * Room compares index **names** as well as their columns, and it is the name the file already has
 * ([SyncQueries.CONVERSATION_ACCOUNT_INDEX]).
 *
 * <p>`archive_first_id` is upstream's in-memory `mFirstMamReference`, persisted for the first time;
 * it is `NULL` after an upgrade and that is the one documented loss ("Design: synchronisation"
 * §1.4). `swept_through` is the floor the live-miss sweep advances; `anchor_time == 0` means the
 * conversation has no archived history this device knows of, which is a state and not an error.
 *
 * <p>Storage shape, not model; `TEXT NOT NULL PRIMARY KEY` for the reason `SyncCursorEntity`
 * gives.
 */
@Entity(
    tableName = "sync_conversation",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["conversation_uuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "sync_conversation_account_index", value = ["account_uuid"])],
)
internal data class SyncConversationEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_uuid") val conversationUuid: String,
    @ColumnInfo(name = "account_uuid") val accountUuid: String,
    @ColumnInfo(name = "anchor_stanza_id") val anchorStanzaId: String?,
    @ColumnInfo(name = "anchor_time", defaultValue = "0") val anchorTime: Long,
    @ColumnInfo(name = "archive_first_id") val archiveFirstId: String?,
    @ColumnInfo(name = "swept_through", defaultValue = "0") val sweptThrough: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
