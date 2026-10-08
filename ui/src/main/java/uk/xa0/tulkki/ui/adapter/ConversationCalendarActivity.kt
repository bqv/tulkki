package uk.xa0.tulkki.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import java.time.YearMonth
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.utils.Attachment
import uk.xa0.tulkki.libs.AttachmentRef
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent

/**
 * The conversation calendar: one month of the conversation's message counts and thumbnails, and the
 * tap that returns a message's uuid to the conversation.
 *
 * <p>**The calendar is Compose now, and its two resource layouts are gone.** The deleted
 * `calendar_day_layout.xml` is [ConversationCalendar]'s `dayContent` block - the 4 dp-inset oval the
 * `FrameLayout` clipped to, the `colorPrimaryContainer` tint whose alpha rises with the day's
 * message count, the thumbnail in its 4 dp margin and the 16 sp day number - and
 * `calendar_month_header_layout.xml` is its `monthHeader` block (the `noto_sans_medium` 20 sp line
 * with its 16 dp top and bottom padding). The library's Compose artifact
 * (`com.kizitonwose.calendar:compose:2.10.1`) wants no layout resources at all, which is why the
 * day views and the header are lambdas now and the `CalendarView`, its binders and containers, the
 * two `R.layout` names and `AndroidView` have gone.
 *
 * <p>**Nothing else moved.** The click that resolves the day to a message and returns
 * [ConversationListActivity.EXTRA_MESSAGE_UUID] is the day cell's `Modifier.clickable`, the
 * month-scroll refresh (immediate from the cache, 500 ms otherwise) is
 * [onVisibleMonthChanged] - Compose has no listener to set, its `firstVisibleMonth` is observed
 * instead - the oldest-message start month is still [setupCalendar]'s, the total line is still the
 * same string with the same em-dash while a month loads, and the day cell's `harmonizeWithPrimary`
 * tint is `MaterialTheme.colorScheme.primaryContainer`, which carries that tint. The opening scroll
 * is `rememberCalendarState`'s `firstVisibleMonth`, the same instant scroll
 * `calendarView.scrollToMonth(currentMonth)` performed.
 *
 * <p>**The thumbnails load as the cells appear.** The view library bound a `Bitmap` into each
 * visible `ImageView`; here a visible in-month cell decodes its day's preview and draws the deleted
 * layout's `#333333` loading colour until it lands.
 */
class ConversationCalendarActivity : XmppActivity() {

    private lateinit var uuid: String

    private var initialized = false

    private var data by mutableStateOf(emptyMap<Int, Int>())
    private var files by mutableStateOf(emptyMap<Int, Attachment>())

    /** The month whose [data] and [files] are loaded. */
    private var yearMonth by mutableStateOf(YearMonth.now())

    /** The month the calendar opens on, which is [setupCalendar]'s `currentMonth`. */
    private var initialMonth by mutableStateOf(YearMonth.now())

    /** Null until the oldest message has been read; the calendar does not open before then. */
    private var startMonth by mutableStateOf<YearMonth?>(null)

    private var endMonth by mutableStateOf(YearMonth.now())

    private val cache = HashMap<YearMonth, Map<Int, Int>>()

    /** The line the deleted `totalPerMonth` held: `R.string.total_messages_per_month`, or `—`. */
    private var totalLabel by mutableStateOf("")

    private var mediaSize: Int = 0

