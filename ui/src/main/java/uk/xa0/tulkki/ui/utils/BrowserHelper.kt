package uk.xa0.tulkki.ui.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.ui.XmppActivity

object BrowserHelper {

    private fun launchNativeApi30(context: Context, uri: Uri): Boolean {
        val nativeAppIntent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER
            )
        return try {
            context.startActivity(nativeAppIntent)
            true
        } catch (ex: ActivityNotFoundException) {
            false
        }
    }

    private fun extractPackageNames(infos: List<ResolveInfo>): MutableSet<String> {
        val names = HashSet<String>()
        for (resolveInfo in infos) {
            val packageName = resolveInfo.activityInfo.packageName
            names.add(packageName)
        }
        return names
    }

    private fun launchNativeBeforeApi30(context: Context, uri: Uri): Boolean {
        val pm = context.packageManager

        // Get all Apps that resolve a generic url
        val browserActivityIntent = Intent()
            .setAction(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.fromParts("http", "", null))
        val genericResolvedList = extractPackageNames(
            pm.queryIntentActivities(browserActivityIntent, 0)
        )

        // Get all apps that resolve the specific Url
        val specializedActivityIntent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolvedSpecializedList = extractPackageNames(
            pm.queryIntentActivities(specializedActivityIntent, 0)
        )

        // Keep only the Urls that resolve the specific, but not the generic
        // urls.
        resolvedSpecializedList.removeAll(genericResolvedList)

        // If the list is empty, no native app handlers were found.
        if (resolvedSpecializedList.isEmpty()) {
            return false
        }

        // We found native handlers. Launch the Intent.
        specializedActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(specializedActivityIntent)
        return true
    }

    @JvmStatic
    fun launchUri(context: Context, uri: Uri) {
        val launched = if (Build.VERSION.SDK_INT >= 30) {
            launchNativeApi30(context, uri)
        } else {
            launchNativeBeforeApi30(context, uri)
        }

        val customTab = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("custom_tab", context.resources.getBoolean(R.bool.default_custom_tab))
        if (!customTab) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    R.string.no_application_found_to_open_link,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (!launched) {
            var builder = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                .setBackgroundInteractionEnabled(true)
                .setStartAnimations(context, R.anim.slide_in_right, R.anim.slide_out_left)
                .setExitAnimations(
                    context,
                    android.R.anim.slide_in_left,
                    android.R.anim.slide_out_right
                )
                .setCloseButtonIcon(
                    FileBackend.drawDrawable(context.getDrawable(R.drawable.ic_arrow_back_24dp))
                        ?: throw NullPointerException()
                )
                .setCloseButtonPosition(CustomTabsIntent.CLOSE_BUTTON_POSITION_START)
            if (context is XmppActivity) {
                builder = builder.setColorScheme(
                    if (context.isDark()) {
                        CustomTabsIntent.COLOR_SCHEME_DARK
                    } else {
                        CustomTabsIntent.COLOR_SCHEME_LIGHT
                    }
                )
            }
            builder.build().launchUrl(context, uri)
        }
    }
}
