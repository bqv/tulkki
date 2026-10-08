package uk.xa0.tulkki.ui

import org.junit.Assert
import org.junit.Test

/**
 * The on→off transition's decision, on the JVM.
 *
 * `TranslationSettingsStore` itself cannot be built here - it is a `PreferenceDataStore` over a real
 * Android `Context`, and the clear it calls reaches a real database - so the one thing the store adds
 * to the flip is extracted as [TranslationSettingsStore.onLanguageWrite] and driven here with two
 * recording actions standing in for the clear and the cancel.
 *
 * Two facts, and the second is the mirror case: the write that turns the interpreter off runs
 * *both* actions, so the queue's scheduled records are cancelled rather than merely dropped (the
 * recorded defect: `clearInterpreterState` dropped the pending rows and left the three
 * `tulkki-translation-*` names to wake the process at their due time); and no other write runs
 * either, so a fresh enable cannot cancel the work it has just enqueued.
 *
 * The names the cancel takes are deliberately not restated here - `TranslationWorkTest` is their
 * single source of truth, and this class never sees them. What this pins is that the flip reaches a
 * cancel at all, and only then.
 */
class TranslationSettingsStoreTest {

    private val ran = ArrayList<String>()

    /** The transition a language write of the two given modes performs, as the actions it ran. */
    private fun transition(wasInterpreting: Boolean, nowInterpreting: Boolean): List<String> {
        ran.clear()
        TranslationSettingsStore.onLanguageWrite(
            wasInterpreting,
            nowInterpreting,
            Runnable { ran.add("drop") },
            Runnable { ran.add("cancel") },
        )
        return ran.toList()
    }

    @Test
    fun theWriteThatTurnsTheInterpreterOffDropsAndCancels() {
        Assert.assertEquals(
            "the flip must cancel, not merely drop: the scheduled records are what wake the process",
            listOf("drop", "cancel"),
            transition(true, false),
        )
    }

    @Test
    fun aWriteThatTurnsTheInterpreterOnCancelsNothing() {
        Assert.assertEquals(
            "a fresh enable must not cancel the work it has just enqueued",
            emptyList<String>(),
            transition(false, true),
        )
    }

    @Test
    fun noOtherWriteRunsTheTransition() {
        Assert.assertEquals("already on", emptyList<String>(), transition(true, true))
        Assert.assertEquals("already off", emptyList<String>(), transition(false, false))
    }
}