    private val handler = Handler(Looper.getMainLooper())
    private val refreshDataRunnable = Runnable { refreshMonth() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        mediaSize = resources.getDimensionPixelSize(R.dimen.media_size)

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                // `setSupportActionBar` and `configureActionBar` went with the bar; the label the
                // manifest gives this Activity is what the toolbar drew.
                title = stringResource(R.string.title_activity_calendar),
                // The home arrow `configureActionBar(supportActionBar)` switched on, which ran `finish()`.
                onUp = { finish() },
            ) {
                ConversationCalendarScreen(
                    total = totalLabel,
                    calendar = {
                        // Nothing until the oldest message has been read: the range is
                        // [startMonth, this month] and the opening scroll needs both ends.
                        startMonth?.let { start ->
                            ConversationCalendar(
                                startMonth = start,
                                endMonth = endMonth,
                                firstVisibleMonth = initialMonth,
                                dataMonth = yearMonth,
                                dayCounts = data,
                                dayFiles = files,
                                previews = { attachment -> dayThumbnail(attachment, mediaSize) },
                                onVisibleMonthChanged = ::onVisibleMonthChanged,
                                onDayClick = ::onDayClick,
                                // The deleted host's `layout_marginTop="48dp"` on the CalendarView.
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            )
                        }
                    },
                )
            }
        }
    }

    override fun refreshUiReal() {
    }

    override fun onBackendConnected() {
        uuid = intent.getStringExtra(EXTRA_UUID) ?: ""
        if (uuid == "") {
            finish()
            return
        }

        if (!initialized) {
            setupCalendar()
            refreshDataRunnable.run()
            initialized = true
        }
    }

    private fun setupCalendar() {
        val calendar = Calendar.getInstance()
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, -1)
        if (timestamp >= 0) {
            calendar.timeInMillis = timestamp
        }

        val conversation = xmppConnectionService.findConversationByUuid(uuid)
        val oldest = DatabaseBackend.get().getMessages(conversation ?: throw NullPointerException(), 1, 1, true).firstOrNull()

        val currentMonth = YearMonth.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
        yearMonth = currentMonth
        initialMonth = currentMonth
        endMonth = YearMonth.now()

        startMonth = if (oldest != null) {
            calendar.timeInMillis = oldest.getTimeSent()
            YearMonth.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
        } else {
            currentMonth.minusMonths(1200)
        }
    }

    /**
     * The deleted `refreshDataRunnable`: [yearMonth]'s counts (from the cache when it is there, a
     * query otherwise), its day's files and the month's total, read at the moment it runs - so the
     * 500 ms pass belongs to the month that is visible when it fires.
     */
    private fun refreshMonth() {
        val month = yearMonth

        val cached = cache[month]
        data = if (cached != null) {
            cached
        } else {
            xmppConnectionService.getMessagesCountGroupByDay(uuid, month.year, month.monthValue)
                .also { cache[month] = it }
        }

        files = FileBackends.get().convertToAttachments(
            DatabaseBackend.get().getRelativeFilePathsForConversationForMonth(
                uuid, month.year, month.monthValue
            )
        )

        val total = data.values.sum()
        totalLabel = getString(R.string.total_messages_per_month, total.toString())
    }

    /**
     * The deleted `monthScrollListener`, with the same two timings: a cached month is drawn in this
     * frame, an uncached one after the same 500 ms.
     */
    private fun onVisibleMonthChanged(month: YearMonth) {
        if (yearMonth == month) {
            return
        }

        yearMonth = month
        totalLabel = getString(R.string.total_messages_per_month, "—")
        data = emptyMap()
        files = emptyMap()
        handler.removeCallbacks(refreshDataRunnable)

        if (cache.containsKey(month)) {
            refreshDataRunnable.run()
        } else {
            handler.postDelayed(refreshDataRunnable, 500L)
        }
    }

    /** The deleted binder's `setOnClickListener` on the day cell. */
    private fun onDayClick(day: CalendarDay) {
        val timestamp = day.date.toEpochDay() * 86400 * 1000
        val conversation = xmppConnectionService.findConversationByUuid(uuid)
        val message = DatabaseBackend.get().getMessages(conversation ?: throw NullPointerException(), 1, timestamp, true).firstOrNull()

        val result = Intent()
        result.putExtra(ConversationListActivity.EXTRA_MESSAGE_UUID, message?.getUuid())
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        private const val EXTRA_UUID = "uuid"
        private const val EXTRA_TIMESTAMP = "timestamp"

        fun createIntent(
            context: Context,
            uuid: String,
            timestamp: Long?,
        ): Intent {
            return Intent(context, ConversationCalendarActivity::class.java)
                .apply {
                    putExtra(EXTRA_UUID, uuid)
                    if (timestamp != null) {
                        putExtra(EXTRA_TIMESTAMP, timestamp)
                    }
                }
        }
    }
}

