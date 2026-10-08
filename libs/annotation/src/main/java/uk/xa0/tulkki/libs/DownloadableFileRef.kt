package uk.xa0.tulkki.libs

import java.io.File
import java.io.IOException

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.DownloadableFile`.
 *
 * Declared in the island and implemented by the model class in `:data`, which needs no edit to
 * satisfy it: `DownloadableFile extends java.io.File`, and `File.delete()` already answers this
 * ref's one member with exactly the signature it declares.
 *
 * Its consumer is `MessageParser`'s retraction path. The island asks the service for the file behind
 * a `cid:` thumbnail (`XmppConnectionService.getFileForCid`), holds the answer in a ref, tells the
 * service to drop the preview and then deletes the file. The `evictPreview` half is an island
 * overload, because a parameter type is not covariant.
 *
 * **C5-B grew it by the JDK `File` half** the HTTP layer needs: `AbstractConnectionManager` and
 * `HttpDownloadConnection` hold a download as a file, hand it to `FileInputStream`/`FileOutputStream`,
 * rename it and read its size. That half is why `asFile()` exists - a ref is not a `File`, so the
 * two stream constructions need the object back (the model's one-line bridge), while everything else
 * (`delete`, `exists`, `getAbsolutePath`, `getName`, `getParentFile`, `renameTo`, `getSize`) is
 * inherited unchanged and costs `:data` nothing.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was
 * `uk.xa0.tulkki.xmpp.refs.DownloadableFileRef` - the island's name for `:data`'s
 * `DownloadableFile` while `:xmpp` could not name `:data`. `:libs` is the one module in the order
 * that every side may reach, so the interface moves here and the ref file is its `git rm`; the move
 * is **package-only** and every namer changes only its import line. **Only the interface moves**:
 * the `:data` class and everything behind it stay put, so nothing is widened.
 *
 * **Respell from Java (2026-10-08, lane `G`).** Nineteen members. The nullabilities are the model's
 * own, not a choice: `DownloadableFile.getKey()`/`getIv()` answer `ByteArray?` (`:112`, `:114`) and
 * `getParentFile()` is the JDK's nullable answer, while `asFile()`, `getSize()`, `getExpectedSize()`,
 * `getMimeType()` and the three setters are non-null in the model. `createNewFile()` keeps the
 * JDK's checked `IOException` through `@Throws`, so a Java caller's `catch` still compiles. The ten
 * members the model inherits from `java.io.File` are satisfied by that inheritance, exactly as
 * `FilePathInfoRef`'s `getPath()` was - measured with kotlinc 2.3.21.
 */
interface DownloadableFileRef {

    /** `File.delete()` - `boolean`, because the model inherits the JDK's declaration. */
    fun delete(): Boolean

    // -- C5-B: the JDK `File` half ------------------------------------------------------------------

    /**
     * The model itself, so it can be handed to the JDK stream constructors, which take a `File` and
     * cannot take an interface. `DownloadableFile.asFile()` is this ref's one `@Override` in `:data`.
     */
    fun asFile(): File

    fun exists(): Boolean

    /**
     * `File.canRead()` - C5-E3's one addition, for the two `:ui` binds and `IqGenerator`'s `:792`
     * that ask a cid's file whether it is present before using it. `DownloadableFile extends File`
     * inherits the declaration, so `:data` needs no edit; adding a member to the interface is
     * compiler-checked against every implementor, so "the model is the only one" is not a grep.
     */
    fun canRead(): Boolean

    @Throws(IOException::class)
    fun createNewFile(): Boolean

    fun renameTo(destination: File): Boolean

    fun getAbsolutePath(): String

    fun getName(): String

    fun getParentFile(): File?

    fun getSize(): Long

    // -- C5-B: the download metadata, read by the HTTP layer and `IqGenerator` ----------------------

    fun getExpectedSize(): Long

    fun setExpectedSize(size: Long)

    fun getMimeType(): String

    fun getKey(): ByteArray?

    fun getIv(): ByteArray?

    fun setKey(key: ByteArray)

    fun setIv(iv: ByteArray)

    /** The OMEMO key/IV pair the download's URL fragment carried. */
    fun setKeyAndIv(keyIvCombo: ByteArray)
}
