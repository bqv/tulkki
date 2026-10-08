package uk.xa0.tulkki.xmpp.services

import android.graphics.Bitmap
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: a CAPTCHA the registration flow must show, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. **Every parameter is non-null**, and the boundary is the Kotlin home
 * `UiUpdateDispatch.displayCaptchaRequest` (chunk C49), which already declares
 * `account: AccountRef, id: String, data: Data, captcha: Bitmap` non-null and hands the
 * `Bitmap.createScaledBitmap` result straight to the listener. The one implementer,
 * `EditAccountActivity.onCaptchaRequested`, also declares all four non-null. So the Java body that
 * would have dereferenced them unconditionally is the reading Kotlin already enforces one frame up.
 */
interface OnCaptchaRequested {
    fun onCaptchaRequested(account: AccountRef, id: String, data: Data, captcha: Bitmap)
}
