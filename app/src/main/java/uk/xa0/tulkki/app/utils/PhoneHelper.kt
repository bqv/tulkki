package uk.xa0.tulkki.app.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.Profile
import android.provider.Settings
import com.google.common.base.Strings
import uk.xa0.tulkki.app.services.AbstractContactListSyncService

/**
 * The device-identity and contact-profile reads the onboarding and contact-sync paths need.
 *
 * <p>An `object` with `@JvmStatic`s: every member is static and the Java declared no constructor, so
 * by the same reasoning as [WakeLockHelper] nothing could construct it meaningfully - and `grep`
 * finds no `new PhoneHelper`.
 *
 * <p><strong>The try-with-resources is the one construct without a one-to-one Kotlin keyword.</strong>
 * Java's `try (Cursor cursor = query(...))` closes the cursor on every exit *and* tolerates a `null`
 * resource; Kotlin's `use` on a null receiver is exactly that (the stdlib's `Closeable?` overload
 * skips the close), so `query(...)?.use { }` reproduces both halves - the close on the `return null`
 * paths and on a throw, and the no-op when the provider answered `null`.
 *
 * <p>`isEmulator`'s long `||` chain of `startsWith`/`contains` is kept in the Java's order and with
 * the Java's spelling: reordering it would be a behaviour change on a device whose fingerprint
 * matches two spellings.
 */
object PhoneHelper {

    @SuppressLint("HardwareIds")
    @JvmStatic
    fun getAndroidId(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    @JvmStatic
    fun getProfilePictureUri(context: Context): Uri? {
        if (!AbstractContactListSyncService.isContactListIntegration(context) ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
                                        PackageManager.PERMISSION_GRANTED)) {
            return null
        }
        val projection = arrayOf(Profile._ID, Profile.PHOTO_URI)
        context.contentResolver
                .query(Profile.CONTENT_URI, projection, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val photoUri = cursor.getString(1)
                        if (Strings.isNullOrEmpty(photoUri)) {
                            return null
                        }
                        return Uri.parse(photoUri)
                    }
                }
        return null
    }

    @JvmStatic
    fun isEmulator(): Boolean =
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                    Build.FINGERPRINT.startsWith("generic") ||
                    Build.FINGERPRINT.startsWith("unknown") ||
                    Build.HARDWARE.contains("goldfish") ||
                    Build.HARDWARE.contains("ranchu") ||
                    Build.MODEL.contains("google_sdk") ||
                    Build.MODEL.contains("Emulator") ||
                    Build.MODEL.contains("Android SDK built for x86") ||
                    Build.MANUFACTURER.contains("Genymotion") ||
                    Build.PRODUCT.contains("sdk_google") ||
                    Build.PRODUCT.contains("google_sdk") ||
                    Build.PRODUCT.contains("sdk") ||
                    Build.PRODUCT.contains("sdk_x86") ||
                    Build.PRODUCT.contains("sdk_gphone64_arm64") ||
                    Build.PRODUCT.contains("vbox86p") ||
                    Build.PRODUCT.contains("emulator") ||
                    Build.PRODUCT.contains("simulator")
}
