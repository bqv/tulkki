package uk.xa0.tulkki.ui.util

class ChangeWatcher<T> {

    private var value: T? = null

    @Synchronized
    fun watch(item: T?): Boolean {
        val current = value
        return if (current == null) {
            value = item
            item != null
        } else {
            val changed = !current.equals(item)
            value = item
            changed
        }
    }

    fun get(): T? = value
}
