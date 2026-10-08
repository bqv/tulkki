package uk.xa0.tulkki.ui.conversation

import uk.xa0.tulkki.ui.projection.EnglishInHand

/**
 * The English this **process** holds, keyed by message uuid: the one home of the English row's own memo, so
 * the two buyers and the reader cannot keep three answers.
 *
 * <p>**Why it is a process-wide object and not a screen's field.** A purchase outlives any screen and
 * arrives when no screen exists: the receive path buys the English of a message that has just arrived -
 * with the conversation closed, or the app in the background, or a notification on screen - and the owner's
 * tap buys it on a row that is on screen. The reader is `LiveMessageFacts.english`, which the projection
 * calls per row and per emission. A memo in the state would lose every purchase made while the screen was
 * not composed, and a memo in the adapter is the class this migration is deleting; a `:ui` object is the
 * one home all three can reach (`:app` may name `:ui`, and the host is `:ui` too).
 *
 * <p>**It is the in-process half only.** The durable half stays `EnglishLookup`'s cache, which both buyers
 * already consult first - a restart empties this memo and the cache is what answers after it, through the
 * peek. Neither is a second decision: `EnglishLookup` is what *buys* and what keys the cache, and this holds
 * only the answer a purchase or a peek produced.
 *
 * <p>**`blurred` is always `null` here**, and that is not an omission: the pixels are the smear factory's,
 * and until it exists the projection conceals a blurred English row with its placeholder - no text, the same
 * shape for the same row - which is the safe half of §2.3(1). A cell can only assert the words, which is
 * exactly the half this object owns.
 *
 * <p>**Bounded and synchronized, for the tree's own two reasons.** The deleted adapter capped its memo at
 * 128 and evicted in access order, so a conversation's worth of English is kept and nothing grows without
 * end; and an answer may arrive from the receive path's worker while the composition is reading, so every
 * access takes the same lock.
 */
object EnglishHold {

    /** The deleted adapter's own capacity: enough for a conversation, small enough to be free. */
    private const val CAPACITY = 128

    private val held: LinkedHashMap<String, String> =
        object : LinkedHashMap<String, String>(CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > CAPACITY
        }

    /** The English a buyer has in hand for this row. The words only; the pixels are the smear's. */
    @JvmStatic
    fun put(uuid: String, text: String) {
        synchronized(held) { held[uuid] = text }
    }

    /**
     * The English in hand for this row, or `null` when nothing has been bought or peeked in this process.
     *
     * <p>`null` is what `UiEnglishRow.Pending` rests on: a bare bar the owner can tap, which is the honest
     * state of a row nobody has bought anything for - and the state that must stop being pending the moment
     * a purchase lands, which is what this object is for.
     */
    @JvmStatic
    fun of(uuid: String): EnglishInHand? {
        val text = synchronized(held) { held[uuid] } ?: return null
        // The pixels are the smear factory's and there are none yet: a placeholder, never the words.
        return EnglishInHand(text, null)
    }

    /** Forget one row's English, for a row that was edited or retracted and whose answer is now stale. */
    @JvmStatic
    fun forget(uuid: String) {
        synchronized(held) { held.remove(uuid) }
    }

    /** Nothing is in hand: a cell's own reset, and the honest state of a fresh process. */
    @JvmStatic
    fun clear() {
        synchronized(held) { held.clear() }
    }
}
