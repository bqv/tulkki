package uk.xa0.tulkki.data.model

import android.util.Log
import java.io.File
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.DownloadableFileRef

/**
 * The file behind one attachment: the JDK's [File] plus the download metadata the HTTP layer needs.
 *
 * <p>Ported from Java by the `port` stage (port-2). The decisions, recorded rather than inherited:
 *
 * 1. **Only the members this class *declares* carry `override`.** `DownloadableFileRef` is a Java
 *    interface and eight of its fifteen members (`delete`, `exists`, `canRead`, `createNewFile`,
 *    `renameTo`, `getAbsolutePath`, `getName`, `getParentFile`) are satisfied by the inherited
 *    `java.io.File` declarations, exactly as Java let them be - a Kotlin superclass member
 *    implements an interface member, so restating them would be eight bodies that only forward.
 *    The ten the class does declare are `override`s because Kotlin's `override` is mandatory where
 *    Java's `@Override` was advisory.
 * 2. **The three private fields are named `expectedSizeValue`/`aesKeyValue`/`ivValue`.** Their Java
 *    names were `expectedSize`, `aeskey` and `iv`; a Kotlin `private var expectedSize` would
 *    generate a private `getExpectedSize()` beside the explicit override of the ref's member, and
 *    `private var iv` the same beside `getIv()`. The Java fields were private, so no caller sees
 *    the rename.
 * 3. **`this.iv.length` in the log line keeps Java's null dereference, written without `!!`.** Java
 *    reads `this.iv` unconditionally, so a `keyIvCombo` shorter than 32 bytes throws
 *    `NullPointerException` there; `val iv = ivValue ?: throw NullPointerException()` is that
 *    throw, at that site, with Java's own null message, and it is the shape clause 6's "no new
 *    `!!`" leaves. Callers pass a hex-decoded URL fragment (`HttpDownloadConnection:153`,
 *    `HttpUploadConnection:162`), so the branch is reachable only from a malformed reference.
 * 4. **`serialVersionUID` is a `private const val` in the companion.** `File` is `Serializable`, so
 *    the field matters; Kotlin's `const val` in a companion is exactly the `private static final
 *    long` the JDK's serialization reads, and nothing outside the class can see it.
 * 5. **`System.arraycopy` stays `System.arraycopy`.** Kotlin's `copyInto` would be the same copy,
 *    but the JDK call takes a nullable destination through its platform-typed parameters, so the
 *    `ivValue`/`aesKeyValue` nullable fields can be passed without a `!!` and the three branches
 *    read as Java's do.
 * 6. **The string work is Kotlin's exact delegations to the Java calls.** `absolutePath` is
 *    `getAbsolutePath()`, `path.length` is `length()`, `substring(start)` is
 *    `java.lang.String.substring(int)`, `lastIndexOf('.')` is `lastIndexOf(char)`, and
 *    `mime ?: ""` is `mime == null ? "" : mime` - no `trim()`, no case folding, so no `javaTrim`
 *    helper and no locale question arises in this file.
 * 7. **`byteArrayOf(...)` is the IV literal**, element for element, and the two `-byte IV` messages
 *    are the Java concatenation spelled as a template.
 *
 * Nothing is static and no Java caller reads a field as a field, so this file adds **zero** interop
 * debt.
 */
class DownloadableFile : File, DownloadableFileRef {

    private var expectedSizeValue: Long = 0
    private var aesKeyValue: ByteArray? = null
    private var ivValue: ByteArray? = null

    constructor(parent: File?, file: String) : super(parent, file)

    constructor(path: String) : super(path)

    override fun getSize(): Long = super.length()

    override fun getExpectedSize(): Long = expectedSizeValue

    override fun getMimeType(): String {
        val path = absolutePath
        val start = path.lastIndexOf('.') + 1
        return if (start < path.length) {
            MimeUtils.guessMimeTypeFromExtension(path.substring(start)) ?: ""
        } else {
            ""
        }
    }

    override fun setExpectedSize(size: Long) {
        expectedSizeValue = size
    }

    override fun setKeyAndIv(keyIvCombo: ByteArray) {
        // originally, we used a 16 byte IV, then found for aes-gcm a 12 byte IV is ideal
        // this code supports reading either length, with sending 12 byte IV to be done in future
        if (keyIvCombo.size == 48) {
            aesKeyValue = ByteArray(32)
            ivValue = ByteArray(16)
            System.arraycopy(keyIvCombo, 0, ivValue, 0, 16)
            System.arraycopy(keyIvCombo, 16, aesKeyValue, 0, 32)
        } else if (keyIvCombo.size == 44) {
            aesKeyValue = ByteArray(32)
            ivValue = ByteArray(12)
            System.arraycopy(keyIvCombo, 0, ivValue, 0, 12)
            System.arraycopy(keyIvCombo, 12, aesKeyValue, 0, 32)
        } else if (keyIvCombo.size >= 32) {
            aesKeyValue = ByteArray(32)
            ivValue =
                byteArrayOf(
                    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b,
                    0x0c, 0x0d, 0x0e, 0xf
                )
            System.arraycopy(keyIvCombo, 0, aesKeyValue, 0, 32)
        }
        val iv = ivValue ?: throw NullPointerException()
        Log.d(Config.LOGTAG, "using ${iv.size}-byte IV for file transmission")
    }

    override fun setKey(key: ByteArray) {
        aesKeyValue = key
    }

    override fun setIv(iv: ByteArray) {
        ivValue = iv
    }

    override fun getKey(): ByteArray? = aesKeyValue

    override fun getIv(): ByteArray? = ivValue

    /**
     * Tulkki: C5-B - [DownloadableFileRef.asFile], the bridge an interface cannot be. The island's
     * HTTP layer hands this object to the JDK's stream constructors, which take a `File`; everything
     * else in that half of the ref is inherited from [File] and needed no body here.
     */
    override fun asFile(): File = this

    companion object {
        private const val serialVersionUID = 2247012619505115863L
    }
}
