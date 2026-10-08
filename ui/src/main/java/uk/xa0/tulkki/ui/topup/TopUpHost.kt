package uk.xa0.tulkki.ui.topup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The live screen's state, for the one host that is an Activity: `TopUpActivity`.
 *
 * <p>It is where this host differs from its siblings, and the reason is the WebView the screen holds.
 * The other hosts re-set the content after every write, which rebuilds the composition; that is
 * harmless for a list of rows, but this screen holds a live page, and re-parenting it on every
 * progress tick would detach and re-attach a WebView in the middle of a payment. So the Activity sets
 * the content once and the state is observable: a tick recomposes the bar and leaves the page where it
 * is.
 *
 * <p>The page is nullable on purpose: with the interpreter off the screen draws the off card, and the
 * Activity builds no WebView at all - there is no spend to top up, so there is no page to open.
 */
object TopUpHost {

    /**
     * The live screen's state. Every method is one callback the host receives, spelled as the state's own
     * transition, so the Activity cannot move the screen to a state the state type does not allow.
     */
    class Session {

        /** The state the screen reads. It is observable, so a tick recomposes and does not rebuild. */
        internal var state by mutableStateOf(TopUpState.loading(0))

        /** The page starting: a first load or a retry, with nothing carried over. */
        fun started() {
            state = TopUpState.loading(0)
        }

        /** A progress tick, which moves a bar that is on screen and nothing else. */
        fun progressed(percent: Int) {
            state = state.progressed(percent)
        }

        /** The page finished loading: the bar goes, and a failure already standing stays standing. */
        fun finished() {
            state = state.finished()
        }

        /** The page did not arrive. */
        fun failed(reason: TopUpState.Reason) {
            state = state.failed(reason)
        }

        /** One address the page asked for and nothing on this phone can open. */
        fun unhandled(address: String) {
            state = state.unhandled(address)
        }
    }
}
