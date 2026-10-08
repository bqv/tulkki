package uk.xa0.tulkki.xmpp.services

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import uk.xa0.tulkki.libs.DownloadableFileRef
import java.io.FileInputStream
import java.io.IOException

/**
 * The streaming `RequestBody` an upload sends: it reads the file once, reports progress as it goes
 * and never buffers the whole transfer.
 *
 * <p>Split out of `AbstractConnectionManager`. The body is only ever built through the static
 * `AbstractConnectionManager.requestBody`, which forwards here, so nothing of the old class's
 * except its nested `ProgressListener` type is named.
 *
 * <p>Three things are held exactly as the Java held them: `contentLength()` still adds the 16-byte
 * GCM tag only when there is a key; `contentType()` is still nullable, because `MediaType.parse`
 * can answer `null` and the Java declared `@Nullable`; and `writeTo` still declares
 * `throws IOException`, because that is the slot it overrides and Java is entitled to catch it.
 *
 * <p>`contentType()` is the one place a caller's *NPE* is preserved rather than a nullable return:
 * the Java called okhttp 4's `MediaType.parse(file.getMimeType())`, which is `String.toMediaTypeOrNull()`
 * behind an intrinsic non-null parameter, so a null mime type threw there. The extension call below
 * keeps that - a null platform-typed receiver throws in the same method.
 */
internal object TransferRequestBody {

    fun create(
            file: DownloadableFileRef,
            progressListener: AbstractConnectionManager.ProgressListener
    ): RequestBody = object : RequestBody() {

        override fun contentLength(): Long = file.getSize() + if (file.getKey() != null) 16 else 0

        override fun contentType(): MediaType? = file.getMimeType().toMediaTypeOrNull()

        @Throws(IOException::class)
        override fun writeTo(sink: BufferedSink) {
            var transmitted = 0L
            TransferCipher.upgrade(file, FileInputStream(file.asFile())).source().use { source ->
                var read = source.read(sink.buffer(), 8196)
                while (read != -1L) {
                    transmitted += read
                    sink.flush()
                    progressListener.onProgress(transmitted)
                    read = source.read(sink.buffer(), 8196)
                }
            }
        }
    }
}
