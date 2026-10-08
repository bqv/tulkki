package uk.xa0.tulkki.data

import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test

/**
 * The persisted-name snapshot (S5-1).
 *
 * `src/test/resources/schema-75.sql` is the schema the owner's device has for the two tables this
 * step sees, resolved mechanically from `DatabaseBackend`'s own DDL strings by
 * `.toolchain/s5-1/mk_schema.py` - not typed by hand and not remembered. This test pins its two
 * halves against each other: the exact column and index names the snapshot records, and the literals
 * the tree actually uses. A rename on either side fails here rather than at
 * `context.getDatabasePath("history")`.
 *
 * **What the snapshot is for.** `docs/MIGRATION.md`, "Design: the data layer" §6 step 1 (b) asks for
 * a comparison between "the declared entity DDL" and this snapshot. Exactly one entity is declared
 * in this commit, `cids` → `uk.xa0.tulkki.data.upload.UploadFileEntity`, and
 * [theDeclaredEntityMatchesTheSnapshotForCids] is that comparison. `messages` and `conversations`
 * have no entity, and that is a measurement rather than a deferral: Room validates a pre-existing
 * file by comparing each declared column's normalised <em>affinity</em>
 * (`TableInfo.Column.equalsCommon`, final term, unconditional), Room normalises a declared type with
 * no `INT/CHAR/CLOB/TEXT/BLOB/REAL/FLOA/DOUB` in it to `ColumnInfo.UNDEFINED`, and most of this
 * schema declares its numeric columns `NUMBER`. The entity side can only ever emit
 * `TEXT|INTEGER|REAL|BLOB`, because `ColumnInfo.SQLiteTypeAffinity` has no `UNDEFINED` or numeric
 * member and the type adapter's affinity wins anyway. So a Kotlin `Long` always compares `INTEGER`
 * against a file column that reads `UNDEFINED`, and declaring those entities now would be a refused
 * open - the exact failure this commit exists to avoid. They arrive with the migration that also
 * normalises those declared types, which is a table rebuild rather than an `ALTER`.
 */
class SchemaNameTest {

