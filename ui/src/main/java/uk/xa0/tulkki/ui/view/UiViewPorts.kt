package uk.xa0.tulkki.ui.view

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.style.ClickableSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.view.ViewPorts
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.UriHandlerActivity
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.text.FixedURLSpan
import uk.xa0.tulkki.ui.util.MyLinkify
import uk.xa0.tulkki.ui.util.ShareUtil

object UiViewPorts :
    ViewPorts.LinkedText,
    ViewPorts.Clipboard,
    ViewPorts.SoftKeyboard,
    ViewPorts.ScreenLaunch,
    ViewPorts.UiSettings {

    override fun fixLinks(body: Editable) {
        FixedURLSpan.fix(body)
    }

    override fun extractLinks(body: Editable): List<String> {
        return MyLinkify.extractLinks(body)
    }

    override fun linkSpan(url: String, account: Account?): ClickableSpan {
        return FixedURLSpan(url, account)
    }

    override fun openLink(url: String, account: Account?, widget: View) {
        FixedURLSpan.open(url, account, widget)
    }

    override fun copyText(context: Context, text: CharSequence, labelResource: Int): Boolean {
        return ShareUtil.copyTextToClipboard(context, text.toString(), labelResource)
    }

    override fun copyLink(context: Context, url: String) {
        ShareUtil.copyLinkToClipboard(context, url)
    }

    override fun show(field: View) {
        field.requestFocus()
        val imm = field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (imm != null) {
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun hide(field: View) {
        val imm = field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (imm != null) {
            imm.hideSoftInputFromWindow(field.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS)
        }
    }

    override fun launchUriHandler(context: Context, url: String) {
        val intent = Intent(context, UriHandlerActivity::class.java)
        intent.setAction(Intent.ACTION_VIEW)
        intent.setData(Uri.parse(url))
        context.startActivity(intent)
    }

    override fun loadImageFromAnyLink(): Boolean {
        val service = XmppActivity.staticXmppConnectionService
        return service != null &&
            service.getBooleanPreference("load_image_from_any_link", R.bool.load_image_from_any_link)
    }
}
