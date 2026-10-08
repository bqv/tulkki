package uk.xa0.tulkki.data.model

import uk.xa0.tulkki.libs.Transferable

/**
 * The stand-in a transfer shows while it is not a real transfer: a status, and no work.
 *
 * <p>It implements [uk.xa0.tulkki.libs.Transferable] so it can be put where a transfer belongs, and it
 * answers every action with "nothing happened": [start] refuses, [cancel] does nothing, and the three
 * facts it reports are the caller's own status with a null size and no progress.
 *
 * Ported from Java by the `port` stage (port-2). Three decisions, all of them deliberate:
 *
 * 1. **Every member is an `override`, and Kotlin demands it** - Java's `@Override` on all five was
 *    advisory; without the modifier Kotlin refuses to compile the file. `Transferable` declares
 *    all five, so all five are overrides, including [cancel] whose Java body was empty.
 * 2. **All five members stay functions, and the property form was tried and refused.** Kotlin
 *    synthesises `status`, `fileSize` and `progress` from a Java type's `getStatus()`, `getFileSize()`
 *    and `getProgress()`, so writing them as `override val`s looks like the same class in Kotlin -
 *    and it does not compile: measured here, `e: … 'status' overrides nothing`, and the class was then
 *    reported as not implementing its abstract members. **Synthesis is for reading a Java type, not
 *    for overriding one**, so the getters are written out as functions and the JVM surface is
 *    unchanged: `:ui`'s `instanceof TransferablePlaceholder` and every `.getStatus()` call resolve
 *    exactly as before. `start()` and `cancel()` were always functions.
 * 3. **It is not `open`.** Nothing in the tree extends it (`grep` finds no `extends
 *    TransferablePlaceholder`), and Kotlin classes are final by default, so the finality is the
 *    language's rather than something this file asked for - but it is stated here so that a later
 *    reader does not add `open` "for parity" with Java's extensible-by-default classes. Its
 *    constructor stays Java-callable exactly as it was: `:app`'s `DataStaticsHost.newTransferablePlaceholder`
 *    runs `new TransferablePlaceholder(status)` and is unchanged.
 *
 * Nothing is static and nothing is a field a Java caller reads, so this file adds **zero** interop
 * annotations.
 */
class TransferablePlaceholder(private val statusValue: Int) : Transferable {

    /** Always false: a placeholder refuses to start. */
    override fun start(): Boolean = false

    /** The status the caller gave - the one thing this object is. */
    override fun getStatus(): Int = statusValue

    /** Always null: a placeholder holds no file. */
    override fun getFileSize(): Long? = null

    /** Always zero: a placeholder has not begun. */
    override fun getProgress(): Int = 0

    /** Nothing to cancel. */
    override fun cancel() {}
}
