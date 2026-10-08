package uk.xa0.tulkki.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import java.io.File
import me.drakeet.support.toast.ToastCompat
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.model.DownloadableFile
import uk.xa0.tulkki.data.utils.Attachment
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.xmpp.Config

object ViewUtil {

    @JvmStatic
    fun view(context: Context, attachment: Attachment) {
        view(context, attachment, null, null, null)
    }

    @JvmStatic
    fun view(context: Context, attachment: Attachment, conversationUuid: String?) {
        view(context, attachment, conversationUuid, null, null)
    }

    @JvmStatic
    fun view(context: Context, attachment: Attachment, conversationUuid: String?, accountUuid: String?, jidString: String?) {
        val file = File(attachment.getUri().path)
        val mime = attachment.getMime() ?: "*/*"
        view(context, file, mime, file.name, conversationUuid, attachment.getUuid().toString(), accountUuid, jidString)
    }

    @JvmStatic
    fun view(context: Context, file: DownloadableFile, displayName: String, conversationUuid: String?, messageUuid: String?) {
        if (!file.exists()) {
            Toast.makeText(context, R.string.file_deleted, Toast.LENGTH_SHORT).show()
            return
        }
        var mime = file.getMimeType()
        if (mime.isEmpty()) {
            mime = "*/*"
        }
        view(context, file, mime, displayName, conversationUuid, messageUuid, null, null)
    }

    @JvmStatic
    fun view(context: Context, file: File, mime: String?, displayName: String, conversationUuid: String?, messageUuid: String?) {
        view(context, file, mime, displayName, conversationUuid, messageUuid, null, null)
    }

    @JvmStatic
    fun view(
        context: Context,
        file: File,
        mime: String?,
        displayName: String,
        conversationUuid: String?,
        messageUuid: String?,
        accountUuid: String?,
        jidString: String?,
    ) {
        val effectiveMime = if (mime.isNullOrEmpty()) "*/*" else mime
        Log.d(Config.LOGTAG, "viewing " + file.absolutePath + " " + effectiveMime)
        val uri: Uri
        try {
            uri = FileBackend.getUriForFile(context, file, displayName)
        } catch (e: SecurityException) {
            Log.d(Config.LOGTAG, "No permission to access " + file.absolutePath, e)
            ToastCompat.makeText(
                context,
                context.getString(R.string.no_permission_to_access_x, file.absolutePath),
                ToastCompat.LENGTH_SHORT,
            ).show()
            return
        }
        // use internal viewer for images, videos and audio
        if ((effectiveMime.startsWith("image/") || effectiveMime.startsWith("video/") || effectiveMime.startsWith("audio/")) &&
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(
                "internal_meda_viewer",
                context.resources.getBoolean(R.bool.internal_meda_viewer),
            )
        ) {
            var type = "file"
            if (effectiveMime.startsWith("image/")) {
                type = "image"
            } else if (effectiveMime.startsWith("video/")) {
                type = "video"
            } else if (effectiveMime.startsWith("audio/")) {
                type = "audio"
            }
            UiHost.installed().openMediaViewer(
                context,
                type,
                Uri.fromFile(file),
                conversationUuid,
                messageUuid,
                accountUuid,
                jidString,
            )
        } else {
            val openIntent = Intent(Intent.ACTION_VIEW)
            openIntent.setDataAndType(uri, effectiveMime)
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(openIntent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
