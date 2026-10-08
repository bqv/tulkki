package uk.xa0.tulkki.ui.projection

import uk.xa0.tulkki.translation.DoubtHold

/**
 * Item 16's per-conversation switch as the conversation screen holds it: whether the doubt hold is in
 * force for this conversation.
 *
 * <p>**It is the derived answer, never the stored column.** The column is a tri-state - `NULL` is
 * "this conversation never chose", `0` is the owner turning the hold off for this room, `1` is turning
 * it explicitly on - and the one place that resolves it is `DoubtHold.inForce` (`docs/MIGRATION.md` item
 * 16). A screen that read the column and answered the default itself would be the second place that
 * decides, which is exactly how item 16's bug comes back: a switch the owner has to remember to set
 * leaves every room they forgot. [of] delegates, so a room the owner has never touched - the ordinary
 * state, and every room of a fresh install - shows the switch **on**.
 *
 * <p>**The owner's "never chose" is deliberately not a third position on the screen.** The switch
 * shows the value in force, as the settings screen's received-original switch shows its own fallback
 * rather than a stored blank; a switch that opened off while the app was holding would be a lie about
 * what the app does. Turning it back on stores the owner's own `1`, and there is no reset row - this
 * repo's rule for a switch whose off state is a real answer. Nothing is lost by that: `NULL` and `1`
 * mean the same thing here.
 *
 * <p>`null` is not a state of this type. The composer draws **no switch at all** when the interpreter
 * is off (`UiComposer.doubtHold`), which is §2.12's shape: off, there is nothing to hold and an
 * affordance that would do nothing must not be drawn.
 */
data class UiDoubtHold(val inForce: Boolean) {

    companion object {

        /**
         * The switch's own reading of the conversation's stored answer: "never chose" follows the
         * build's shipped default, which is on, and an explicit answer is the owner's. One delegation,
         * so the screen and the send path cannot disagree about a room.
         */
        @JvmStatic fun of(stored: Boolean?): UiDoubtHold = UiDoubtHold(DoubtHold.inForce(stored))
    }
}
