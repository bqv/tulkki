package uk.xa0.tulkki.data.blocking

/**
 * `blocking/`'s SQL: the table, and the four statements the blocklist needs.
 *
 * <p>It is a Kotlin object of `const val`s rather than SQL written inline in the DAO because a
 * `const val` is a compile-time constant an annotation can name (`@Query(BlockingQueries.BLOCK)`),
 * which means the DAO and its JVM test execute *the same string*: the test runs the production SQL
 * over a JDBC fixture, because Room's own runtime cannot open this file (`docs/MIGRATION.md`,
 * "Design: the data layer" §4.4) and a test that re-spelled the query would be testing the test.
 *
 * <p>The table belongs to this package (S5-3's "every package owns its DDL"): `Schema77` executes
 * [CREATE_TABLE], and nothing else spells `blocked_jids`.
 */
internal object BlockingQueries {

    const val TABLE = "blocked_jids"
    const val ACCOUNT = "account_uuid"
    const val JID = "jid"

    /**
     * `blocked_jids`, new in schema 77. The composite key is the block itself, the foreign key is
     * the cascade every per-account table carries, and there is no second index because every
     * query below goes through the key.
     */
    const val CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS blocked_jids (" +
            "account_uuid TEXT NOT NULL, " +
            "jid TEXT NOT NULL, " +
            "PRIMARY KEY (account_uuid, jid), " +
            "FOREIGN KEY(account_uuid) REFERENCES accounts(uuid) ON DELETE CASCADE)"

    const val BLOCKED_BY_ACCOUNT =
        "SELECT jid FROM blocked_jids WHERE account_uuid = :account ORDER BY jid ASC"

    const val COUNT_FOR =
        "SELECT COUNT(*) FROM blocked_jids WHERE account_uuid = :account AND jid = :jid"

    const val BLOCK =
        "INSERT OR IGNORE INTO blocked_jids (account_uuid, jid) VALUES (:account, :jid)"

    const val UNBLOCK =
        "DELETE FROM blocked_jids WHERE account_uuid = :account AND jid = :jid"
}
