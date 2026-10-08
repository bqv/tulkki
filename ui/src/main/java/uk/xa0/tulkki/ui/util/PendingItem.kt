package uk.xa0.tulkki.ui.util

class PendingItem<T> {

    private var item: T? = null

    @Synchronized
    fun push(item: T?) {
        this.item = item
    }

    @Synchronized
    fun pop(): T? {
        val item = this.item
        this.item = null
        return item
    }

    @Synchronized
    fun peek(): T? = item

    @Synchronized
    fun clear(): Boolean {
        val notNull = this.item != null
        this.item = null
        return notNull
    }
}
