package uk.xa0.tulkki.data.omemo

import androidx.room.ColumnInfo

/**
 * The four columns `FingerprintStatus.fromCursor` reads, named (S5-3, the `omemo/` capability).
 *
 * <p>`DatabaseBackend.getIdentityKeyCursor` always projected exactly `trust`, `active`,
 * `last_activation` and `key`, and `FingerprintStatus.fromCursor` is the one reader: it maps
 * `trust` to the island enum, reads `active > 0` and takes `last_activation` as a long. This class
 * is that projection, so the DAO's identity reads answer a named type instead of a cursor.
 *
 * <p>Not an entity: it has no table and Room never validates it. Its only job is to name the four
 * columns [OmemoQueries.IDENTITY_STATUS] projects.
 */
internal data class IdentityStatusRow(
    @ColumnInfo(name = "trust") val trust: String?,
    @ColumnInfo(name = "active") val active: Long?,
    @ColumnInfo(name = "last_activation") val lastActivation: Long?,
    @ColumnInfo(name = "key") val key: String?,
)
