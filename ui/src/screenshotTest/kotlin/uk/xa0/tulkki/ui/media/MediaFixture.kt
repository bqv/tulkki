package uk.xa0.tulkki.ui.media

import android.net.Uri
import uk.xa0.tulkki.libs.AttachmentRef
import java.util.UUID

/** A fixed instant, so a cell's date heading is the same string every run. */
internal const val FIXTURE_DAY: Long = 1_700_000_000_000L

/** The day after [FIXTURE_DAY], for a second heading. */
internal const val FIXTURE_NEXT_DAY: Long = FIXTURE_DAY + 86_400_000L

/**
 * An [AttachmentRef] with nothing behind it, so a screenshot cell needs no backend.
 *
 * <p>**The file is deliberately absent.** `getUri()` throws rather than answering a `Uri`, because
 * neither converted screen dereferences it in a cell: the grid reads the mime, the kind, the timestamp
 * and the uuid, and the viewer's page slot is a fixture `Box` rather than the activity's `PhotoView`.
 * If a future cell starts asking for the file, it should say so loudly instead of drawing a path that
 * does not exist.
 *
 * @param mime the mime the cell shows an icon for, which may be null - the icon map has an arm for it.
 * @param seed the uuid's low half, so a cell's `LazyVerticalGrid` keys are the same every run.
 * @param timestamp the row's day heading.
 * @param kind the island kind, which wins over the mime for a location and a recording.
 * @param thumbnail whether the row takes the image path (a decode, which the fixture never has) rather
 *     than the mime-icon path.
 */
internal class FixtureAttachment(
    private val mime: String?,
    private val seed: Long,
    private val timestamp: Long = FIXTURE_DAY,
    private val kind: AttachmentRef.TypeRef = AttachmentRef.TypeRef.FILE,
    private val thumbnail: Boolean = false,
) : AttachmentRef {

    private val uuid: UUID = UUID(0L, seed)

    override fun getUri(): Uri = throw UnsupportedOperationException("the fixture has no file")

    override fun getMime(): String? = mime

    override fun getUuid(): UUID = uuid

    override fun getTimestamp(): Long = timestamp

    override fun getConversationUuid(): String? = null

    override fun renderThumbnail(): Boolean = thumbnail

    override fun kind(): AttachmentRef.TypeRef = kind
}
