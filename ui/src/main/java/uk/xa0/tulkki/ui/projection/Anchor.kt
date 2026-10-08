package uk.xa0.tulkki.ui.projection

/**
 * Where the reader was before the rows changed, and where they should be after, `Design: the Compose UI`
 * §4.3: "capture the visible position and the first item's id before a change; if the first id changed
 * (older history prepended) keep the reader's anchor; only if the last item was visible scroll to the
 * bottom."
 *
 * <p>**It is load-bearing for Tulkki's own feature, not polish**: "Tulkki's catch-up pass translates a whole
 * gap at once - a burst of rows changing state *while the owner is reading*. Without anchoring, Tulkki's
 * own feature throws the reader around." So it is a pure pair of functions over ids, with cells, rather than
 * a `LazyListState` the JVM cannot reach: the screen keeps the `At`, and asks [after] where to move.
 *
 * <p>**`0` is the doc's own sentinel, and it covers both readings of "do not move":** the reader who was at
 * the bottom (the list's end is where they stay) and the reader whose anchored row is still the first one
 * (nothing was added above them). The screen reads it as "scroll to the end" in the first case and as "no
 * move" in the second, which is the same drawing.
 */
object Anchor {

    /**
     * The reader's place.
     *
     * @param firstId the id of the row at the top of the visible window; `null` on an empty list.
     * @param index that row's index, kept so a row that disappears can fall back to where it was.
     * @param atBottom whether the list's last row was on screen.
     */
    data class At(val firstId: String?, val index: Int, val atBottom: Boolean)

    /** What the reader has on screen now, from the list and the visible window's bounds. */
    @JvmStatic
    fun before(ids: List<String>, firstVisible: Int, lastVisible: Int): At {
        if (ids.isEmpty()) {
            return At(firstId = null, index = 0, atBottom = true)
        }
        val first = firstVisible.coerceIn(0, ids.size - 1)
        return At(
            firstId = ids[first],
            index = first,
            // A window that reaches the end - or an empty one, which only a not-yet-measured list gives.
            atBottom = lastVisible >= ids.size - 1,
        )
    }

    /**
     * Where to move after the rows changed: the index the reader's own row moved to, or `0` for the two
     * cases the doc's sentinel covers - they were at the bottom, or their row is still the first one.
     */
    @JvmStatic
    fun after(before: At, ids: List<String>): Int {
        if (ids.isEmpty() || before.atBottom) {
            return 0
        }
        val moved = before.firstId?.let { ids.indexOf(it) } ?: -1
        // The row is gone - a retraction, a clear, a deletion: stay at the index it was at rather than jump.
        return if (moved >= 0) moved else before.index.coerceIn(0, ids.size - 1)
    }

    /**
     * Whether the newest row is fully on screen - "at the bottom" as the owner reads it.
     *
     * <p>**`lastVisible >= ids.lastIndex` is not this fact**, and that is the whole of the reported
     * "last few messages hidden": a row whose top has just entered the viewport while its bottom is
     * still cut satisfies "the last row is drawn" while the owner cannot read it, so the jump-to-latest
     * control hid exactly when the newest message was half-hidden and the pin could not tell a fully
     * visible bottom from a clipped one.
     *
     * @param lastIndex the newest item's index, or negative for a list with no items yet
     * @param lastVisibleIndex the bottom-most drawn item's index, or -1 for none
     * @param lastVisibleBottom that item's bottom edge, in the list's own coordinates
     * @param viewportEnd the viewport's own end offset, the origin those coordinates are measured from
     */
    @JvmStatic
    fun newestFullyVisible(
        lastIndex: Int,
        lastVisibleIndex: Int,
        lastVisibleBottom: Int,
        viewportEnd: Int,
    ): Boolean = lastIndex >= 0 && lastVisibleIndex == lastIndex && lastVisibleBottom <= viewportEnd

    /**
     * Whether one reading is the end of the **reader's own** scroll, which is the only moment the pin
     * may be written.
     *
     * <p>**The two other ways the position can move are not the reader leaving the bottom**, and
     * reading them as one is what made "at the bottom" drift upwards: a list that merely grew changes
     * the layout under a still reader (the pin must survive it, so the newest row can be carried back
     * into view), and a scroll this screen started programmatically moves the position too - its own
     * re-anchoring scroll would otherwise read as a reader who had just scrolled away. The hand has to
     * have been moving and to have stopped, which is what this asks for.
     *
     * @param moved the position changed since the last settled reading
     * @param scrolling `LazyListState.isScrollInProgress` for this reading
     * @param wasScrolling the same fact for the previous reading
     */
    @JvmStatic
    fun settled(moved: Boolean, scrolling: Boolean, wasScrolling: Boolean): Boolean =
        moved && wasScrolling && !scrolling
}