    // `[^;]*` rather than `.*?`: a greedy body would swallow the statements after the table and a
    // lazy one would still be free to cross one, and the snapshot is several statements long.
    private val create = Pattern.compile(
        "create\\s+table\\s+if\\s+not\\s+exists\\s+(\\w+)\\s*\\(([^;]*)\\)\\s*;",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val alter = Pattern.compile(
        "alter\\s+table\\s+(\\w+)\\s+add\\s+column\\s+(\\w+)", Pattern.CASE_INSENSITIVE)
    private val index = Pattern.compile(
        "create\\s+index\\s+if\\s+not\\s+exists\\s+(\\w+)", Pattern.CASE_INSENSITIVE)

    /** 32 in the CREATE plus the 6 guarded ALTERs: see the snapshot's own header. */
    private val messages = linkedSetOf(
        "uuid", "conversationUuid", "timeSent", "counterpart", "trueCounterpart", "body",
        "encryption", "status", "type", "relativeFilePath", "serverMsgId",
        "axolotl_fingerprint", "carbon", "edited", "read", "oob", "errorMsg", "readByMarkers",
        "markable", "file_deleted", "deleted", "bodyLanguage", "retractId", "occupantId",
        "occupant_id", "reactions", "remoteMsgId", "ephemeral_timer", "expire_at",
        "translated_body", "translation_lang", "translation_state",
        "subject", "oobUri", "fileParams", "payloads", "timeReceived", "notificationDismissed")

    /** 9 in the CREATE plus Tulkki's 2 language columns, which only ever arrive as ALTERs. */
    private val conversations = linkedSetOf(
        "uuid", "name", "contactUuid", "accountUuid", "contactJid", "created", "status", "mode",
        "attributes", "detected_language", "language_override")

    private val messageIndexes = linkedSetOf(
        "message_time_index", "message_conversation_index", "message_deleted_index",
        "message_file_deleted_index", "message_file_path_index", "message_type_index",
        "message_expire_at_index", "message_time_received_index")

    private fun snapshot(): String {
        val input = SchemaNameTest::class.java.getResourceAsStream("/schema-75.sql")
        Assert.assertNotNull(
            ":data's unit test classpath is missing src/test/resources/schema-75.sql",
            input)
        return String(input!!.readAllBytes(), StandardCharsets.UTF_8)
    }

    private fun stripComments(sql: String): String {
        val out = StringBuilder()
        for (line in sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    /** Top-level comma split: a `FOREIGN KEY(a, b)` is one part, not two. */
    private fun splitTopLevel(body: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in body.toCharArray()) {
            if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
        }
        parts.add(cur.toString())
        return parts
    }

    private fun columnsOf(sql: String, table: String): Set<String> {
        val m = create.matcher(sql)
        val columns = sortedSetOf<String>()
        var found = false
        while (m.find()) {
            if (!m.group(1).equals(table, ignoreCase = true)) {
                continue
            }
            found = true
            for (part in splitTopLevel(m.group(2))) {
                val decl = part.trim()
                if (decl.isEmpty() || decl.uppercase().startsWith("FOREIGN KEY") ||
                    decl.uppercase().startsWith("PRIMARY KEY") ||
                    decl.uppercase().startsWith("UNIQUE")) {
                    continue
                }
                columns.add(decl.split(Regex("\\s+"))[0])
            }
        }
        Assert.assertTrue("no CREATE TABLE for " + table + " in the snapshot", found)
        val a = alter.matcher(sql)
        while (a.find()) {
            if (a.group(1).equals(table, ignoreCase = true)) {
                columns.add(a.group(2))
            }
        }
        return columns
    }

    @Test
    fun theSnapshotRecordsTheSchemaTheDeviceHas() {
        val sql = stripComments(snapshot())
        Assert.assertEquals(messages, columnsOf(sql, "messages"))
        Assert.assertEquals(conversations, columnsOf(sql, "conversations"))

        val indexes = sortedSetOf<String>()
        val i = index.matcher(sql)
        while (i.find()) {
            indexes.add(i.group(1).lowercase())
        }
        Assert.assertEquals(messageIndexes, indexes)

        Assert.assertTrue(
            "the snapshot must keep the NUMBER spelling of the numeric columns: it is what " +
                "makes the entity's affinity mismatch visible rather than silent",
            sql.contains("timeSent NUMBER") && sql.contains("created NUMBER"))
    }

    @Test
    fun everyRecordedNameStillExistsInTheTree() {
        val tree = StringBuilder()
        for (file in RepoFiles.sourcesUnder("data/src/main")) {
            tree.append(RepoFiles.read(file))
        }
        val all = tree.toString()
        val gone = ArrayList<String>()
        for (name in union().sorted()) {
            if (!all.contains(name)) {
                gone.add(name)
            }
        }
        Assert.assertEquals(
            "a persisted name in the snapshot no longer appears anywhere in :data's sources: " +
                "either the snapshot is stale or something was renamed without a migration",
            emptyList<String>(),
            gone)
    }

    private fun union(): Set<String> {
        val all = sortedSetOf<String>()
        all.addAll(messages)
        all.addAll(conversations)
        all.addAll(messageIndexes)
        return all
    }

    /** The entity's declared columns, as (persisted name -> NOT NULL). */
    private fun declaredEntity(): Map<String, Boolean> {
        val kt = RepoFiles.read(
            "data/src/main/java/uk/xa0/tulkki/data/upload/UploadFileEntity.kt")
        val m = Pattern.compile(
            "@ColumnInfo\\(name = \"(\\w+)\"\\)\\s*val\\s+\\w+\\s*:\\s*\\w+(\\?)?")
            .matcher(kt)
        val out = LinkedHashMap<String, Boolean>()
        while (m.find()) {
            out[m.group(1)] = m.group(2) == null
        }
        Assert.assertFalse("no @ColumnInfo declarations found in the entity", out.isEmpty())
        return out
    }

    /** The snapshot's own view of `cids`: NOT NULL is written out in the SQL, so it is readable. */
    private fun snapshotColumns(sql: String, table: String): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        val m = create.matcher(sql)
        while (m.find()) {
            if (!m.group(1).equals(table, ignoreCase = true)) {
                continue
            }
            for (part in splitTopLevel(m.group(2))) {
                val decl = part.trim()
                if (decl.isEmpty() || decl.uppercase().startsWith("FOREIGN KEY") ||
                    decl.uppercase().startsWith("PRIMARY KEY") ||
                    decl.uppercase().startsWith("UNIQUE")) {
                    continue
                }
                out[decl.split(Regex("\\s+"))[0]] = decl.uppercase().contains("NOT NULL")
            }
        }
        val a = alter.matcher(sql)
        while (a.find()) {
            if (a.group(1).equals(table, ignoreCase = true)) {
                out[a.group(2)] = false
            }
        }
        Assert.assertFalse("the snapshot has no " + table, out.isEmpty())
        return out
    }

    /**
     * Step 1 (b): the one declared entity's columns, in name and in nullability, are the ones the
     * device's file has. Affinity is not compared here because it cannot be: the snapshot's types are
     * the SQL spellings and the entity's are Kotlin, and it is Room that normalises them - the whole
     * point of choosing the one all-TEXT table is that its normalisation is the identity.
     */
    @Test
    fun theDeclaredEntityMatchesTheSnapshotForCids() {
        val sql = stripComments(snapshot())
        Assert.assertEquals(
            "the entity must declare exactly the columns the device's `cids` has, and the same " +
                "NOT NULLs: a missing or renamed column is a refused open on the owner's file",
            snapshotColumns(sql, "cids"),
            declaredEntity())
        val upper = sql.uppercase()
        for (type in arrayOf("cid TEXT", "path TEXT", "url TEXT")) {
            Assert.assertTrue(
                "`cids` must still declare every column with a TEXT type; any other type would " +
                    "change the normalised affinity the entity is validated against: " + type,
                upper.contains(type.uppercase()))
        }
    }
}
