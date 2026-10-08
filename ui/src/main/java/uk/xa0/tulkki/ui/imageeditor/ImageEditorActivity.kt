package uk.xa0.tulkki.ui.imageeditor

import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.github.chrisbanes.photoview.PhotoView
import com.zomato.photofilters.FilterPack
import com.zomato.photofilters.imageprocessors.Filter
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * The image editor, the rewrite of `medialib.activities.EditActivity`'s entry and framing.
 *
 * <p>The screen itself is [ImageEditorScreen]'s Compose tree; this class keeps only what a Composable
 * cannot: the three surfaces it hands in (the `PhotoView` preview, the third-party `CropImageView`
 * and the Compose [EditorDrawSurface]), the Glide/Bitmap/Exif work, the save path and the three
 * dialogs the sweep has not reached. Every edit step and the save path are the old Activity's,
 * reproduced rather than redesigned. The old `EditActivity.kt:520` caught `GlideException` around
 * `FutureTarget.get()`, which the future never throws; [buildFilterStrip] catches the
 * `ExecutionException` it does throw, so a failed thumbnail load takes the intended fallback
 * (the error toast, then the editor closes) instead of escaping the background thread.
 *
 * <p>The intent contract is the old class's, constants included, so the three Java callers
 * (`ConversationFragment`, `StoriesActivity`, `InterfaceSettingsFragment`) keep compiling: the image
 * arrives as `intent.data`, the toolbar title as [KEY_CHAT_NAME], and the edited file leaves as
 * [KEY_EDITED_URI] with `RESULT_OK`.
 */
class ImageEditorActivity : AppCompatActivity(), CropImageView.OnCropImageCompleteListener {

    companion object {
        const val KEY_CHAT_NAME = "editActivity_chatName"
        const val KEY_EDITED_URI = "editActivity_edited_uri"
    }

    private val session = ImageEditorSession()

    private val preferences by lazy { EditorPreferences(applicationContext) }

    private lateinit var photoView: PhotoView
    private lateinit var cropHost: SurfaceHost
    private lateinit var cropView: CropImageView
    private val drawState = EditorDrawState()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val savingToast = Runnable { toast(R.string.saving) }

    private var uri: Uri? = null
    private var originalUri: Uri? = null
    private var resizeWidth = 0
    private var resizeHeight = 0
    private var oldExif: ExifInterface? = null
    private var filterInitialBitmap: Bitmap? = null
    private var wasDrawCanvasPositioned = false
    private var pendingResizeSize: Point? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        window.statusBarColor = ContextCompat.getColor(this, R.color.black26)

        session.title = intent.getStringExtra(KEY_CHAT_NAME).orEmpty()