/**
 * The calendar's months, drawn by the kizitonwose **Compose** artifact
 * (`com.kizitonwose.calendar:compose:2.10.1`).
 *
 * <p>It is the deleted host's `CalendarView` in the shapes the library kept: [HorizontalCalendar]
 * because the view library's default orientation is horizontal and the deleted host paged one month
 * at a time (the `CalendarView(this)` built in code called no `init`, so its paging had been off),
 * `dayContent` for what `calendar_day_layout.xml` inflated, `monthHeader` for
 * `calendar_month_header_layout.xml`, and [startMonth]/[endMonth]/[firstVisibleMonth] for
 * `setup`/`scrollToMonth`. There is no binder, no container and no listener to install - the state's
 * `firstVisibleMonth` is observed through `snapshotFlow`, which is what the deleted
 * `monthScrollListener` reported.
 *
 * <p>**Why `wrapContentHeight(unbounded = true)`.** The old view was `wrap_content` tall, so it was
 * exactly one month page - the header plus the month's square week rows - and the screen's weighted
 * `Space`s divided what was left. A lazy row measured against the column's remaining space would
 * take all of it and collapse those spaces to nothing, so the calendar is measured unbounded and
 * reports the page height it actually drew.
 *
 * <p>**One month's data at a time.** [dayCounts] and [dayFiles] are keyed by day of month and belong
 * to [dataMonth]; a cell whose date is not in [dataMonth] neither shows its count nor borrows its
 * neighbours', exactly as the deleted binder's `monthValue` test decided - a month still scrolling
 * into place must not paint the loaded month's numbers.
 */
@Composable
fun ConversationCalendar(
    startMonth: YearMonth,
    endMonth: YearMonth,
    firstVisibleMonth: YearMonth,
    dataMonth: YearMonth,
    dayCounts: Map<Int, Int>,
    dayFiles: Map<Int, AttachmentRef>,
    previews: suspend (AttachmentRef) -> Bitmap?,
    onVisibleMonthChanged: (YearMonth) -> Unit,
    onDayClick: (CalendarDay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = firstVisibleMonth,
        firstDayOfWeek = firstDayOfWeekFromLocale(),
    )

    // The view library reported month changes through `monthScrollListener`; here the state's own
    // `firstVisibleMonth` is the report, and observing it is the listener.
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleMonth.yearMonth }.collect { month -> onVisibleMonthChanged(month) }
    }

    HorizontalCalendar(
        state = state,
        modifier = modifier.wrapContentHeight(unbounded = true),
        dayContent = { day ->
            val dayOfMonth = day.date.dayOfMonth
            ConversationCalendarDay(
                day = day,
                // `MonthDate` is the library's "belongs to the month being drawn", which is the
                // deleted layout's `data.date.monthValue != yearMonth.monthValue` check; the
                // year-month test keeps a cell from reading another month's day-keyed data.
                inMonth = day.position == DayPosition.MonthDate && YearMonth.from(day.date) == dataMonth,
                messageCount = dayCounts[dayOfMonth] ?: 0,
                attachment = dayFiles[dayOfMonth],
                previews = previews,
                onClick = onDayClick,
            )
        },
        monthHeader = { month -> ConversationCalendarMonthHeader(month.yearMonth) },
    )
}

