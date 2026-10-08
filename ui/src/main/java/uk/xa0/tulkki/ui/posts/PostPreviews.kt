package uk.xa0.tulkki.ui.posts

import android.app.Activity
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Comment
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog

/*
 * The four dialogs the deleted `PostsAdapter` opened, kept verbatim so the feed's behaviour does
 * not move with its layout.
 *
 * **The first two are Compose dialogs now.** `dialog_image_preview.xml` and
 * `dialog_video_preview.xml` were the two layouts this file inflated after the posts conversion;
 * both are deleted, and the faces are [ImagePreviewDialog] and [VideoPreviewDialog] here - the same
 * Glide load, the same tap-to-dismiss, the same looping `VideoView` with the tree's own
 * `MediaController`, and the same `download_failed_file_not_found` toast. They are hosted by
 * `showTulkkiDialog`, because `PostsActivity` draws the feed with Compose and the preview is the
 * feed's own dialog.
 *
 * The other two are builders and own no layout: the account picker a post with several connected
 * accounts needed, and the long-press list of who liked a post. Both are the deleted adapter's own
 * code, moved, not rewritten.
 */

/** The full-screen image preview: `dialog_image_preview.xml`, dismissed by a tap. */
internal fun showImagePreview(activity: XmppActivity, url: String?) {
    if (url == null) {
        return
    }
    activity.showTulkkiDialog { dismiss ->
        ImagePreviewDialog(url = url, onDismiss = dismiss)
    }
}

/** The full-screen video preview: `dialog_video_preview.xml`, looping, with the media controller. */
internal fun showVideoPreview(activity: XmppActivity, url: String?) {
    if (url == null) {
        return
    }
    activity.showTulkkiDialog { dismiss ->
        VideoPreviewDialog(url = url, onDismiss = dismiss)
    }
}

/**
 * The image preview's face: the deleted layout's one `ImageView`, fit-centred, loaded by Glide and
 * dismissed by a tap. The dialog fills the window rather than taking the platform's minimum width,
 * because the deleted `Dialog` made its window background transparent and let the photo be the
 * window; the whole face is the tap target, so the space around the photo dismisses it too.
 */
@Composable
internal fun ImagePreviewDialog(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setOnClickListener { onDismiss() }
                        Glide.with(this).load(url).into(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The video preview's face: the deleted layout's `FrameLayout` and `VideoView`, with the same
 * `MediaController` anchored to the frame, the same `Glide.asFile` fetch and `Uri.fromFile`
 * `setVideoURI`, the same looping `setOnPreparedListener` and the same failure toast. The dialog
 * fills the window, which is what the deleted `setLayout(MATCH_PARENT, MATCH_PARENT)` asked of its
 * own.
 */
@Composable
internal fun VideoPreviewDialog(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    val videoView = VideoView(context)
                    addView(
                        videoView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    videoView.setMediaController(controller)
                    Glide.with(context)
                        .asFile()
                        .load(url)
                        .into(
                            object : CustomTarget<File>() {
                                override fun onResourceReady(
                                    resource: File,
                                    transition: Transition<in File>?,
                                ) {
                                    videoView.setVideoURI(Uri.fromFile(resource))
                                    videoView.setOnPreparedListener { mp ->
                                        mp.setLooping(true)
                                        videoView.start()
                                    }
                                }

                                override fun onLoadCleared(placeholder: Drawable?) {
                                    // Do nothing
                                }

                                override fun onLoadFailed(errorDrawable: Drawable?) {
                                    Toast.makeText(
                                            context,
                                            uk.xa0.tulkki.xmpp.R.string
                                                .download_failed_file_not_found,
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }
                            },
                        )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * One account for an action that needs exactly one: the account list the deleted adapter showed
 * when more than one was connected. With none connected it says so; with one it just uses it.
 */
internal fun showAccountSelection(
    activity: Activity,
    title: String,
    accounts: List<Account>,
    onAccountSelected: (Account) -> Unit,
) {
    if (accounts.isEmpty()) {
        Toast.makeText(activity, uk.xa0.tulkki.xmpp.R.string.no_active_account, Toast.LENGTH_SHORT)
            .show()
        return
    }
    if (accounts.size == 1) {
        onAccountSelected(accounts[0])
        return
    }
    val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
    val accountMap = HashMap<String, Account>()
    for (account in accounts) {
        val jid = account.getJid().asBareJid().toString()
        adapter.add(jid)
        accountMap[jid] = account
    }

    MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setAdapter(adapter) { _, which ->
            val jid = adapter.getItem(which)!!
            onAccountSelected(accountMap[jid]!!)
        }
        .create()
        .show()
}

/** Who liked the post, from the roster the notification names are read out of. */
internal fun showLikes(activity: Activity, likes: List<Comment>) {
    if (likes.isEmpty()) {
        return
    }
    val likerDisplayNames = ArrayList<String>()
    val accounts = AccountRegistry.get().getAccounts()
    for (like in likes) {
        val authorJid = like.author?.asBareJid()
        if (authorJid != null) {
            var displayName: String? = null
            for (account in accounts) {
                val contact = account.getRoster().getContact(authorJid)
                val contactName = contact.getDisplayName()
                if (contactName.isNotEmpty()) {
                    displayName = contactName
                    break
                }
            }
            if (displayName != null) {
                likerDisplayNames.add(displayName)
            } else {
                likerDisplayNames.add(authorJid.toString())
            }
        }
    }
    val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, likerDisplayNames)
    MaterialAlertDialogBuilder(activity)
        .setTitle(
            activity.resources.getQuantityString(
                R.plurals.liked_by_title,
                likes.size,
                likes.size,
            ),
        )
        .setAdapter(adapter, null)
        .setPositiveButton(R.string.action_close, null)
        .create()
        .show()
}
