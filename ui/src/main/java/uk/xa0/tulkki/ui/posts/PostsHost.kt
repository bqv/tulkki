package uk.xa0.tulkki.ui.posts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The feed host's one piece of state: the screen reads [Session.state] and
 * [uk.xa0.tulkki.ui.PostsActivity] writes it after every read.
 *
 * <p>It is the same door [uk.xa0.tulkki.ui.conversationlist.ConversationListHost.Session] is, kept
 * where the screen is rather than where the models are: the Activity owns the queries, the service
 * and the accounts, and the screen owns nothing but a picture of the last answer.
 */
class PostsHost {

    /** The one `MutableState` the feed screen recomposes on. */
    class Session {

        /** What the screen draws now. */
        var state: PostsState by mutableStateOf(PostsState())
            private set

        /** Replace the picture; the host calls this once per read. */
        fun update(next: PostsState) {
            state = next
        }
    }
}