/**
 * One day cell: the deleted `calendar_day_layout.xml`, with the tap `setOnClickListener` ran.
 *
 * <p>The `FrameLayout`'s `match_parent` square is [Modifier.aspectRatio] 1 (the view library's
 * `DaySize.Square`), its `layout_margin="4dp"` and the oval `ViewOutlineProvider` are the padding
 * and [CircleShape] clip, its `calendarDayBackground` is the `colorPrimaryContainer` fill whose
 * alpha rises by `0.3 + 0.035 x messagesCount`, and its `calendarDayText` is the 16 sp number at
 * alpha 0.5 on a day of an adjacent month. The thumbnail is the `ImageView` - the same
 * `renderThumbnail()` gate and the same `#333333` loading colour while the decode runs. Its
 * `android:foreground="@color/gray_700"` is **not** here: an opaque foreground painted over the very
 * bitmap the cell decodes, so the thumbnail could never be seen.
 */
@Composable
private fun ConversationCalendarDay(
    day: CalendarDay,
    inMonth: Boolean,
    messageCount: Int,
    attachment: AttachmentRef?,
    previews: suspend (AttachmentRef) -> Bitmap?,
    onClick: (CalendarDay) -> Unit,
) {
    val showsThumbnail = inMonth && attachment != null && attachment.renderThumbnail()

    var thumbnail by remember(attachment) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(attachment, showsThumbnail) {
        val file = attachment
        if (showsThumbnail && file != null) {
            thumbnail = previews(file)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable { onClick(day) }
            .padding(4.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (inMonth && messageCount > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = (0.3f + 0.035f * messageCount).coerceIn(0f, 1f),
                        ),
                    ),
            )
        }

        if (showsThumbnail) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Box(modifier = Modifier.matchParentSize().background(CalendarThumbnailLoading))
            }
        }

        Text(
            text = day.date.dayOfMonth.toString(),
            modifier = Modifier.alpha(if (inMonth) 1f else 0.5f),
            fontSize = 16.sp,
        )
    }
}

/** The deleted month header layout: the `noto_sans_medium` 20 sp month, centred in its 16 dp. */
@Composable
private fun ConversationCalendarMonthHeader(month: YearMonth) {
    Text(
        text = month.toString(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        textAlign = TextAlign.Center,
        fontFamily = FontFamily(Font(R.font.noto_sans_medium)),
        fontSize = 20.sp,
    )
}

/**
 * The deleted day layout's loading colour, `-0xcccccd` (`#333333`), which `loadPreview` painted while
 * the decode ran.
 */
private val CalendarThumbnailLoading = Color(0xFF333333)

/**
 * `FileBackends`' cached bitmap first and its decode second, the pair `MediaCell` reads: the same two
 * `getPreviewForUri` calls in the same order, off the main thread.
 */
private suspend fun dayThumbnail(attachment: AttachmentRef, sizePx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        FileBackends.get().getPreviewForUri(attachment, sizePx, true)
            ?: FileBackends.get().getPreviewForUri(attachment, sizePx, false)
    }

/**
 * The calendar screen's body, and the whole of it: the per-month total over the calendar, with the
 * proportional spaces the deleted `LinearLayout` gave them (`0.05` above the line, `0.15` between it
 * and the calendar, `0.8` below). The deleted file's `TextView` is the [total] line - the resource's
 * 20 sp and `@font/noto_sans_medium`, centred - and the calendar's `layout_marginTop="48dp"` is the
 * slot's own padding, applied by the caller so a preview cell that passes nothing draws neither.
 *
 * <p>The slot is [AddReactionScreen]'s shape and for the same reason: the calendar arrives already
 * composed, so this screen names no calendar type and a screenshot cell may pass the real
 * [ConversationCalendar] or nothing at all. [total] is the caller's, already resolved, so this
 * composable names no string of its own.
 */
@Composable
fun ConversationCalendarScreen(
    total: String,
    calendar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.weight(0.05f))
        Text(
            text = total,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily(Font(R.font.noto_sans_medium)),
            fontSize = 20.sp,
        )
        Spacer(modifier = Modifier.weight(0.15f))
        Box(modifier = Modifier.fillMaxWidth()) { calendar() }
        Spacer(modifier = Modifier.weight(0.8f))
    }
}
