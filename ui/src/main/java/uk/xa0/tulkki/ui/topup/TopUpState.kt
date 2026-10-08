package uk.xa0.tulkki.ui.topup

/**
 * The top-up screen's state, docs/MIGRATION.md "Design: the Compose UI" §3.3.
 *
 * <p>Two readings and no third: [page] is what the platform's own page is doing, and [notice] is one
 * address nothing on this phone could open. The screen around them is deliberately a browser and
 * nothing more - the page is DeepSeek's, the session is the owner's, and nothing here reads, keeps or
 * forwards a credential - so this type holds no URL of its own and no login.
 *
 * <p><strong>The page is three shapes and the failure is two.</strong> [Page.Loading] carries the
 * percent its bar shows, because the bar is the loading state's whole content; [Page.Ready] is the page
 * on screen; [Page.Failed] carries the one [Reason] to say about it - the WebView's own description of
 * a load that did not happen, or an HTTP status the platform answered instead of the page (the 403
 * shape its bot protection produces).
 *
 * <p>§3.3's declaration had two things read against the tree. Its `Failed(reason)` is kept, and its
 * `notice: UnhandledScheme?|HttpError?|null` is **not**: in the tree the HTTP error is the page's own
 * failure - it is drawn in the panel that covers the page, with the retry beside it - while the bottom
 * line says the one thing the page asked for and no app on the phone can do. So the notice is the
 * address, `null` when there is nothing to say, and the HTTP error is a [Reason].
 *
 * <p>The transitions are members because each one mirrors a callback the WebView really makes, and two
 * of them are rules rather than assignments: a progress tick only moves a bar that is on screen, and a
 * finished page only hides a bar - it does not clear a failure, because a refusal arrives as an HTTP
 * error and then as a finished page, and the second callback must not clear the first.
 */
data class TopUpState(
    /** What the page is doing. */
    val page: Page,
    /** The one address nothing on the phone could open, or `null` when there is nothing to say. */
    val notice: String?,
) {

    /** What the page is doing: §3.3's three shapes. */
    sealed interface Page {
        /** The page has not arrived; [percent] is how far the bar has come, 0..100. */
        data class Loading(val percent: Int) : Page

        /** The page is on screen and nothing is wrong with it. */
        object Ready : Page

        /** The page did not arrive, and [reason] is what to say about it. */
        data class Failed(val reason: Reason) : Page
    }

    /** Why the page did not arrive. */
    sealed interface Reason {
        /** The WebView's own failure: the address it was loading and its description. */
        data class Unreachable(val address: String, val description: String) : Reason

        /** The platform answered a status instead of the page: the 403 shape bot protection produces. */
        data class Http(val status: Int, val address: String) : Reason
    }

    /**
     * A progress tick. Only a bar that is on screen moves: an error panel is standing over a page that
     * may still be finishing, and a tick must not take it away.
     */
    fun progressed(percent: Int): TopUpState =
        if (page is Page.Loading) copy(page = Page.Loading(percent)) else this

    /**
     * The page finished loading. The bar goes - and a failure already standing is left standing, which
     * is the tree's own rule: an HTTP refusal arrives as an error and then as a finished page.
     */
    fun finished(): TopUpState = if (page is Page.Loading) copy(page = Page.Ready) else this

    /** The page did not arrive. The notice is the page's business and is left alone. */
    fun failed(reason: Reason): TopUpState = copy(page = Page.Failed(reason))

    /**
     * One address the page asked for and nothing on this phone can open. A line, not a panel: the page
     * is still there and still usable.
     */
    fun unhandled(address: String): TopUpState = copy(notice = address)

    companion object {
        /**
         * The page starting - a first load or a retry: the bar at [percent], no failure standing and no
         * notice, because the page that produced either is being replaced.
         *
         * <p>It is the state a start *produces*, not a transition of the old one, and that is the point:
         * `loading(0)` after a refusal cannot carry the refusal's panel or a stale address, because there
         * is nowhere for them to come from. It is the one static, so the host's own callbacks - which are
         * Java - can name the state they are moving to.
         */
        @JvmStatic fun loading(percent: Int): TopUpState = TopUpState(Page.Loading(percent), null)
    }
}
