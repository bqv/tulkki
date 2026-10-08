package uk.xa0.tulkki.data.model

import android.content.ContentValues
import android.database.Cursor
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.libs.PresenceTemplateRef

/**
 * One presence template: a status, a status message, when it was last used, and its uuid.
 *
 * <p>Ported from Java by the `port` stage (`port-5`, the `:data.model` row; the same model file set
 * `port-2`'s "31 of 78" carries). The decisions, recorded rather than inherited:
 *
 * 1. **Java's two constructors become a private primary and a public secondary.** The public
 *    `(Status, String)` sets `lastUsed` to now and a fresh uuid - `Presences.kt:114` and
 *    `EditAccountActivity.java:1226` are its callers - while the cursor path
 *    (`DatabaseBackend.java:960`) passes the row's own values through the primary. Java's private
 *    no-arg constructor existed only so `fromCursor` could fill the fields; the values now come in
 *    through the constructor instead of being assigned to a companion's access to an inherited
 *    `protected` field.
 * 2. **`TABELNAME` (Java's spelling), `LAST_USED`, `MESSAGE` and `STATUS` are companion `const val`s**,
 *    so `DatabaseBackend.java:930-958`'s Java reads and `PresenceQueries.kt:25-29`'s
 *    `const val TABLE = PresenceTemplate.TABELNAME` both keep a compile-time constant. `UUID` is
 *    **not** restated: no Kotlin file names `PresenceTemplate.UUID`, and Java inherits it from
 *    `AbstractEntity`.
 * 3. **`fromCursor` is `@JvmStatic`** - `DatabaseBackend.java:960` is its only caller.
 *    **Interop debt: one `@JvmStatic`.**
 * 4. **`getStatusMessage()` is nullable**: the row's `MESSAGE` is, `Presences.asTemplates` builds a
 *    template from a nullable `getMessage()`, and `PresenceTemplateAdapter`'s Java filter
 *    dereferences the answer - the NPE there is Java's own. `getStatusRef()` is the distinctly named
 *    island read Java's interface already carried.
 * 5. **[toString] folds `null` to `""`.** Java returned the nullable field; Kotlin's `toString`
 *    cannot return `null`, and the field's only reader is the `ArrayAdapter` row, where a `null` and
 *    an empty string draw the same thing.
 */
class PresenceTemplate private constructor(
    statusValue: Presence.Status,
    statusMessageValue: String?,
    lastUsedValue: Long,
    uuidValue: String?,
) : AbstractEntity(), PresenceTemplateRef {

    private var lastUsed: Long = lastUsedValue
    private var statusMessage: String? = statusMessageValue
    private var status: Presence.Status = statusValue

    init {
        this.uuid = uuidValue
    }

    constructor(status: Presence.Status, statusMessage: String?) : this(
        status,
        statusMessage,
        System.currentTimeMillis(),
        java.util.UUID.randomUUID().toString(),
    )

    override fun getContentValues(): ContentValues {
        val show = status.toShowString()
        val values = ContentValues()
        values.put(LAST_USED, lastUsed)
        values.put(MESSAGE, statusMessage)
        values.put(STATUS, show ?: "")
        values.put(AbstractEntity.UUID, uuid)
        return values
    }

    fun getStatus(): Presence.Status = status

    override fun getStatusRef(): PresenceRef.StatusRef = status.toRef()

    override fun getStatusMessage(): String? = statusMessage

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val template = other as PresenceTemplate

        if (statusMessage != null) {
            if (statusMessage != template.statusMessage) return false
        } else if (template.statusMessage != null) {
            return false
        }
        return status == template.status
    }

    override fun hashCode(): Int {
        var result = statusMessage?.hashCode() ?: 0
        result = 31 * result + status.hashCode()
        return result
    }

    override fun toString(): String = statusMessage ?: ""

    companion object {

        const val TABELNAME = "presence_templates"
        const val LAST_USED = "last_used"
        const val MESSAGE = "message"
        const val STATUS = "status"

        @JvmStatic
        fun fromCursor(cursor: Cursor): PresenceTemplate = PresenceTemplate(
            Presence.Status.fromShowString(cursor.getString(cursor.getColumnIndex(STATUS))),
            cursor.getString(cursor.getColumnIndex(MESSAGE)),
            cursor.getLong(cursor.getColumnIndex(LAST_USED)),
            cursor.getString(cursor.getColumnIndex(AbstractEntity.UUID)),
        )
    }
}
