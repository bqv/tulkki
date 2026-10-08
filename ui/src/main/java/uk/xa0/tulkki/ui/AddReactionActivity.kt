package uk.xa0.tulkki.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.google.common.collect.ImmutableSet
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.chrome.TulkkiChrome
import uk.xa0.tulkki.ui.compose.setTulkkiContent

/**
 * The add-reaction screen: the platform's emoji picker, and the reaction the pick sends.
 *
 * <p>**The layout is gone.** `activity_add_reaction.xml` held a toolbar and one
 * `EmojiPickerView` and the file is deleted; the bar is the shared chrome
 * ([uk.xa0.tulkki.ui.chrome.TulkkiChrome]) now, `setSupportActionBar`,
 * `setTitle`, `Activities.setStatusAndNavigationBarColors` and the bar's own `ic_clear_24dp`
 * navigation icon went with it. The chrome's arrow is the same exit: the old icon's click ran
 * `finish()`, and the arrow is the affordance the shell has.
 *
 * <p>**The picker stays a view.** [EmojiPickerView] is the emoji data and its categories, a platform
 * component, and `EmojiPanel` hosts it through [AndroidView] for the same reason - so this screen
 * does too, and [AddReactionScreen] takes it as a slot, exactly as `TopUpScreen` takes its page. The
 * pick's action is unchanged: the picked emoji goes through `addReaction`, which is the whole of
 * [addReaction] below, `Toast` and `finish()` included.
 */
class AddReactionActivity : XmppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The chrome draws its bar behind the system bars and hands the content the space they
        // leave, so the window must not inset itself for them first - on every API level, which is
        // what enableEdgeToEdge settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()

        setTulkkiContent(darkTheme = isDark()) {
            TulkkiChrome(
                title = stringResource(R.string.add_reaction_title),
                onUp = { finish() },
            ) {
                AddReactionScreen(
                    picker = {
                        AndroidView(
                            factory = { context ->
                                EmojiPickerView(context).apply {
                                    setOnEmojiPickedListener { item ->
                                        addReaction(item.emoji.toString())
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }
        }
    }

    private fun addReaction(emoji: String) {
        // The Java read `getStringExtra` on a possibly-null Intent and let the strings be null;
        // the guard below is what the original relied on, and it keeps the smart cast.
        val conversation = intent?.getStringExtra("conversation")
        val message = intent?.getStringExtra("message")
        if (conversation.isNullOrEmpty() || message.isNullOrEmpty()) {
            Toast.makeText(this, R.string.could_not_add_reaction, Toast.LENGTH_LONG).show()
            return
        }
        val c = xmppConnectionService.findConversationByUuid(conversation) as Conversation?
        val m = c?.findMessageWithUuid(message)
        if (m == null) {
            Toast.makeText(this, R.string.could_not_add_reaction, Toast.LENGTH_LONG).show()
            return
        }
        val aggregated = m.getAggregatedReactions()
        val reactions: Collection<String> =
            if (aggregated.ourReactions.contains(emoji)) {
                aggregated.ourReactions
            } else {
                ImmutableSet.builder<String>()
                    .addAll(aggregated.ourReactions)
                    .add(emoji)
                    .build()
            }
        xmppConnectionService.sendReactions(m, reactions)
        finish()
    }

    override fun refreshUiReal() {}

    override fun onBackendConnected() {}
}

/**
 * The screen's body, and the whole of it: the picker fills the content area the chrome leaves, and
 * the grid itself is the view handed in.
 *
 * <p>The slot is [TopUpActivity]'s shape for the same reason and is what makes the screen drawable
 * without its platform view: a screenshot cell passes an empty picker, because [EmojiPickerView]'s
 * grid is the platform's and only observable on a phone.
 */
@Composable
fun AddReactionScreen(picker: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) { picker() }
}
