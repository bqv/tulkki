package uk.xa0.tulkki.translation

import java.util.LinkedHashMap

/**
 * The reviews this process has already looked up, so a bubble that is re-bound does not ask the
 * database again.
 *
 * <p>A `ListView` re-binds as it scrolls, and the review of a message is found under a key
 * derived from the message's own text - a primary-key lookup, but a lookup all the same, and one the
 * same bubble would repeat on every pass. One point query per review per process is the whole
 * ambition of this class; the answers are a few short strings, so the cap is generous and the
 * eviction order is the read order.
 *
 * <p><strong>A miss is not remembered.</strong> The notes are written after the translation lands
 * and before the message is handed over, so a bubble can be drawn in between - and caching that
 * "nothing yet" would hide the notes until the next process start. Only an answer is kept.
 *
 * <p>Pure Kotlin, so it is exercised by JVM unit tests without a device. The two constructors stay
 * two Java constructors (`@JvmOverloads`), `get`/`put`/`size` stay methods because their Java names
 * are the API `ReviewStore` and `ReviewMemoTest` already call, and `DEFAULT_CAPACITY` is a
 * `const val`.
 */
class ReviewMemo @JvmOverloads constructor(capacity: Int = DEFAULT_CAPACITY) {

    private val answers: LinkedHashMap<String, Review>

    init {
        val bounded = maxOf(1, capacity)
        answers =
                object : LinkedHashMap<String, Review>(bounded, 0.75f, true) {
                    override fun removeEldestEntry(
                            eldest: MutableMap.MutableEntry<String, Review>
                    ): Boolean = size > bounded
                }
    }

    companion object {
        /** Enough for a conversation's worth of sent messages, and small enough to be free. */
        const val DEFAULT_CAPACITY = 256
    }

    /** The remembered review, or `null` when this process has not read it yet. */
    fun get(key: String?): Review? = if (key == null) null else answers[key]

    /** Keeps an answer. A repeated key replaces what was there and becomes the most recent. */
    fun put(key: String?, review: Review?) {
        if (key == null || review == null) {
            return
        }
        answers[key] = review
    }

    /** How many answers are held, which the cap bounds. */
    fun size(): Int = answers.size
}
