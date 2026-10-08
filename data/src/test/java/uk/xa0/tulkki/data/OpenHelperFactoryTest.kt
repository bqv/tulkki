package uk.xa0.tulkki.data

import java.nio.charset.StandardCharsets
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.Schema80

/**
 * Ban test 3 of 3 (S5-1): the opener is the SQLCipher one, and the key it is handed is the
 * `x'<64 hex>'` raw-key form.
 *
 * `docs/MIGRATION.md`, "Design: the data layer" §3.4 asks for a test that builds the opener and
 * inspects its `SupportSQLiteOpenHelper.Factory`. **That is not testable in this module.** SQLCipher
 * ships Android ABIs only - `jni/arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` and no host library -
 * so `System.loadLibrary("sqlcipher")` fails on the unit-test JVM before any factory exists, and the
 * android `room-runtime` variant on that classpath needs `android.content.Context` regardless. What
 * is checkable here is the two halves that decide whether the real open works: the key's shape,
 * which is pure Kotlin and is asserted by running it, and the places the factory is constructed,
 * which is asserted by reading the tree. Since S5-3 there are two - one per SQLCipher file, the chat
 * database's and the UnifiedPush distributor's - and each is pinned with its own file name, hook and
 * library load. The object-level check belongs to the device (S5-1's gate (e)).
 */
class OpenHelperFactoryTest {

    private val opener = "data/src/main/java/uk/xa0/tulkki/data/HistoryDatabase.kt"

    /**
     * The second SQLCipher file, and so the second factory. `docs/MIGRATION.md`, "Design: the data
     * layer" §2.6 gives the UnifiedPush database its own `SupportOpenHelperFactory` because it is a
     * different <em>file</em> with different key material: what the single-site assertion protects is
     * one owner per file, and the two sites name different files and derive different keys.
     */
    private val updbOpener =
        "data/src/main/java/uk/xa0/tulkki/data/updb/UnifiedPushDatabase.kt"

