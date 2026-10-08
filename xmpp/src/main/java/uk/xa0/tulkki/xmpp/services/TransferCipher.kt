package uk.xa0.tulkki.xmpp.services

import android.util.Log
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.io.CipherInputStream
import org.bouncycastle.crypto.io.CipherOutputStream
import org.bouncycastle.crypto.modes.AEADBlockCipher
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.DownloadableFileRef
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The AES-GCM wrapping the file-transfer layer puts round a download's plaintext and an upload's
 * ciphertext.
 *
 * <p>Split out of `AbstractConnectionManager`, whose Java surface keeps forwarding to it. Both
 * halves are static and stateless - they take the key and IV off the `DownloadableFileRef` and
 * decide nothing themselves - so this is an `object` and nothing here holds a service.
 *
 * <p><strong>One contract is deliberately nullable.</strong> `OutputStream?` is not defensive: the
 * Java it replaces logged the failure and returned `null`, and every Java caller then derefs the
 * answer without a check (`ByteStreams.copy(is, os)`, `outputStream.write(...)`), so `null` is the
 * answer they must keep getting. A non-null Kotlin return would trade that caller-side NPE for a
 * Kotlin intrinsic NPE at the return line.
 */
internal object TransferCipher {

    fun upgrade(file: DownloadableFileRef, input: InputStream): InputStream {
        val key = file.getKey()
        val iv = file.getIv()
        if (key != null && iv != null) {
            val cipher: AEADBlockCipher = GCMBlockCipher(AESEngine())
            cipher.init(true, AEADParameters(KeyParameter(key), 128, iv))
            return CipherInputStream(input, cipher)
        }
        return input
    }

    fun createOutputStream(
            file: DownloadableFileRef,
            append: Boolean,
            decrypt: Boolean
    ): OutputStream? {
        val os: FileOutputStream = try {
            FileOutputStream(file.asFile(), append)
        } catch (e: FileNotFoundException) {
            Log.d(Config.LOGTAG, "unable to create output stream", e)
            return null
        }
        val key = file.getKey()
        if (key == null || !decrypt) {
            return os
        }
        return try {
            val cipher: AEADBlockCipher = GCMBlockCipher(AESEngine())
            cipher.init(false, AEADParameters(KeyParameter(key), 128, file.getIv()))
            CipherOutputStream(os, cipher)
        } catch (e: Exception) {
            Log.d(Config.LOGTAG, "unable to create cipher output stream", e)
            null
        }
    }
}
