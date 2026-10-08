package uk.xa0.tulkki.xmpp.utils

import androidx.annotation.NonNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import uk.xa0.tulkki.libs.Jid

/**
 * The header a Tulkki backup file starts with: version, app, bare JID, timestamp, IV and salt.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **The six fields stay private and the six getters stay *functions*** (`getVersion()`,
 *    `getSalt()`, …) rather than Kotlin properties. Kotlin hides a property's accessor from Kotlin
 *    source, so naming them `version`, `salt`, … would have forced edits at the Kotlin call sites
 *    (`ImportBackupWorker.kt:189`, `:191`, `:195` all say `getSalt()`/`getIv()`) for no behaviour
 *    change. The functions keep the Java-visible surface *and* the Kotlin call shape identical, and
 *    the Java readers (`BackupFile.java:181-182`, `BackupFileAdapter.java:51-58`) are untouched.
 * 2. **`read` and `write` carry `@Throws(IOException::class)`**, which is Java's `throws IOException`
 *    carried through Kotlin: `BackupFile.java:55`, `:66` and `ImportBackupWorker.kt:174` are
 *    callers that catch it.
 * 3. **`VERSION` is a `const val`** in the companion, i.e. the same `public static final int`.
 * 4. **`OutdatedBackupFileVersion` stays a nested class**, so `BackupFileHeader.OutdatedBackupFileVersion`
 *    in `ImportBackupActivity.java:242` is unchanged.
 * 5. **The two constructors are secondary constructors** because the Java ones were: one forwards to
 *    the other with [VERSION], exactly as Java's did.
 */
class BackupFileHeader {

    private val fileVersion: Int
    private val app: String
    private val jid: Jid
    private val timestamp: Long
    private val iv: ByteArray
    private val salt: ByteArray

    @NonNull
    override fun toString(): String =
        "BackupFileHeader{" +
            "version=" +
            fileVersion +
            ", app='" +
            app +
            '\'' +
            ", jid=" +
            jid +
            ", timestamp=" +
            timestamp +
            ", iv=" +
            CryptoHelper.bytesToHex(iv) +
            ", salt=" +
            CryptoHelper.bytesToHex(salt) +
            '}'

    constructor(app: String, jid: Jid, timestamp: Long, iv: ByteArray, salt: ByteArray) :
        this(VERSION, app, jid, timestamp, iv, salt)

    constructor(
        fileVersion: Int,
        app: String,
        jid: Jid,
        timestamp: Long,
        iv: ByteArray,
        salt: ByteArray,
    ) {
        this.fileVersion = fileVersion
        this.app = app
        this.jid = jid
        this.timestamp = timestamp
        this.iv = iv
        this.salt = salt
    }

    @Throws(IOException::class)
    fun write(dataOutputStream: DataOutputStream) {
        dataOutputStream.writeInt(fileVersion)
        dataOutputStream.writeUTF(app)
        dataOutputStream.writeUTF(jid.asBareJid().toString())
        dataOutputStream.writeLong(timestamp)
        dataOutputStream.write(iv)
        dataOutputStream.write(salt)
    }

    fun getVersion(): Int = fileVersion

    fun getSalt(): ByteArray = salt

    fun getIv(): ByteArray = iv

    fun getJid(): Jid = jid

    fun getApp(): String = app

    fun getTimestamp(): Long = timestamp

    class OutdatedBackupFileVersion : RuntimeException()

    companion object {

        const val VERSION: Int = 4

        @JvmStatic
        @Throws(IOException::class)
        fun read(inputStream: DataInputStream): BackupFileHeader {
            val version = inputStream.readInt()
            val app = inputStream.readUTF()
            val jid = inputStream.readUTF()
            val timestamp = inputStream.readLong()
            val iv = ByteArray(12)
            inputStream.readFully(iv)
            val salt = ByteArray(16)
            inputStream.readFully(salt)
            if (version < 2) {
                throw OutdatedBackupFileVersion()
            }
            if (version > VERSION) {
                throw IllegalArgumentException(
                    "Backup File version was " +
                        version +
                        " but app only supports version " +
                        VERSION,
                )
            }
            return BackupFileHeader(version, app, Jid.of(jid), timestamp, iv, salt)
        }
    }
}
