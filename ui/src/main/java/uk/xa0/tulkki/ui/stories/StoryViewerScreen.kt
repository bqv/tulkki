package uk.xa0.tulkki.ui.stories

import android.util.Patterns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full-screen story player, where `activity_story_view.xml`, `story_progress_bar.xml` and
 * `fragment_story.xml` + `StoryFragment` were.
 *
 * <p>**The three files are gone.** The deleted screen was one `ViewPager2` of `StoryFragment`s, a
 * container of `story_progress_bar.xml` instances built in a loop, a `bottom_panel` with the story
 * title, and a toolbar carrying the author and the published time. Here the pager is the session's
 * own index - the old pager had `setUserInputEnabled(false)`, so a swipe never did anything - the
 * progress row is [LinearProgressIndicator]s, the panel is the same 16 dp, `#80000000` strip, and the
 * player's two view halves (the image and the video) are slots, because the image library and the
 * `VideoView` are the host's.
 *
 * <p>**The timer is the deleted `ObjectAnimator`.** A still story ran 0 -> 1000 over
 * `STORY_DURATION_MS`, then asked for the next; a video story's own playback drove the same bar, at
 * `currentPosition / duration`. The same two rules live here: [session]'s `progress` is driven frame
 * by frame by [withFrameNanos] for a still (started only when the image is in hand, as the deleted
 * animator was started in `onResourceReady`), and the video slot writes it for a video. `paused` is
 * the long press: the deleted `StoryFragment` held at 300 ms and resumed on release, and this holds
 * for as long as the pointer is down.
 *
 * <p>**The one thing the deleted screen did that this one does not** is hide the system bars and fade
 * its own toolbar and panel out on a long press: the chrome owns the bars now
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]), which is the whole point of it, so `pauseStory`/
 * `resumeStory` are the timer alone. The author's avatar moved out of the bar with it - the chrome's
 * title slot is a string - and the published time it sat beside is drawn in the panel instead.
 */

/** One page: the published item's url, its mime type and its title. */
data class StoryPage(val url: String, val mimeType: String, val title: String)

/**
 * The viewer's live state: which page is showing, how far through it is (0..1) and whether it is
 * held. The host reads [index] to know which story a menu action is about, which is what the deleted
 * `viewPager.currentItem` answered.
 */
class StoryViewerSession {
    var index by mutableStateOf(0)
    var progress by mutableStateOf(0f)
    var paused by mutableStateOf(false)
}

/**
 * The player: one page at a time, the progress row, the title panel, and the tap/hold gestures.
 *
 * @param loadImage the still's pixels, fetched by the host off the main thread; `null` is the
 *     deleted `ImageView`'s spinner, which [StoryViewerScreen] draws until the answer lands.
 * @param video the video page's own view, hosted by the caller because a `VideoView` is a platform
 *     view. It draws into the whole screen, and it is expected to write [StoryViewerSession.progress]
 *     and to advance the session when playback ends.
 * @param onFinish the deleted `StoryFragment`'s `finish()`: the last page ran out.
 */
