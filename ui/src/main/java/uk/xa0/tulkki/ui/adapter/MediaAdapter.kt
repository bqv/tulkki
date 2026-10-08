package uk.xa0.tulkki.ui.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.color.MaterialColors

import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.utils.Attachment
import uk.xa0.tulkki.data.utils.MimeUtils
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.media.MediaAlbumEntry
import uk.xa0.tulkki.ui.media.MediaIcons
import uk.xa0.tulkki.ui.media.mediaAlbum
import uk.xa0.tulkki.ui.util.ViewUtil
import uk.xa0.tulkki.ui.widget.SquareFrameLayout

import java.io.File
import java.lang.ref.WeakReference
import java.util.HashSet
import java.util.concurrent.RejectedExecutionException

/**
 * The media list's RecyclerView adapter, for the two hosts that still draw one list inside a Java
 * screen: contact details and group details. The media browser and the media viewer no longer use it
 * at all - their rows are Compose ([uk.xa0.tulkki.ui.media.MediaBrowserScreen]) - so what is left here
 * is the RecyclerView bridge those two screens still need.
 *
 * <p>**Its item layouts are gone.** `item_media.xml` and `item_date_separator.xml` were the last
 * layouts this adapter inflated, and deleting a layout means the view is built here instead: the same
 * `SquareFrameLayout`, the same 2 dp frame, the same `centerInside` image, the same `#80000000`
 * selection overlay and trailing check, the same wrap-content, 8 dp, subtitle-2, secondary-coloured
 * date heading. Nothing about how a row looks or behaves changed; it is one verb - the row markup
 * became code - and the icon map moved to [MediaIcons] so the Compose grid reads the same copy.
 */
