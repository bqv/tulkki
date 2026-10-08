package uk.xa0.tulkki.xmpp.refs

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import uk.xa0.tulkki.xmpp.pep.Avatar
import io.ipfs.cid.Cid
import java.io.File
import java.io.IOException
import java.io.InputStream
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.libs.DownloadableFileRef

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.FileBackend`.
 *
 * C5-E3's prep step declared this file for the **exception contract** alone; C5-R1 is the step
 * that gives it the island's members. The island's use of the model was the field
 * (`XmppConnectionService.fileBackend`) and the accessor (`getFileBackend()`), and
 * retyping both means declaring here exactly what the island calls - the 31 declarations below over
 * 21 names - so that no island file names `uk.xa0.tulkki.data.FileBackend` at all.
 *
 * The interface is the island's audience and **only** the island's: first-party code binds the
 * same accessor to the concrete model through `uk.xa0.tulkki.data.FileBackends`, which is a `:data`
 * holder rather than a second view of this one. The split is forced rather than tidy, because
 * [getFile] cannot answer both audiences - the island stores the result in
 * [DownloadableFileRef] fields while `:ui` binds it to the model - and one declared return
 * type cannot be both. So a first-party caller keeps the compiler-guaranteed model and this file
 * carries no member only `:ui`/`:app` call (`getThumbnail`, `getAvatar`,
 * `cropCenter`, `drawSVG`, `getStorageLocation` and their siblings stay in
 * `:data`).
 *
 * `convertToAttachments` is deliberately absent: its parameter is
 * `List<DatabaseBackend.FilePath>`, a `:data` nested model that declares no island ref, and
 * its return is `:data`'s `Attachment` list. The island's one call moves to the port
 * (`DataStatics.loadAttachments`) rather than widening this file with two other clusters'
 * surfaces.
 *
 * Two nested types, both declared here and both extended by the model's own:
 *
 * 
 *   - [FileCopyException] - the resource id crosses as the opaque `int` it already is
 *       at the island's catch sites (`callback.error(e.getResId(), ...)`). `:data`'s
 *       `R.string.*` values stay exactly where they are, at throw sites in `:data`; this file
 *       names `androidx.annotation.StringRes` (external) and never `uk.xa0.tulkki.app.R`.
 *   - [ImageCompressionException] - a message and nothing else.
 * 
 *
 * Why the declarations move and the classes do not: `:app` catches
 * `FileBackend.FileCopyException` by name (`AttachFileToConversationRunnable`) and
 * `:app` is allowed to name `:data`. The island catches the **supertype** instead - the
 * subclass is what is thrown on every one of those paths, so the widening is total by construction,
 * not by argument. Nothing here casts: a caught `FileBackendRef.FileCopyException` is the
 * object that was thrown.
 */
interface FileBackendRef {

    /**
     * `FileBackend.FileCopyException`, without a single R reference: the resource id crosses as the
     * opaque int it already is at the island's call sites (`callback.error(e.getResId(), ...)`).
     */
    open class FileCopyException protected constructor(@StringRes val resId: Int) : Exception()

    /** `FileBackend.ImageCompressionException`: a message and nothing else. */
    open class ImageCompressionException protected constructor(message: String) : Exception(message)

    // -- the file/download quartet the island binds -------------------------------------------------
    //
    // `getFile` and `getTemporaryFile` answer `DownloadableFileRef` here while `:data` declares the
    // model, which is a legal covariant override: the model is the only implementor and the compiler
    // checks it. The `.asFile()` bridge is what turns one back into the `File` the JDK stream
    // constructors need, and it is a method on the ref - never a cast.
    fun getFile(message: MessageRef): DownloadableFileRef

    fun getFile(message: MessageRef, decrypted: Boolean): DownloadableFileRef

    fun getFileForPath(path: String): DownloadableFileRef

    fun getTemporaryFile(mimeType: String?): DownloadableFileRef

    // -- the relative-path and media-scanner family -------------------------------------------------
    fun setupRelativeFilePath(message: MessageRef, filename: String)

    fun setupRelativeFilePath(message: MessageRef, filename: String, mime: String?)

    @Throws(IOException::class, uk.xa0.tulkki.xmpp.services.BlockedMediaException::class)
    fun setupRelativeFilePath(message: MessageRef, `is`: InputStream, extension: String?)

    fun updateMediaScanner(file: File)

    fun updateMediaScanner(file: File, callback: Runnable?)

    fun setupNomedia(nomedia: Boolean)

    fun isInternalFile(file: File): Boolean

    fun getOriginalPath(uri: Uri): String?

    fun getStoryCacheDirectory(): File

    // -- the transferable's file params -------------------------------------------------------------
    //
    // `updateFileParams(MessageRef, String, long)` is the one member `:data` had to change shape for
    // (it was `static`); an interface declares an instance member and a static cannot satisfy it. The
    // static `(Message, String, long)` beside it is `:data`'s own and stays.
    fun updateFileParams(message: MessageRef)

    fun updateFileParams(message: MessageRef, url: String?)

    fun updateFileParams(message: MessageRef, url: String?, size: Long)

    fun updateFileParams(message: MessageRef, url: String?, updateCids: Boolean)

    // -- deletion and private-storage copies --------------------------------------------------------
    //
    // Every `File`-taking member below keeps its `File` parameter and gains **no**
    // `DownloadableFileRef` twin: a `DownloadableFile` is both types, so a twin would make `:data`'s
    // own `copyFileToPrivateStorage(getFile(message), uri)` ambiguous and javac would refuse it.
    // `.asFile()` is the bridge that keeps the two overload sets disjoint.
    fun deleteFile(message: MessageRef): Boolean

    fun deleteFile(file: File): Boolean

    @Throws(FileCopyException::class)
    fun copyFileToPrivateStorage(file: File, uri: Uri)

    @Throws(ImageCompressionException::class, FileCopyException::class)
    fun copyImageToPrivateStorage(message: MessageRef, image: Uri)

    @Throws(ImageCompressionException::class, FileCopyException::class)
    fun copyImageToPrivateStorage(file: File, image: Uri)

    @Throws(FileCopyException::class)
    fun copyAttachmentToDownloadsFolder(message: MessageRef)

    @Throws(FileCopyException::class)
    fun copyAttachmentToDownloadsFolder(input: File)

    @Throws(IOException::class)
    fun calculateCids(`is`: InputStream): Array<Cid>

    fun useImageAsIs(uri: Uri): Boolean

    fun unusualBounds(image: Uri): Boolean

    // -- the PEP avatar family (`Avatar` is `:xmpp`'s own type, no edge) -----------------------------
    fun getPepAvatar(image: Uri, size: Int, format: Bitmap.CompressFormat): Avatar?

    fun getStoredPepAvatar(hash: String?): Avatar?

    fun isAvatarCached(avatar: Avatar): Boolean

    fun save(avatar: Avatar): Boolean
}
