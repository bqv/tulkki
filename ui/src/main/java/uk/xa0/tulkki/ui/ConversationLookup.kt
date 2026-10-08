package uk.xa0.tulkki.ui

import android.app.Activity
import android.app.Fragment
import android.app.FragmentManager
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.ui.conversation.ConversationMedia

/**
 * The two questions every caller used to ask `ConversationFragment`'s statics: "which conversation
 * is on screen" and "what does the pending download/audio state do".
 *
 * <p>They were `public static` methods on the fragment. The lookups are pure `FragmentManager`
 * reads - `R.id.main_fragment` (the phone's single pane) or `R.id.secondary_fragment` (the
 * tablet's detail pane) - so they never needed the fragment to be the place they lived; they needed
 * to know the id and the type. Moving them here takes the two ids and the cast out of the class
 * that step 6 replaces.
 *
 * <p>[startStopPending] and [openPendingMessage] are not lookups at all: they were thin delegations
 * to [ConversationMedia], which owns the audio player's play/pause surface and the pending download
 * the storage permission holds. They stay static-shaped because their callers are an activity's
 * `onActivityResult` and menu handling, which have an `Activity` and no fragment.
 *
 * <p>Kotlin for the callers' sake: everything is `@JvmStatic`, so the Java fragment reaches them as
 * `ConversationLookup.conversationReliable(activity)` exactly as it reached its own statics, and a
 * Java caller never sees `ConversationLookup.INSTANCE`.
 *
 * <p>[get] answers the live fragment itself, because the two verbs its callers run -
 * [ConversationFragment.privateMessageWith] and [ConversationFragment.onArrowUpCtrlPressed] - are
 * instance methods. `null` is the answer when no fragment of that type is on screen, which is also
 * what the old statics said.
 *
 * @see ConversationRequests
 */
object ConversationLookup {
    /**
     * Toggles the pending download's audio player, or starts it on the message the storage
     * permission is holding. No fragment is involved.
     */
    @JvmStatic
    fun startStopPending(activity: Activity) {
        ConversationMedia.startStopPending(activity)
    }

    /** Opens the message whose download the storage permission was standing in for. */
    @JvmStatic
    fun openPendingMessage(activity: Activity) {
        ConversationMedia.openPendingDownload(activity)
    }

    /**
     * The conversation on screen, read from the detail pane first.
     *
     * <p>Names the pane directly rather than going through [conversationReliable]'s two-pane walk,
     * because that is what the old `getConversation(Activity)` did: this is the phone-shaped read.
     */
    @JvmStatic
    fun conversation(activity: Activity): Conversation? =
        conversation(activity, R.id.secondary_fragment)

    /**
     * The conversation on either pane, detail pane first and the single pane second. This is the
     * one a caller uses when it cannot know which layout it is in.
     */
    @JvmStatic
    fun conversationReliable(activity: Activity): Conversation? =
        conversation(activity, R.id.secondary_fragment)
            ?: conversation(activity, R.id.main_fragment)

    /**
     * The live conversation fragment, whichever pane it is on, or `null`.
     *
     * <p>The returned type is the fragment itself, deliberately: this is the module's escape hatch
     * to the two instance verbs the menus need, and inventing an interface for two unrelated verbs
     * would be a wider change than the escape it buys.
     */
    @JvmStatic
    fun get(activity: Activity): ConversationFragment? {
        val fragmentManager: FragmentManager = activity.fragmentManager
        var fragment: Fragment? = fragmentManager.findFragmentById(R.id.main_fragment)
        if (fragment is ConversationFragment) {
            return fragment
        }
        fragment = fragmentManager.findFragmentById(R.id.secondary_fragment)
        return fragment as? ConversationFragment
    }

    private fun conversation(activity: Activity, res: Int): Conversation? {
        val fragment: Fragment? = activity.fragmentManager.findFragmentById(res)
        return if (fragment is ConversationFragment) fragment.getConversation() else null
    }
}
