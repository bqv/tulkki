package uk.xa0.tulkki.ui.conversation

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import com.google.android.material.tabs.TabLayout
import uk.xa0.tulkki.ui.LockedViewPager
import uk.xa0.tulkki.ui.R

/**
 * The conversation's pager, built in code: the tab strip, the `LockedViewPager` and its two pages.
 *
 * <p>**It is views on purpose, and the one place in this screen where that is true.** The pager's
 * adapter is `:data`'s `Conversation.ConversationPagerAdapter`: it adopts the pager's two child pages
 * by position, it hangs a `TabLayout` off them, and every command a page opens is a `CommandSession`
 * that draws through `CommandFormHost`'s Compose renderer as a *further page*. None of that is this
 * module's to rewrite, and the pages must be real children of a real `ViewPager` for
 * `setupViewPager`'s own check (`page2.findViewById(commandsViewId)`) to pass. So the pager is an
 * `AndroidView` in the page's composition and everything else in this screen is Compose.
 *
 * <p>**The geometry is the deleted layout's, with one mended quirk.** The old file put the tab strip
 * 30 dp at the top of a `RelativeLayout` and the pager `layout_below` it at `match_parent` height -
 * which, with the tabs visible, pushed the pager 30 dp down without shrinking it, so its bottom 30 dp
 * fell off the screen. A vertical `LinearLayout` with the pager weighted gives the pager exactly the
 * space the tabs leave, and a `GONE` strip (the ordinary case) still costs it nothing.
 *
 * <p>**Why the tabs have a generated id and the page two keeps `commands_view`.** `R.id.tab_layout`
 * and `R.id.conversation_view_pager` died with the layout and nothing looks either up: the adapter
 * holds the two views themselves. `commands_view` is different - it is the id
 * `Conversation.setupViewPager` is handed and searches page two for - so it moves to `values/ids.xml`
 * and is set on [page2].
 */
class ConversationPagerController(context: Context) {

    /** The pager's whole view: the tab strip above, the pager below. */
    val root: LinearLayout = LinearLayout(context)

    /** The tab strip `Conversation.ConversationPagerAdapter` shows while a page is open. */
    val tabs: TabLayout = TabLayout(context)

    val pager: LockedViewPager = LockedViewPager(context)

    /** The conversation's own page: the shell `ConversationHost.showPage` draws. */
    val page1: ComposeView = ComposeView(context)

    /** The command page: `ConversationCommands`, and the id `commandsViewId` names. */
    val page2: ComposeView =
        ComposeView(context).apply {
            id = R.id.commands_view
        }

    init {
        root.orientation = LinearLayout.VERTICAL
        val density = context.resources.displayMetrics.density
        // The deleted `tab_layout`'s own height and elevation. It is `GONE` until the adapter has a
        // page to show, exactly as the deleted view was.
        tabs.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (TAB_STRIP_HEIGHT_DP * density).toInt(),
            )
        tabs.tabGravity = TabLayout.GRAVITY_FILL
        tabs.tabMode = TabLayout.MODE_SCROLLABLE
        tabs.elevation = context.resources.getDimension(R.dimen.toolbar_elevation)
        tabs.visibility = View.GONE
        pager.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        page1.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        page2.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        pager.addView(page1)
        pager.addView(page2)
        root.addView(tabs)
        root.addView(pager)
    }
}

/** The deleted `tab_layout`'s height, in dp. */
private const val TAB_STRIP_HEIGHT_DP = 30