class MediaAdapter @JvmOverloads constructor(
    private val activity: XmppActivity,
    @DimenRes mediaSizeRes: Int,
    private val showDateSeparators: Boolean = true,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var mediaSize: Int = Math.round(activity.getResources().getDimension(mediaSizeRes))

    // Tulkki: 3.7 C5-E3 - `items` stays untyped on purpose: it mixes `DateSeparator` with the
    // attachments, and the pattern match at `onBindViewHolder` is `item is Attachment`, i.e.
    // against the *model*, because the objects that travel this list really are models. A ref
    // pattern there would be a weaker, different test. Everything else in this file that only reads
    // ref members speaks `uk.xa0.tulkki.libs.AttachmentRef`; `:ui` writes it in full and imports
    // nothing.
    private val items = ArrayList<Any>()

    private var selectedAttachments: HashSet<uk.xa0.tulkki.libs.AttachmentRef> = HashSet()

    private var selectionMode = false

    private var selectionChangedListener: OnSelectionChangedListener? = null

    fun interface OnSelectionChangedListener {
        fun onSelectionChanged(count: Int)
    }

    fun setOnSelectionChangedListener(listener: OnSelectionChangedListener?) {
        this.selectionChangedListener = listener
    }

    fun setSelectedAttachments(selectedAttachments: HashSet<uk.xa0.tulkki.libs.AttachmentRef>) {
        this.selectedAttachments = selectedAttachments
        this.selectionMode = selectedAttachments.isNotEmpty()
        notifyDataSetChanged()
    }

    fun toggleSelection(attachment: uk.xa0.tulkki.libs.AttachmentRef) {
        if (selectedAttachments.contains(attachment)) {
            selectedAttachments.remove(attachment)
            if (selectedAttachments.isEmpty()) {
                selectionMode = false
            }
        } else {
            selectedAttachments.add(attachment)
            selectionMode = true
        }
        notifyDataSetChanged()
        selectionChangedListener?.onSelectionChanged(selectedAttachments.size)
    }

    fun clearSelection() {
        selectedAttachments.clear()
        selectionMode = false
        notifyDataSetChanged()
        selectionChangedListener?.onSelectionChanged(0)
    }

    fun getSelectedAttachments(): HashSet<uk.xa0.tulkki.libs.AttachmentRef> {
        return selectedAttachments
    }

    fun isSelectionMode(): Boolean {
        return selectionMode
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is DateSeparator) VIEW_TYPE_DATE_SEPARATOR else VIEW_TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.getContext()
        return if (viewType == VIEW_TYPE_DATE_SEPARATOR) {
            DateSeparatorViewHolder(newDateSeparator(context))
        } else {
            MediaViewHolder(newMediaRow(context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is MediaViewHolder && item is Attachment) {
            val row = holder.row
            if (item.renderThumbnail()) {
                loadPreview(item, row.media)
            } else {
                cancelPotentialWork(item, row.media)
                renderPreview(item, row.media)
            }

            val isSelected = selectedAttachments.contains(item)
            row.selectionOverlay.setVisibility(if (isSelected) View.VISIBLE else View.GONE)
            row.selectionCheck.setVisibility(if (isSelected) View.VISIBLE else View.GONE)

            row.root.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(item)
                } else {
                    val convUuid = activity.getIntent().getStringExtra("conversation_uuid")
                    val accountUuid = activity.getIntent().getStringExtra("account")
                    val jid = activity.getIntent().getStringExtra("jid")
                    ViewUtil.view(activity, item, convUuid, accountUuid, jid)
                }
            }

            row.root.setOnLongClickListener {
                if (!selectionMode) {
                    toggleSelection(item)
                    true
                } else {
                    false
                }
            }

            row.root.setOnCreateContextMenuListener { menu, _, _ ->
                if (selectionMode) return@setOnCreateContextMenuListener
                val path = FileBackends.get().getOriginalPath(item.getUri())
                    ?: return@setOnCreateContextMenuListener
                val file = File(path)
                if (!file.canWrite()) return@setOnCreateContextMenuListener

                menu.add("Delete File").setOnMenuItemClickListener {
                    if (file.delete()) {
                        activity.xmppConnectionService.evictPreview(file)
                        items.remove(item)
                        notifyDataSetChanged()
                    }
                    true
                }
            }
        } else if (holder is DateSeparatorViewHolder && item is DateSeparator) {
            holder.date.setText(
                android.text.format.DateUtils.formatDateTime(
                    activity,
                    item.timestamp,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or
                        android.text.format.DateUtils.FORMAT_SHOW_YEAR or
                        android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY))
        }
    }

    // Tulkki: 3.7 C5-E3 - the parameter is the island's list, because the media path speaks
    // `uk.xa0.tulkki.libs.AttachmentRef` from the service down. `items` is `ArrayList<Object>`, so the
    // elements go in unchanged and `onBindViewHolder`'s model `is` still sees models.
    fun setAttachments(attachments: List<uk.xa0.tulkki.libs.AttachmentRef>?) {
        this.items.clear()
        if (!attachments.isNullOrEmpty()) {
            // The interleave lives in `mediaAlbum` now, because the Compose media grid needs the same
            // rows and two copies of this arithmetic could disagree about where a day heading goes.
            for (entry in mediaAlbum(attachments, showDateSeparators)) {
                when (entry) {
                    is MediaAlbumEntry.Day -> items.add(DateSeparator(entry.timestamp))
                    is MediaAlbumEntry.Row -> items.add(entry.attachment)
                }
            }
        }
        notifyDataSetChanged()
    }

    private fun setMediaSize(mediaSize: Int) {
        this.mediaSize = mediaSize
    }

    private fun loadPreview(attachment: uk.xa0.tulkki.libs.AttachmentRef, imageView: ImageView) {
        if (cancelPotentialWork(attachment, imageView)) {
            val bm = FileBackends.get().getPreviewForUri(attachment, mediaSize, true)
            if (bm != null) {
                cancelPotentialWork(attachment, imageView)
                imageView.setImageBitmap(bm)
                imageView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                // TODO consider if this is still a good, general purpose loading color
                imageView.setBackgroundColor(0xff333333.toInt())
                imageView.setImageDrawable(null)
                val task = BitmapWorkerTask(mediaSize, imageView)
                val asyncDrawable = AsyncDrawable(activity.getResources(), null, task)
                imageView.setImageDrawable(asyncDrawable)
                try {
                    task.execute(attachment)
                } catch (ignored: RejectedExecutionException) {
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }

    /**
     * One media row's three views, which is all the deleted `item_media.xml` carried. `MediaAdapter`
     * owns it because only `MediaAdapter` builds one.
     */
    class MediaRow(
        val root: SquareFrameLayout,
        val media: ImageView,
        val selectionOverlay: View,
        val selectionCheck: ImageView,
    )

    class MediaViewHolder(val row: MediaRow) : RecyclerView.ViewHolder(row.root)

    class DateSeparatorViewHolder(val date: TextView) : RecyclerView.ViewHolder(date)

    class DateSeparator(@JvmField val timestamp: Long)

    private class AsyncDrawable(res: Resources, bitmap: Bitmap?, bitmapWorkerTask: BitmapWorkerTask) :
        BitmapDrawable(res, bitmap) {

        private val bitmapWorkerTaskReference: WeakReference<BitmapWorkerTask> =
            WeakReference(bitmapWorkerTask)

        fun getBitmapWorkerTask(): BitmapWorkerTask? {
            return bitmapWorkerTaskReference.get()
        }
    }

    private class BitmapWorkerTask(private val mediaSize: Int, imageView: ImageView) :
        AsyncTask<uk.xa0.tulkki.libs.AttachmentRef, Void, Bitmap?>() {

        private val imageViewReference: WeakReference<ImageView> = WeakReference(imageView)

        var attachment: uk.xa0.tulkki.libs.AttachmentRef? = null

        override fun doInBackground(vararg params: uk.xa0.tulkki.libs.AttachmentRef): Bitmap? {
            val att = params[0]
            this.attachment = att
            val activity = XmppActivity.find(imageViewReference) ?: return null
            return FileBackends.get().getPreviewForUri(att, mediaSize, false)
        }

        override fun onPostExecute(bitmap: Bitmap?) {
            if (bitmap != null && !isCancelled) {
                val imageView = imageViewReference.get()
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.setBackgroundColor(0x00000000)
                }
            }
        }
    }

    companion object {

        const val VIEW_TYPE_MEDIA = 0

        const val VIEW_TYPE_DATE_SEPARATOR = 1

        // The three families live in MimeUtils now (pair 8b): they are pure MIME data, and `:data`'s
        // FileBackend read them here, which is a forbidden `:data` -> `:ui` import. The names are kept as
        // aliases so every `:ui` caller is unchanged.
        @JvmField
        val DOCUMENT_MIMES: List<String> = MimeUtils.DOCUMENT_MIMES

        @JvmField
        val SPREAD_SHEET_MIMES: List<String> = MimeUtils.SPREAD_SHEET_MIMES

        @JvmField
        val SLIDE_SHOW_MIMES: List<String> = MimeUtils.SLIDE_SHOW_MIMES

        @JvmStatic
        fun setMediaSize(recyclerView: RecyclerView, mediaSize: Int) {
            val adapter = recyclerView.getAdapter()
            if (adapter is MediaAdapter) {
                adapter.setMediaSize(mediaSize)
            }
        }

        // Tulkki: 3.7 C5-E3 - the parameter is the ref, and `getImageDrawable(String)` beside it cannot
        // compete: a `String` is not an `AttachmentRef` and an `Attachment` is not a `String`, so the
        // overload resolution is total. The model keeps `getType()`; the island enum travels on `kind()`.
        @JvmStatic
        @DrawableRes
        fun getImageDrawable(attachment: uk.xa0.tulkki.libs.AttachmentRef): Int {
            return MediaIcons.drawableFor(attachment)
        }

        @JvmStatic
        fun renderPreview(attachment: uk.xa0.tulkki.libs.AttachmentRef, imageView: ImageView) {
            ImageViewCompat.setImageTintList(
                imageView,
                ColorStateList.valueOf(
                    MaterialColors.getColor(
                        imageView, com.google.android.material.R.attr.colorOnSurface)))
            imageView.setImageResource(getImageDrawable(attachment))
            imageView.setBackgroundColor(
                MaterialColors.getColor(
                    imageView, com.google.android.material.R.attr.colorSurfaceContainerHighest))
        }

        /**
         * The deleted `item_media.xml` as views: a square frame padded 2 dp with the borderless
         * selectable ripple, the `centerInside` thumbnail on `colorSurfaceContainerHighest`, the
         * `#80000000` dim overlay and the trailing white check, both `GONE` until the row is selected.
         */
        internal fun newMediaRow(context: Context): MediaRow {
            val frame = dp(context, 2)
            val root = SquareFrameLayout(context)
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            root.setPadding(frame, frame, frame, frame)
            root.background = themedDrawable(context, android.R.attr.selectableItemBackgroundBorderless)

            val media = ImageView(context)
            media.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            media.scaleType = ImageView.ScaleType.CENTER_INSIDE
            media.setBackgroundColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    Color.TRANSPARENT))
            root.addView(media)

            val selectionOverlay = View(context)
            selectionOverlay.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            selectionOverlay.setBackgroundColor(0x80000000.toInt())
            selectionOverlay.visibility = View.GONE
            root.addView(selectionOverlay)

            val checkMargin = dp(context, 4)
            val selectionCheck = ImageView(context)
            val checkParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            checkParams.gravity = Gravity.TOP or Gravity.END
            checkParams.setMargins(checkMargin, checkMargin, checkMargin, checkMargin)
            selectionCheck.layoutParams = checkParams
            selectionCheck.setImageResource(R.drawable.ic_check_24dp)
            ImageViewCompat.setImageTintList(selectionCheck, ColorStateList.valueOf(Color.WHITE))
            selectionCheck.visibility = View.GONE
            root.addView(selectionCheck)

            return MediaRow(root, media, selectionOverlay, selectionCheck)
        }

        /**
         * The deleted `item_date_separator.xml` as a view: one wrap-content heading, 8 dp of padding,
         * `?textAppearanceSubtitle2` and `?colorSecondary`.
         */
        internal fun newDateSeparator(context: Context): TextView {
            val pad = dp(context, 8)
            val view = TextView(context)
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            view.setPadding(pad, pad, pad, pad)
            view.gravity = Gravity.CENTER_VERTICAL
            val appearance = TypedValue()
            if (context.theme.resolveAttribute(
                    com.google.android.material.R.attr.textAppearanceSubtitle2, appearance, true)) {
                view.setTextAppearance(appearance.resourceId)
            }
            view.setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorSecondary,
                    Color.BLACK))
            return view
        }

        private fun dp(context: Context, value: Int): Int {
            return Math.round(value * context.resources.displayMetrics.density)
        }

        /** One theme-attribute drawable, e.g. the borderless selectable ripple. */
        private fun themedDrawable(context: Context, attr: Int): Drawable? {
            val value = TypedValue()
            if (!context.theme.resolveAttribute(attr, value, true) || value.resourceId == 0) {
                return null
            }
            return ContextCompat.getDrawable(context, value.resourceId)
        }

        private fun cancelPotentialWork(attachment: uk.xa0.tulkki.libs.AttachmentRef, imageView: ImageView): Boolean {
            val bitmapWorkerTask = getBitmapWorkerTask(imageView)

            if (bitmapWorkerTask != null) {
                val oldAttachment = bitmapWorkerTask.attachment
                if (oldAttachment == null || oldAttachment != attachment) {
                    bitmapWorkerTask.cancel(true)
                } else {
                    return false
                }
            }
            return true
        }

        private fun getBitmapWorkerTask(imageView: ImageView): BitmapWorkerTask? {
            val drawable: Drawable? = imageView.getDrawable()
            if (drawable is AsyncDrawable) {
                return drawable.getBitmapWorkerTask()
            }
            return null
        }
    }
}
