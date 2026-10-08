package uk.xa0.tulkki.xmpp.utils

/**
 * A [SerialSingleThreadExecutor] that drops whatever is queued (and cancels the running task if it
 * is a [SerialSingleThreadExecutor.Cancellable]) whenever new work arrives.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **Both methods keep `@Synchronized`**, and `execute` keeps its `override` and its `super` call:
 *    the class is instantiated from Java (`XmppConnectionService:392-393`, `MessageSearchTask:61`)
 *    and from Kotlin (`ShortcutService.kt:64`, `EmojiSearch.kt:155`).
 * 2. **`tasks` and `active` are read from the base class**, as the Java subclass did; the
 *    `instanceof`-and-cast pair is the safe cast (`as?`), which is the same test and the same call.
 * 3. **The inherited `tasks` field is package-private in Java and `internal` in Kotlin**, which is
 *    the closest visibility this tree uses for that access; no Java caller touches it.
 */
class ReplacingSerialSingleThreadExecutor(name: String) : SerialSingleThreadExecutor(name) {

    @Synchronized
    override fun execute(r: Runnable) {
        tasks.clear()
        (active as? Cancellable)?.cancel()
        super.execute(r)
    }

    @Synchronized
    fun cancelRunningTasks() {
        tasks.clear()
        (active as? Cancellable)?.cancel()
    }
}