    @Test
    fun theKeyIsTheRawSqlCipherForm() {
        val key = Argon2KeyDerivation.formatAsRawSqlCipherKey(ByteArray(32))
        val text = String(key, StandardCharsets.US_ASCII)
        Assert.assertEquals("x'<64 hex>' plus the two quotes", 2 + 64 + 1, text.length)
        Assert.assertTrue("must open with x'", text.startsWith("x'"))
        Assert.assertTrue("must close with '", text.endsWith("'"))
        val hex = text.substring(2, text.length - 1)
        Assert.assertEquals(64, hex.length)
        Assert.assertTrue("must be lowercase hex: " + hex, hex.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun theFactoryIsBuiltInExactlyOnePlacePerFileAndWithTheHook() {
        val where = ArrayList<String>()
        for (sources in arrayOf(
            "app/src/main", "ui/src/main", "translation/src/main",
            "data/src/main", "xmpp/src/main", "crypto/src/main")) {
            for (file in RepoFiles.sourcesUnder(sources)) {
                if (RepoFiles.read(file).contains("SupportOpenHelperFactory")) {
                    where.add(RepoFiles.name(file))
                }
            }
        }
        Assert.assertEquals(
            "exactly two openers may construct the SQLCipher factory, one per file - the chat " +
                "database and the UnifiedPush distributor's. A third is an owner of a file this " +
                "tree does not know about, and a missing one means an opener stopped opening",
            listOf(opener, updbOpener),
            where)

        val openerText = RepoFiles.read(opener)
        Assert.assertTrue(
            "the opener must pass the file name unchanged, so getDatabasePath(\"history\") " +
                "resolves to the same file, -wal and -shm",
            openerText.contains("DatabaseBackend.DATABASE_NAME"))
        Assert.assertTrue(
            "the opener must pass the same SQLiteDatabaseHook the legacy chain used, or the " +
                "cipher_memory_security / secure_delete PRAGMAs are lost",
            openerText.contains("DatabaseBackend.ARGON2_DATABASE_HOOK"))
        Assert.assertTrue(
            "SQLCipher's native library must be loaded before the factory opens anything",
            openerText.indexOf("loadLibrary(\"sqlcipher\")") > 0 &&
                openerText.indexOf("loadLibrary(\"sqlcipher\")") <
                openerText.indexOf("SupportOpenHelperFactory("))

        val updb = RepoFiles.read(updbOpener)
        Assert.assertTrue(
            "the UnifiedPush opener must keep the legacy file name, or " +
                "getDatabasePath(\"unified-push-distributor\") resolves to a different file and " +
                "every registration is silently lost",
            updb.contains("\"unified-push-distributor\""))
        Assert.assertTrue(
            "the UnifiedPush opener must pass the same SQLiteDatabaseHook the legacy chain used",
            updb.contains("DatabaseBackend.ARGON2_DATABASE_HOOK"))
        Assert.assertTrue(
            "SQLCipher's native library must be loaded before the UnifiedPush factory opens " +
                "anything",
            updb.indexOf("loadLibrary(\"sqlcipher\")") > 0 &&
                updb.indexOf("loadLibrary(\"sqlcipher\")") <
                updb.indexOf("SupportOpenHelperFactory("))
    }

    /**
     * A **source pin, not a runtime proof**. Nothing in this module can open the database (SQLCipher
     * ships Android ABIs only), so the only JVM-visible fact about foreign-key enforcement is that
     * the opener asks for it. The call itself is checked by the compiler -
     * `androidx.sqlite.db.SupportSQLiteDatabase.setForeignKeyConstraintsEnabled(boolean)` exists on
     * the object `onOpen` is handed, and the build fails if it does not - so what this pin adds is
     * that the call is present and in the one legal position.
     *
     * The behaviour it pins is real and load-bearing: `PRAGMA foreign_keys=ON` used to run in
     * `DatabaseBackend.onConfigure`, which the SQLCipher helper called. The adoption commit made
     * `DatabaseBackend` stop being an open helper, so nothing ran it and the `ON DELETE CASCADE`
     * clauses the schema declares stopped firing — while `DatabaseBackend.deleteAccount` deletes one
     * `accounts` row and relies on them to take the account's own rows with it. The device check,
     * owed: delete an account and find no orphans in the conversation table, `messages`, `contacts`,
     * `identities`, `sessions`, `prekeys`, `signed_prekeys` or `pinned_messages`.
     *
     * The pin is positional as well as textual, because the position is the part with a reason: the
     * call must sit inside `onOpen`, which the SQLCipher helper runs after the version transaction is
     * committed and closed (`javap -c` on sqlcipher-android 4.16.0's
     * `SQLiteOpenHelper.getDatabaseLocked`: `endTransaction` at bytecode 552, `onOpen` at 569). Moved
     * into `onCreate` the string would still be there, and the pool's `reconfigure` throws
     * `IllegalStateException` inside a transaction.
     */
    @Test
    fun sourcePinTheOpenerEnablesForeignKeysOnOpenSoTheDeclaredCascadesFire() {
        val openerText = RepoFiles.read(opener)
        val onOpen = openerText.indexOf("override fun onOpen(")
        val enable = openerText.indexOf("setForeignKeyConstraintsEnabled(true)")
        Assert.assertTrue(
            "the opener must enable foreign keys: without it the ON DELETE CASCADE clauses the " +
                "schema declares never fire, and DatabaseBackend.deleteAccount deletes one " +
                "accounts row and relies on them",
            enable > 0)
        Assert.assertTrue(
            "the call must be inside onOpen, which the SQLCipher helper runs after the version " +
                "transaction is committed and closed; in onCreate it would run inside a " +
                "transaction, where SQLite refuses a foreign_keys change",
            onOpen > 0 && enable > onOpen)
    }

    @Test
    fun theOpenerDeclaresTheVersionTheFileIsBroughtTo() {
        Assert.assertEquals(
            "the version this stage brings the file to, as a literal: comparing the two symbols " +
                "only to each other is satisfied by any number they agree on, and the version pair " +
                "is the schema's identity",
            80,
            Schema80.VERSION)
        Assert.assertEquals(
            "the tree's schema version, with exactly one home for the number (Schema80.VERSION): " +
                "a file at 75 is brought to it by MIGRATION_75_76, MIGRATION_76_77 and " +
                "MIGRATION_77_78, MIGRATION_78_79 and MIGRATION_79_80, and a fresh install creates it",
            Schema80.VERSION,
            DatabaseBackend.DATABASE_VERSION)
        Assert.assertEquals("history", DatabaseBackend.DATABASE_NAME)
        // `@Database` is AnnotationRetention.BINARY, so the annotation cannot be read back by
        // reflection (getAnnotation returns null and a test that used it would pass vacuously).
        // The source is the only place left to pin it, and the constant is what it names, so the
        // number has exactly one home and RoomKeepRuleTest already reads this file.
        val openerText = RepoFiles.read(opener)
        val versionLine =
            openerText.lineSequence().firstOrNull { it.contains("version =") }
                ?: "(no version line)"
        Assert.assertTrue(
            "HistoryDatabase must take its version from DatabaseBackend.DATABASE_VERSION rather " +
                "than repeating the number: " + versionLine,
            // The annotation became multi-line when the four entities arrived in S5-2b; what is
            // pinned is the constant, not the layout it happens to be written in.
            openerText.contains("version = DatabaseBackend.DATABASE_VERSION"))
    }
}