@Composable
fun StoryViewerScreen(
    pages: List<StoryPage>,
    session: StoryViewerSession,
    subtitle: String,
    loadImage: suspend (String) -> ImageBitmap?,
    video: @Composable (StoryPage) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = pages.getOrNull(session.index)
    val isVideo = page != null && page.mimeType.startsWith("video/")
    var image by remember(page?.url) { mutableStateOf<ImageBitmap?>(null) }

    // The deleted `loadStory`: the spinner is shown, the fetch runs, and the still is drawn - or the
    // spinner alone is left when the fetch failed, exactly as `onLoadFailed` left it.
    LaunchedEffect(page?.url) {
        image = null
        val url = page?.url
        if (url != null && !page.mimeType.startsWith("video/")) {
            image = loadImage(url)
        }
    }

    // The deleted `ObjectAnimator`: `ofInt(progress, 0, 1000)` over `STORY_DURATION_MS`, started
    // when the image arrived and cancelled (never finished) when the page was left. `paused` is the
    // long press, and a resumed run continues from where it held.
    LaunchedEffect(page?.url, session.paused, isVideo, image) {
        if (page == null || isVideo || image == null || session.paused) {
            return@LaunchedEffect
        }
        val from = session.progress.coerceIn(0f, 1f)
        val startNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsedMs = (nowNanos - startNanos) / NANOS_PER_MILLI
            session.progress =
                (from + (1f - from) * (elapsedMs / STORY_DURATION_MS)).coerceIn(0f, 1f)
            if (session.progress >= 1f) {
                break
            }
        }
        advance(pages, session, onFinish)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (page != null) {
            if (isVideo) {
                video(page)
            } else {
                val current = image
                if (current == null) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Image(
                        bitmap = current,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // The deleted `StoryFragment`'s `setOnTouchListener`, over the page and under the panels:
        // a tap on the left half asks for the previous page, a tap on the right the next, and a hold
        // pauses. `tryAwaitRelease` is the deleted `ACTION_UP`/`ACTION_CANCEL`, and the panels are
        // drawn after this box so the title's links keep their taps.
        Box(
            modifier =
                Modifier.matchParentSize().pointerInput(page?.url) {
                    detectTapGestures(
                        onPress = {
                            tryAwaitRelease()
                            if (session.paused) {
                                session.paused = false
                            }
                        },
                        onLongPress = { session.paused = true },
                        onTap = { offset ->
                            if (offset.x < size.width / 2f) {
                                previous(session)
                            } else {
                                advance(pages, session, onFinish)
                            }
                        },
                    )
                }
        )

        // The deleted `progress_bar_container` of `story_progress_bar.xml` instances: one per page,
        // 3 dp tall, 2 dp apart, white, a finished page full and a page not yet reached empty.
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 4.dp),
        ) {
            for (i in pages.indices) {
                val value =
                    when {
                        i < session.index -> 1f
                        i > session.index -> 0f
                        else -> session.progress
                    }
                LinearProgressIndicator(
                    progress = { value },
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp).height(3.dp),
                    color = Color.White,
                )
            }
        }

        // The deleted `bottom_panel` + `story_title_view`: `#80000000`, 16 dp, white 18 sp, the web
        // addresses tappable (`android:autoLink="web"`), scrollable with the layout's 8-line ceiling.
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(PanelColour)
                    .padding(16.dp),
        ) {
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val title = page?.title.orEmpty()
            val linkColour = MaterialTheme.colorScheme.primary
            val body = remember(title, linkColour) { storyLinks(title, linkColour) }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = body,
                    color = Color.White,
                    fontSize = 18.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The deleted `bottom_panel`'s `android:background="#80000000"`. */
private val PanelColour = Color(0x80000000)

/** The deleted `StoryFragment.STORY_DURATION_MS`. */
private const val STORY_DURATION_MS = 6000f

private const val NANOS_PER_MILLI = 1_000_000f

/** The deleted `onPreviousStory`: one page back, and nothing at the first page. */
private fun previous(session: StoryViewerSession) {
    if (session.index > 0) {
        session.index -= 1
        session.progress = 0f
    }
}

/** The deleted `onNextStory`: one page on, or out of the player at the last one. */
private fun advance(
    pages: List<StoryPage>,
    session: StoryViewerSession,
    onFinish: () -> Unit,
) {
    if (session.index + 1 < pages.size) {
        session.index += 1
        session.progress = 0f
    } else {
        onFinish()
    }
}

/**
 * The title with every web address in it a [LinkAnnotation.Url], which is what
 * `android:autoLink="web"` (`Linkify.WEB_URLS`, which reads [Patterns.WEB_URL]) did to the deleted
 * `TextView`. The same matcher and the same trailing-punctuation trim `AboutScreen` uses.
 */
private fun storyLinks(text: String, linkColour: Color): AnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColour))
    val matcher = Patterns.WEB_URL.matcher(text)
    return buildAnnotatedString {
        var last = 0
        while (matcher.find()) {
            val start = matcher.start()
            val url = matcher.group().trimEnd(')', '.', ',', ';')
            append(text, last, start)
            withLink(LinkAnnotation.Url(url, styles)) { append(url) }
            last = start + url.length
        }
        append(text, last, text.length)
    }
}
