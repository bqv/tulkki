package uk.xa0.tulkki.ui.util

/**
 * Created by mxf on 2018/4/3.
 */
class PendingActionHelper {

    private var pendingAction: PendingAction? = null

    fun push(pendingAction: PendingAction) {
        this.pendingAction = pendingAction
    }

    fun execute() {
        val action = pendingAction
        if (action != null) {
            action.execute()
            pendingAction = null
        }
    }

    fun undo() {
        pendingAction = null
    }

    fun interface PendingAction {
        fun execute()
    }
}
