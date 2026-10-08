package uk.xa0.tulkki.ui.posts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The composer host's one piece of state: the screen reads [Session.state] and
 * [uk.xa0.tulkki.ui.CreatePostActivity] writes it after its intent is read and after every action
 * that changes what is visible.
 */
class CreatePostHost {

    /** The one `MutableState` the composer recomposes on. */
    class Session {

        /** What the screen draws now. */
        var state: CreatePostState by mutableStateOf(CreatePostState())
            private set

        /** Replace the picture; the host calls this once per change. */
        fun update(next: CreatePostState) {
            state = next
        }
    }
}