        buildSurfaces()
        setTulkkiContent(darkTheme = true) {
            ImageEditorScreen(
                session = session,
                preview = {
                    AndroidView(
                        factory = { photoView },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.setImageBitmap(session.preview) },
                    )
                },
                crop = {
                    AndroidView(factory = { cropHost }, modifier = Modifier.fillMaxSize())
                },
                draw = {
                    val background = session.drawBackground
                    val density = LocalDensity.current
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val surfaceModifier =
                            if (background == null) {
                                Modifier.fillMaxSize()
                            } else {
                                with(density) { Modifier.size(background.width.toDp(), background.height.toDp()) }
                            }
                        EditorDrawSurface(
                            state = drawState,
                            background = background,
                            modifier = surfaceModifier.onSizeChanged { onDrawSurfaceSized(it.width, it.height) },
                        )
                    }
                },
                onBack = { onBackPressedDispatcher.onBackPressed() },
                onDone = { saveImage() },
                onMode = { selectMode(it) },
                onRotate = { cropView.rotateImage(90) },
                onResize = { resizeImage() },
                onFlipHorizontally = { cropView.flipImageHorizontally() },
                onFlipVertically = { cropView.flipImageVertically() },
                onAspectPanel = { toggleAspectPanel() },
                onAspectRatio = { updateAspectRatio(it) },
                onOtherAspectRatio = { pickOtherAspectRatio() },
                onFilter = { applyFilter(session.filters[it]) },
                onDrawColor = { pickDrawColor() },
                onBrushSize = { updateBrushSize(it) },
                onUndo = { drawState.undo() },
            )

            if (session.colorPickerOpen) {
                ColorPickerDialog(
                    initialColor = session.drawColor,
                    recentColors = preferences.colorPickerRecentColors,
                    onDismiss = { session.colorPickerOpen = false },
                    onConfirm = { color ->
                        addRecentColor(color)
                        updateDrawColor(color)
                        session.colorPickerOpen = false
                    },
                )
            }

            if (session.otherAspectRatioOpen) {
                OtherAspectRatioDialog(
                    lastOtherAspectRatio = session.lastOtherAspectRatio,
                    onDismiss = { session.otherAspectRatioOpen = false },
                    onPicked = { aspectRatio ->
                        session.lastOtherAspectRatio = aspectRatio
                        preferences.lastEditorCropOtherAspectRatioX = aspectRatio.first
                        preferences.lastEditorCropOtherAspectRatioY = aspectRatio.second
                        updateAspectRatio(ASPECT_RATIO_OTHER)
                        session.otherAspectRatioOpen = false
                    },
                )
            }

            val resizeSize = pendingResizeSize
            if (session.resizeOpen && resizeSize != null) {
                ResizeDialog(
                    size = resizeSize,
                    onInvalid = { toast(R.string.invalid_values) },
                    onDismiss = { session.resizeOpen = false },
                    onConfirm = { newSize ->
                        resizeWidth = newSize.x
                        resizeHeight = newSize.y
                        session.resizeOpen = false
                        cropView.croppedImageAsync()
                    },
                )
            }
        }

        initEditor()
    }

    private fun buildSurfaces() {
        photoView = PhotoView(this)

        cropView =
            CropImageView(this).apply {
                val options =
                    CropImageOptions().apply {
                        backgroundColor = ContextCompat.getColor(this@ImageEditorActivity, R.color.crop_image_view_background)
                        initialCropWindowPaddingRatio = 0f
                    }
                setImageCropOptions(options)
                guidelines = CropImageView.Guidelines.ON
                setOnCropImageCompleteListener(this@ImageEditorActivity)
            }

        // The cropper rewrites its own LayoutParams from `onLayout`; the plain host is what keeps
        // that request from escaping into Compose's interop layout (see `SurfaceHost`).
        cropHost =
            SurfaceHost(this).apply {
                addView(
                    cropView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
    }

    private fun initEditor() {
        if (intent.data == null) {
            toast(R.string.invalid_image_path)
            finish()
            return
        }

        uri = intent.data!!
        originalUri = uri
        if (uri!!.scheme != "file" && uri!!.scheme != "content") {
            toast(R.string.unknown_file_location)
            finish()
            return
        }

        if (intent.extras?.containsKey(REAL_FILE_PATH) == true) {
            val realPath = intent.extras!!.getString(REAL_FILE_PATH)
            uri =
                when {
                    realPath!!.startsWith("file:/") -> Uri.parse(realPath)
                    else -> Uri.fromFile(File(realPath))
                }
        } else {
            getRealPathFromURI(uri!!)?.let { uri = Uri.fromFile(File(it)) }
        }

        setupDrawButtons()

        if (preferences.lastEditorCropAspectRatio == ASPECT_RATIO_OTHER) {
            if (preferences.lastEditorCropOtherAspectRatioX == 0f) {
                preferences.lastEditorCropOtherAspectRatioX = 1f
            }
            if (preferences.lastEditorCropOtherAspectRatioY == 0f) {
                preferences.lastEditorCropOtherAspectRatioY = 1f
            }
            session.lastOtherAspectRatio = Pair(preferences.lastEditorCropOtherAspectRatioX, preferences.lastEditorCropOtherAspectRatioY)
        }
        updateAspectRatio(preferences.lastEditorCropAspectRatio)

        loadPreviewImage()
    }

    // --- surfaces ---------------------------------------------------------------------------------

    private fun loadPreviewImage() {
        session.surface = EditorSurface.PREVIEW

        val options =
            RequestOptions()
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)

        Glide.with(this)
            .asBitmap()
            .load(uri)
            .apply(options)
            .listener(
                object : RequestListener<Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
                        if (uri != originalUri) {
                            uri = originalUri
                            mainHandler.post { loadPreviewImage() }
                        }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: Target<Bitmap>,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        if (filterInitialBitmap == null) {
                            loadCropImage()
                            session.mode = EditorMode.CROP
                            session.aspectPanelOpen = true
                        }

                        val currentFilter = selectedFilter()
                        if (filterInitialBitmap != null && currentFilter != null && currentFilter.filter.name != getString(R.string.none)) {
                            photoView.post { applyFilter(currentFilter) }
                        } else {
                            filterInitialBitmap = resource
                            session.preview = resource
                        }
                        return false
                    }
                },
            )
            .into(photoView)
    }

    private fun loadCropImage() {
        session.surface = EditorSurface.CROP
        cropView.guidelines = CropImageView.Guidelines.ON
        cropView.setImageUriAsync(uri)
    }

    /**
     * The draw surface's first real size, which is what the vendored `prepareDrawCanvas` waited for:
     * the background is loaded to that size and the surface then draws it. The size is recorded on
     * every change so [EditorDrawState.toBitmap] saves exactly what is on screen.
     */
    private fun onDrawSurfaceSized(width: Int, height: Int) {
        drawState.setCanvasSize(width, height)
        if (wasDrawCanvasPositioned || width == 0 || height == 0) {
            return
        }

        wasDrawCanvasPositioned = true

        val options =
            RequestOptions()
                .format(DecodeFormat.PREFER_ARGB_8888)
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .fitCenter()

        ensureBackgroundThread {
            try {
                val bitmap =
                    Glide.with(applicationContext)
                        .asBitmap()
                        .load(uri)
                        .apply(options)
                        .submit(width, height)
                        .get()
                runOnUiThread { session.drawBackground = bitmap }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    // --- primary actions --------------------------------------------------------------------------

    private fun selectMode(mode: EditorMode) {
        session.mode = if (session.mode == mode) EditorMode.NONE else mode

        when (session.mode) {
            EditorMode.FILTER -> {
                if (session.surface != EditorSurface.PREVIEW) {
                    loadPreviewImage()
                }
                buildFilterStrip()
            }
            EditorMode.CROP -> {
                if (session.surface != EditorSurface.CROP) {
                    loadCropImage()
                }
            }
            EditorMode.DRAW -> {
                session.surface = EditorSurface.DRAW
            }
            EditorMode.NONE -> Unit
        }

        if (session.mode != EditorMode.CROP) {
            session.aspectPanelOpen = false
        }
    }

    private fun buildFilterStrip() {
        if (session.filters.isNotEmpty()) {
            return
        }

        ensureBackgroundThread {
            val thumbnailSize = resources.getDimension(R.dimen.bottom_filters_thumbnail_size).toInt()

            val bitmap =
                try {
                    Glide.with(this)
                        .asBitmap()
                        .load(uri)
                        .submit(thumbnailSize, thumbnailSize)
                        .get()
                } catch (e: ExecutionException) {
                    // `FutureTarget.get()` signals a failed load as an ExecutionException wrapping the
                    // GlideException; catching GlideException here (as the old Activity did) left the
                    // failure uncaught and the fallback below unreachable.
                    showErrorToast(e)
                    finish()
                    return@ensureBackgroundThread
                }

            runOnUiThread {
                val filters = ArrayList<EditorFilter>()
                val noFilter = Filter(getString(R.string.none))
                filters.add(EditorFilter(noFilter.name, noFilter.processFilter(Bitmap.createBitmap(bitmap)), noFilter))
                FilterPack.getFilterPack(this).forEach { filter ->
                    filters.add(EditorFilter(filter.name, filter.processFilter(Bitmap.createBitmap(bitmap)), filter))
                }
                session.selectedFilter = 0
                session.filters = filters
            }
        }
    }

    private fun applyFilter(item: EditorFilter) {
        val base = filterInitialBitmap ?: return
        session.preview = item.filter.processFilter(Bitmap.createBitmap(base))
    }

    private fun selectedFilter() = session.filters.getOrNull(session.selectedFilter)

    // --- crop/rotate ------------------------------------------------------------------------------

    private fun toggleAspectPanel() {
        session.aspectPanelOpen = !session.aspectPanelOpen
        cropView.guidelines = if (session.aspectPanelOpen) CropImageView.Guidelines.ON else CropImageView.Guidelines.OFF
    }

    private fun pickOtherAspectRatio() {
        session.otherAspectRatioOpen = true
    }

    private fun updateAspectRatio(aspectRatio: Int) {
        session.aspectRatio = aspectRatio
        preferences.lastEditorCropAspectRatio = aspectRatio

        cropView.apply {
            if (aspectRatio == ASPECT_RATIO_FREE) {
                setFixedAspectRatio(false)
            } else {
                val newAspectRatio =
                    when (aspectRatio) {
                        ASPECT_RATIO_ONE_ONE -> Pair(1f, 1f)
                        ASPECT_RATIO_FOUR_THREE -> Pair(4f, 3f)
                        ASPECT_RATIO_SIXTEEN_NINE -> Pair(16f, 9f)
                        else -> session.lastOtherAspectRatio ?: return
                    }
                setAspectRatio(newAspectRatio.first.toInt(), newAspectRatio.second.toInt())
            }
        }
    }

    private fun resizeImage() {
        val point = getAreaSize()
        if (point == null) {
            toast(R.string.unknown_error_occurred)
            return
        }

        pendingResizeSize = point
        session.resizeOpen = true
    }

    private fun getAreaSize(): Point? {
        val rect = cropView.cropRect ?: return null
        val rotation = cropView.rotatedDegrees
        return if (rotation == 0 || rotation == 180) {
            Point(rect.width(), rect.height())
        } else {
            Point(rect.height(), rect.width())
        }
    }

    override fun onCropImageComplete(view: CropImageView, result: CropImageView.CropResult) {
        if (result.error == null) {
            setOldExif()
            val bitmap = result.bitmap ?: return
            saveBitmapToFile(bitmap, true)
        } else {
            toast("${getString(R.string.image_editing_failed)}: ${result.error?.message}")
        }
    }

    // --- draw -------------------------------------------------------------------------------------

    private fun setupDrawButtons() {
        updateDrawColor(preferences.lastEditorDrawColor)
        updateBrushSize(preferences.lastEditorBrushSize)
    }

    private fun pickDrawColor() {
        session.colorPickerOpen = true
    }

    /** The vendored `ColorPickerDialog.addRecentColor`: newest first, at most [RECENT_COLORS_NUMBER]. */
    private fun addRecentColor(color: Int) {
        var recentColors = preferences.colorPickerRecentColors

        recentColors.remove(color)
        if (recentColors.size >= RECENT_COLORS_NUMBER) {
            val numberOfColorsToDrop = recentColors.size - RECENT_COLORS_NUMBER + 1
            recentColors = LinkedList(recentColors.dropLast(numberOfColorsToDrop))
        }
        recentColors.addFirst(color)

        preferences.colorPickerRecentColors = recentColors
    }

    private fun updateDrawColor(color: Int) {
        session.drawColor = color
        preferences.lastEditorDrawColor = color
        drawState.updateColor(color)
    }

    private fun updateBrushSize(percent: Int) {
        session.brushSize = percent
        preferences.lastEditorBrushSize = percent
        drawState.updateBrushWidth(resources.getDimension(R.dimen.full_brush_size) * (percent / 100f))
    }

    // --- save -------------------------------------------------------------------------------------

    private fun saveImage() {
        setOldExif()

        when (session.surface) {
            EditorSurface.CROP -> cropView.croppedImageAsync()
            EditorSurface.DRAW -> saveBitmapToFile(drawState.toBitmap(session.drawBackground), true)
            EditorSurface.PREVIEW -> {
                val currentFilter = selectedFilter() ?: return
                toast(R.string.saving)

                session.preview = null
                cropView.setImageBitmap(null)
                session.filters = emptyList()

                ensureBackgroundThread {
                    try {
                        val originalBitmap =
                            Glide.with(applicationContext)
                                .asBitmap()
                                .load(uri)
                                .submit(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                                .get()
                        currentFilter.filter.processFilter(originalBitmap)
                        saveBitmapToFile(originalBitmap, false)
                    } catch (e: OutOfMemoryError) {
                        toast(R.string.out_of_memory_error)
                    }
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun setOldExif() {
        var inputStream: InputStream? = null
        try {
            if (isNougatPlus()) {
                inputStream = contentResolver.openInputStream(uri!!)
                oldExif = ExifInterface(inputStream!!)
            }
        } catch (e: Exception) {
        } finally {
            inputStream?.close()
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, showSavingToast: Boolean) {
        val file = File(cacheDir, "media/${UUID.randomUUID()}.jpg")

        file.deleteRecursively()
        file.parentFile?.mkdirs()

        try {
            ensureBackgroundThread {
                try {
                    val out = FileOutputStream(file)
                    saveBitmap(file, bitmap, out, showSavingToast)
                } catch (e: Exception) {
                    toast(R.string.image_editing_failed)
                }
            }
        } catch (e: Exception) {
            showErrorToast(e)
        } catch (e: OutOfMemoryError) {
            toast(R.string.out_of_memory_error)
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun saveBitmap(file: File, bitmap: Bitmap, out: OutputStream, showSavingToast: Boolean) {
        if (showSavingToast) {
            mainHandler.postDelayed(savingToast, 500)
        }

        if (resizeWidth > 0 && resizeHeight > 0) {
            val resized = Bitmap.createScaledBitmap(bitmap, resizeWidth, resizeHeight, false)
            resized.compress(file.absolutePath.getCompressionFormat(), 90, out)
        } else {
            bitmap.compress(file.absolutePath.getCompressionFormat(), 90, out)
        }

        try {
            if (isNougatPlus()) {
                val newExif = ExifInterface(file.absolutePath)
                oldExif?.copyNonDimensionAttributesTo(newExif)
            }
        } catch (e: Exception) {
        }

        intent.putExtra(KEY_EDITED_URI, file.toUri())
        setResult(Activity.RESULT_OK, intent)
        out.close()
        mainHandler.removeCallbacks(savingToast)
        finish()
    }
}
