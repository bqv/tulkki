package uk.xa0.tulkki.ui.conversation

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import uk.xa0.tulkki.translation.Gloss
import uk.xa0.tulkki.translation.GlossContent
import uk.xa0.tulkki.ui.theme.TulkkiTheme

/**
 * The reading aid's card, which the live screen only draws after a tap and so cannot be reached by a
 * `ConversationScreen` cell: the screenshot suite feeds the drawn surface directly, which is what makes
 * the card's own coverage possible at all (`docs/MIGRATION.md` §7.2's "the assertion is the rendered
 * image against the reference").
 *
 * <p>One cell, the state worth looking at: a full answer - the word, its dictionary form, the ending
 * and the case, and the meaning. The looking-up spinner and the two caption states are the same card
 * with fewer lines, and the JVM cells on `GlossContent` are where those decisions are pinned.
 */
@PreviewTest
@Preview(name = "gloss-card", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360, heightDp = 320)
@Composable
fun GlossCardScreenshot() =
    TulkkiTheme(darkTheme = true) {
        GlossCardContent(
            content =
                GlossContent.of(
                    Gloss.of("talossa", "talo", "ssa", "inessive", "in the house")
                )
        )
    }
