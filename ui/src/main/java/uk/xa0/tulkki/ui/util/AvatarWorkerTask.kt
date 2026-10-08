package uk.xa0.tulkki.ui.util

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.os.Build
import android.widget.ImageView
import androidx.annotation.DimenRes
import java.lang.ref.WeakReference
import java.util.concurrent.RejectedExecutionException
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity

class AvatarWorkerTask private constructor(
    private val imageViewReference: WeakReference<ImageView>?,
    private val activityReference: WeakReference<XmppActivity>?,
    @DimenRes private val size: Int,
) : AsyncTask<Avatarable, Void, Drawable>() {

    private var avatarable: Avatarable? = null

    constructor(imageView: ImageView, @DimenRes size: Int) : this(WeakReference(imageView), null, size)

    constructor(activity: XmppActivity, @DimenRes size: Int) : this(null, WeakReference(activity), size)

    protected override fun doInBackground(vararg params: Avatarable): Drawable? {
        val avatarable = params[0]
        this.avatarable = avatarable
        val activity = if (activityReference == null) {
            XmppActivity.find(imageViewReference ?: throw NullPointerException())
        } else {
            activityReference.get()
        }
        if (activity == null) {
            return null
        }
        return activity.avatarService().get(avatarable, activity.resources.getDimension(size).toInt(), isCancelled())
    }

    protected override fun onPostExecute(bitmap: Drawable?) {
        if (bitmap != null && !isCancelled()) {
            val imageView = imageViewReference?.get()
            if (imageView != null) {
                imageView.setImageDrawable(bitmap)
                imageView.setBackgroundColor(0x00000000)
            }
            if (Build.VERSION.SDK_INT >= 28 && bitmap is AnimatedImageDrawable) {
                bitmap.start()
            }
            val activity = activityReference?.get()
            if (activity != null) {
                activity.refreshUi()
            }
        }
    }

    companion object {
        @JvmStatic
        fun cancelPotentialWork(avatarable: Avatarable, imageView: ImageView): Boolean {
            val workerTask = getBitmapWorkerTask(imageView)

            if (workerTask != null) {
                val old = workerTask.avatarable
                if (old == null || avatarable !== old) {
                    workerTask.cancel(true)
                } else {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun getBitmapWorkerTask(imageView: ImageView?): AvatarWorkerTask? {
            if (imageView != null) {
                val drawable = imageView.getDrawable()
                if (drawable is AsyncDrawable) {
                    return drawable.getAvatarWorkerTask()
                }
            }
            return null
        }

        @JvmStatic
        fun loadAvatar(avatarable: Avatarable, imageView: ImageView, @DimenRes size: Int) {
            if (cancelPotentialWork(avatarable, imageView)) {
                val activity = XmppActivity.find(imageView)
                if (activity == null) {
                    return
                }
                val bm = activity.avatarService().get(avatarable, activity.resources.getDimension(size).toInt(), true)
                setContentDescription(avatarable, imageView)
                if (bm != null) {
                    cancelPotentialWork(avatarable, imageView)
                    imageView.setImageDrawable(bm)
                    imageView.setBackgroundColor(0x00000000)
                    if (Build.VERSION.SDK_INT >= 28 && bm is AnimatedImageDrawable) {
                        bm.start()
                    }
                } else {
                    imageView.setBackgroundColor(avatarable.getAvatarBackgroundColor())
                    imageView.setImageDrawable(null)
                    val task = AvatarWorkerTask(imageView, size)
                    val asyncDrawable = AsyncDrawable(activity.resources, null, task)
                    imageView.setImageDrawable(asyncDrawable)
                    try {
                        task.execute(avatarable)
                    } catch (ignored: RejectedExecutionException) {
                    }
                }
            }
        }

        private fun setContentDescription(avatarable: Avatarable, imageView: ImageView) {
            val context = imageView.getContext()
            if (avatarable is Account) {
                imageView.setContentDescription(context.getString(R.string.your_avatar))
            } else {
                imageView.setContentDescription(context.getString(R.string.avatar_for_x, avatarable.getAvatarName()))
            }
        }
    }

    private class AsyncDrawable(
        res: Resources,
        bitmap: Bitmap?,
        workerTask: AvatarWorkerTask,
    ) : BitmapDrawable(res, bitmap) {
        private val avatarWorkerTaskReference = WeakReference(workerTask)

        fun getAvatarWorkerTask(): AvatarWorkerTask? = avatarWorkerTaskReference.get()
    }
}
