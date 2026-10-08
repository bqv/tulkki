package uk.xa0.tulkki.app.services

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.CancellationSignal
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Hashtable
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.CryptoHelper

/**
 * Serves an account's onboarding QR code as a `content://` PNG, and draws the code itself.
 *
 * <p>**The two `public static` barcode entries are `@JvmStatic` companion members**: `UiAppHost`
 * calls `BarcodeProvider.create2dBarcodeBitmap(...)` twice and `getUriForAccount(...)` once, all
 * three from Java. `AUTHORITY` is a `private const val` beside them, which is the Java's
 * `private static final String`.
 *
 * <p>`getContext()` is nullable, so the two bare dereferences the Java got away with are `context!!`
 * - the same NPE, at the same place. `bitmap.compress` is `bitmap!!.compress` for the same reason:
 * `create2dBarcodeBitmap` answers `null` when zxing throws.
 *
 * <p>**The Java's close-then-flush order is kept verbatim.** `outputStream.close()` followed by
 * `outputStream.flush()` flushes nothing, but it is what the Java did and it is observable only to a
 * reader, never to the file.
 *
 * <p>The `wait`/`notifyAll` handshake would not be Kotlin at all if it was rewritten: `kotlin.Any`
 * exposes no `wait`/`notify`, so the monitor methods are reached through a `java.lang.Object` view of
 * the same `Any`. `synchronized(this)` and `synchronized(lock)` keep their Java nesting, and
 * `waitForService`'s `InterruptedException` is caught by `connectAndWait` exactly where the Java
 * caught it.
 *
 * <p>Name-string audit: `app/src/main/AndroidManifest.xml:403` declares
 * `android:name="uk.xa0.tulkki.app.services.BarcodeProvider"` - the platform instantiates the
 * provider by that string, the FQCN is unchanged, and `manifest-gate` checks it against the dex.
 * 0 hits elsewhere.
 */
class BarcodeProvider : ContentProvider(), ServiceConnection {

    private val lock = Any()

    private var mXmppConnectionService: XmppConnectionService? = null
    private var mBindingInProcess = false

    override fun onCreate(): Boolean {
        val barcodeDirectory =
                File(context!!.getCacheDir().getAbsolutePath() + "/barcodes/")
        if (barcodeDirectory.exists() && barcodeDirectory.isDirectory()) {
            for (file in barcodeDirectory.listFiles()!!) {
                if (file.isFile() && !file.isHidden()) {
                    if (file.delete()) {
                        Log.d(Config.LOGTAG, "deleted old barcode file " + file.getAbsolutePath())
                    }
                }
            }
        }
        return true
    }

    override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        return "image/png"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?
    ): Int {
        return 0
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        return openFile(uri, mode, null)
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(
            uri: Uri,
            mode: String,
            signal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.d(Config.LOGTAG, "opening file with uri (normal): " + uri.toString())
        val path = uri.getPath()
        if (path != null && path.endsWith(".png") && path.length >= 5) {
            val jid = path.substring(1).substring(0, path.length - 4)
            Log.d(Config.LOGTAG, "account:" + jid)
            if (connectAndWait()) {
                Log.d(Config.LOGTAG, "connected to background service")
                try {
                    val account = AccountRegistry.get().findAccountByJid(Jid.of(jid))
                    if (account != null) {
                        val shareableUri = account.getShareableUri()
                        val hash = CryptoHelper.getFingerprint(shareableUri)
                        val file =
                                File(
                                        context!!.getCacheDir().getAbsolutePath() +
                                                "/barcodes/" +
                                                hash)
                        if (!file.exists()) {
                            file.getParentFile().mkdirs()
                            file.createNewFile()
                            val bitmap = create2dBarcodeBitmap(account.getShareableUri(), 1024)
                            val outputStream: OutputStream = FileOutputStream(file)
                            bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                            outputStream.close()
                            outputStream.flush()
                        }
                        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                } catch (e: Exception) {
                    throw FileNotFoundException()
                }
            }
        }
        throw FileNotFoundException()
    }

    private fun connectAndWait(): Boolean {
        val context = getContext()
        val intent = Intent(context, XmppConnectionService::class.java)
        intent.action = this.javaClass.getSimpleName()
        if (context != null) {
            synchronized(this) {
                if (mXmppConnectionService == null && !mBindingInProcess) {
                    Log.d(Config.LOGTAG, "calling to bind service")
                    context.bindService(intent, this, Context.BIND_AUTO_CREATE)
                    this.mBindingInProcess = true
                }
            }
            try {
                waitForService()
                return true
            } catch (e: InterruptedException) {
                return false
            }
        } else {
            Log.d(Config.LOGTAG, "context was null")
            return false
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        synchronized(this) {
            val binder = service as uk.xa0.tulkki.xmpp.services.XmppConnectionBinder
            mXmppConnectionService = binder.getService()
            mBindingInProcess = false
            synchronized(this.lock) {
                (lock as java.lang.Object).notifyAll()
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        synchronized(this) { mXmppConnectionService = null }
    }

    @Throws(InterruptedException::class)
    private fun waitForService() {
        if (mXmppConnectionService == null) {
            synchronized(this.lock) { (lock as java.lang.Object).wait() }
        } else {
            Log.d(Config.LOGTAG, "not waiting for service because already initialized")
        }
    }

    companion object {

        private const val AUTHORITY = ".barcodes"

        @JvmStatic
        fun getUriForAccount(context: Context, account: Account): Uri {
            val packageId = context.getPackageName()
            return Uri.parse(
                    "content://" +
                            packageId +
                            AUTHORITY +
                            "/" +
                            account.getJid().asBareJid() +
                            ".png")
        }

        @JvmStatic
        fun create2dBarcodeBitmap(input: String, size: Int): Bitmap? {
            return create2dBarcodeBitmap(input, size, Color.BLACK, Color.WHITE)
        }

        @JvmStatic
        fun create2dBarcodeBitmap(input: String, size: Int, black: Int, white: Int): Bitmap? {
            try {
                val barcodeWriter = QRCodeWriter()
                val hints = Hashtable<EncodeHintType, Any>()
                hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                hints.put(EncodeHintType.CHARACTER_SET, "UTF-8")
                val result =
                        barcodeWriter.encode(input, BarcodeFormat.QR_CODE, size, size, hints)
                val width = result.getWidth()
                val height = result.getHeight()
                val pixels = IntArray(width * height)
                for (y in 0 until height) {
                    val offset = y * width
                    for (x in 0 until width) {
                        pixels[offset + x] = if (result.get(x, y)) black else white
                    }
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                return bitmap
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "could not generate QR code image", e)
                return null
            }
        }
    }
}
