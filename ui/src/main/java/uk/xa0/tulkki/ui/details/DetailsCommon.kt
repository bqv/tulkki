package uk.xa0.tulkki.ui.details

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lelloman.identicon.view.GithubIdenticonView
import uk.xa0.tulkki.ui.utils.UIHelper

/**
 * The pieces `activity_contact_details.xml` and `activity_muc_details.xml` both drew, so the two
 * converted screens share one renderer instead of two copies that could drift: the section rule, the
 * tag pill, the recent-thread row, the labelled switch, and the `AndroidView` bridge for the views
 * that stay views.
 */

/** One tag pill: the text the deleted `item_tag.xml` drew and the tint its host gave it. */
data class TagChip(val text: String, val color: Int)

/** One recent thread as the deleted `thread_row.xml` drew it. */
data class ThreadRow(val threadId: String, val subject: String)

/** The deleted layouts' 0.05 dp rule over `@drawable/details_line`, in the theme's line colour. */
@Composable
fun DetailsDivider(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(0.05.dp)
                .background(MaterialTheme.colorScheme.onSurface)
    )
}

/** One tag chip: the deleted `item_tag.xml` - a pill, a white label, one line. */
@Composable
fun TagPill(chip: TagChip, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color(chip.color))
                .padding(horizontal = 7.dp, vertical = 1.dp)
    ) {
        Text(
            text = chip.text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** A labelled `Switch` in the `MaterialSwitch android:text` shape: the label, then the switch. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * One recent-thread row: the deleted `thread_row.xml`'s `GithubIdenticonView` at its 17 dp, a
 * 10 dp gap and the subject in `bodyMedium`, one line, over the theme's small list-item height.
 *
 * <p>The identicon stays the real widget rather than the colour disc `ConversationComposer`'s thread
 * marker became: that marker was a redesign of a `GONE` Java row, while these rows are content the
 * owner reads, so the generated graphic is kept exactly as the data binding set it - `color` from
 * `UIHelper.getColorForName`, `hash` from `UIHelper.identiconHash`.
 */
@Composable
fun ThreadRowItem(
    thread: ThreadRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
    ) {
        AndroidView(
            factory = { context ->
                GithubIdenticonView(context).apply {
                    this.color = UIHelper.getColorForName(thread.threadId)
                    this.hash = UIHelper.identiconHash(thread.threadId)
                }
            },
            update = { view ->
                view.color = UIHelper.getColorForName(thread.threadId)
                view.hash = UIHelper.identiconHash(thread.threadId)
            },
            modifier = Modifier.padding(end = 10.dp).size(17.dp),
        )
        Text(
            text = thread.subject,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A view the screen must keep - a shared adapter's `RecyclerView`/`ListView`, an `AvatarView`, a
 * `TextView` the host decorates. The host builds and wires it; the
 * screen says where it goes.
 * A `null` view draws nothing, which is what lets a screenshot cell compose the screen without an
 * `AndroidView` in it.
 */
@Composable
fun HostView(view: View?, modifier: Modifier = Modifier) {
    if (view == null) {
        return
    }
    val hosted: View = view
    AndroidView(factory = { hosted }, modifier = modifier)
}

/**
 * The JID with its suspicious-character highlights: `IrregularUnicodeDetector.style` answers a
 * `Spannable` whose only span is a `ForegroundColorSpan` over the mixed-script runs, so the
 * conversion is that one span type and nothing is lost. The highlight is a security affordance - it
 * is what shows the owner a spoofed name before they trust it - so it is carried across rather than
 * flattened to plain text.
 */
fun annotatedJid(spanned: Spanned): AnnotatedString = buildAnnotatedString {
    append(spanned.toString())
    for (span in spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)) {
        addStyle(
            SpanStyle(color = Color(span.foregroundColor)),
            spanned.getSpanStart(span),
            spanned.getSpanEnd(span),
        )
    }
}
