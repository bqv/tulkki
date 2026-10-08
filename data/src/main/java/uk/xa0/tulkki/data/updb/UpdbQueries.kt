package uk.xa0.tulkki.data.updb

/**
 * `updb/`'s SQL: the `push` table's shape, and every statement the distributor's database runs.
 *
 * <p>It is a Kotlin object of `const val`s rather than SQL written inline in the DAO because a
 * `const val` is a compile-time constant an annotation can name, which means the DAO and its JVM
 * test execute *the same string*: the test runs the production SQL over a JDBC fixture, because
 * Room's own runtime cannot open this file (`docs/MIGRATION.md`, "Design: the data layer" §4.4) and
 * a test that re-spelled the query would be testing the test.
 *
 * <p><strong>The table is older than Room and older than this package.</strong> It was created by
 * `UnifiedPushDatabase.onCreate` with `expiration NUMBER DEFAULT 0` and an inline
 * `UNIQUE(instance)`, at file version 1 - and `NUMBER` is exactly the spelling Room cannot
 * validate: `SchemaInfoUtil.findAffinity` has no numeric member, so the file's column normalises to
 * `UNDEFINED`, `TableInfo.Column.equalsCommon` compares affinity unconditionally as its last term,
 * and a version-equal open over that file is a *refused* open rather than a repaired one. There is
 * no `ALTER` that changes a declared type, so the file is adopted by a create-copy-drop-rename in
 * [LegacyPreflight], and [COLUMNS]/[TAIL] are that rebuilt table's shape - the same shape
 * `PushEntity` declares, which is what `UpdbDaoTest` compares Room's generated `CREATE` against.
 *
 * <p>The legacy file's inline `UNIQUE(instance)` is kept verbatim in the rebuilt table (that is the
 * constraint the live inserts have always conflicted on) **and** the same guarantee is created as a
 * named unique index, because Room compares only the indices whose `PRAGMA index_list` origin is
 * `c`: an inline `UNIQUE` is an autoindex with origin `u` and `SchemaInfoUtilKt.readIndices` skips
 * it, so the entity's own `Index(value = ["instance"], unique = true)` - which is how a fresh
 * install gets the guarantee at all - would have nothing to match on an adopted file.
 */
internal object UpdbQueries {

    const val TABLE = "push"

    /** The surrogate key the rebuild adds, carried from the legacy table's `rowid`. */
    const val ID = "_id"

    const val ACCOUNT = "account"
    const val TRANSPORT = "transport"
    const val APPLICATION = "application"
    const val INSTANCE = "instance"
    const val ENDPOINT = "endpoint"
    const val EXPIRATION = "expiration"

    /**
     * The rebuilt table's columns: the legacy declaration, with every column's *true* affinity and
     * the surrogate key first so `PRAGMA table_info` reads in the order Room's generated `CREATE`
     * emits. `account`, `transport`, `endpoint` and `expiration` stay nullable - the rebuild may not
     * make a row the file already holds illegal - and `application`/`instance` keep the `NOT NULL`
     * the legacy table always had.
     */
    @JvmField
    val COLUMNS: List<Pair<String, String>> =
        listOf(
            ID to "INTEGER NOT NULL",
            ACCOUNT to "TEXT",
            TRANSPORT to "TEXT",
            APPLICATION to "TEXT NOT NULL",
            INSTANCE to "TEXT NOT NULL",
            ENDPOINT to "TEXT",
            EXPIRATION to "INTEGER DEFAULT 0",
        )

    /** The legacy `UNIQUE(instance)`, kept, plus the surrogate key that replaced the implicit rowid. */
    const val TAIL = ", PRIMARY KEY(_id), UNIQUE(instance)"

    /**
     * The same uniqueness in the form Room can see. The name is Room's own default
     * (`index_<table>_<column>`), and `TableInfo.Index.equalsCommon` normalises by the `index_`
     * prefix, so the exact spelling is not load-bearing - its *existence* is.
     */
    const val UNIQUE_INSTANCE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS index_push_instance ON push(instance)"

    // -- the statements, in the order PushDao declares them -----------------------------------------

    /** `register`'s existence check: the instance is unique, so this is at most one row. */
    const val APPLICATION_BY_INSTANCE =
        "SELECT application FROM push WHERE instance = :instance"

    /**
     * `register`'s insert. Plain `INSERT`, not `OR IGNORE`: the caller mirrors the legacy
     * `android.database.sqlite.SQLiteDatabase.insert`, which swallowed a constraint violation and
     * answered `-1` rather than throwing out of a `BroadcastReceiver`.
     */
    const val INSERT =
        "INSERT INTO push (application, instance, expiration) VALUES (:application, :instance, 0)"

    /**
     * `getRenewals`: everything that is not this account's or transport's current registration, or
     * whose endpoint has fallen outside the renewal window. `expiration < :expiration` is the
     * legacy concatenation, now a bind.
     */
    const val RENEWALS =
        "SELECT application, instance FROM push " +
            "WHERE account <> :account OR transport <> :transport OR expiration < :expiration"

    /** `getEndpoint`: this exact registration, and only while its endpoint is still valid. */
    const val ENDPOINT_FOR =
        "SELECT application, endpoint FROM push " +
            "WHERE account = :account AND transport = :transport AND instance = :instance " +
            "AND endpoint IS NOT NULL AND expiration >= :expiration"

    /** `deletePushTargets`' read: the legacy query, which took the first row it was handed. */
    const val ALL_TARGETS = "SELECT application, instance FROM push"

    const val DELETE_ALL = "DELETE FROM push"

    /** `hasEndpoints`: existence, as `SELECT EXISTS(...)` returned 0 or 1 to the legacy reader. */
    const val HAS_ENDPOINTS =
        "SELECT EXISTS(SELECT endpoint FROM push WHERE account = :account AND transport = :transport)"

    /** `updateEndpoint`'s read-before-write, so the answer says whether the endpoint moved. */
    const val ENDPOINT_BY_INSTANCE = "SELECT endpoint FROM push WHERE instance = :instance"

    /** `updateEndpoint`'s write: the registration's own row, by instance. */
    const val UPDATE_ENDPOINT =
        "UPDATE push SET account = :account, transport = :transport, endpoint = :endpoint, " +
            "expiration = :expiration WHERE instance = :instance"

    /**
     * `getPushTargets(account, transport)`: the legacy statement's own `WHERE account = ?`, which
     * never bound the transport it was given. Kept as it is - narrowing it would be the behaviour
     * change this conversion exists to avoid.
     */
    const val TARGETS_BY_ACCOUNT =
        "SELECT application, instance FROM push WHERE account = :account"

    const val DELETE_INSTANCE = "DELETE FROM push WHERE instance = :instance"

    const val DELETE_APPLICATION = "DELETE FROM push WHERE application = :application"

    /**
     * S5-12: the registrations an account owns, removed with the account. `push` is in a second
     * SQLCipher file, so no `accounts` cascade can reach it; this is the explicit half of
     * `DatabaseBackend.deleteAccount`.
     */
    const val DELETE_BY_ACCOUNT = "DELETE FROM push WHERE account = :account"
}
