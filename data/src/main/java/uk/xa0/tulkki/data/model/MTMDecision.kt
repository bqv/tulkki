/* MemorizingTrustManager - a TrustManager which asks the user about invalid
 *  certificates and memorizes their decision.
 *
 * Copyright (c) 2010 Georg Lukas <georg@op-co.de>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package uk.xa0.tulkki.data.model

/**
 * A MemorizingTrustManager decision: which of the four answers the owner gave to an invalid
 * certificate, as the integer the activity hands back.
 *
 * Ported from Java by the `port` stage (port-2). The upstream **MIT notice is carried verbatim**
 * above, as the licence requires and as the Java file had it.
 *
 * Decisions taken rather than inherited:
 *
 * 1. **The four constants are a `companion object` of `const val`s.** Their one caller is `:ui`'s
 *    `MemorizingActivity`, which writes `MTMDecision.DECISION_ALWAYS` and its three siblings; a
 *    `const val` in a companion is compiled to a static field **on `MTMDecision` itself**, so those
 *    Java reads are unchanged and no `@JvmField` is needed. Changing them to `@JvmField val`s would
 *    also work and would be strictly more permissive; `const val` is the closer translation of
 *    Java's `final static int`, which is a compile-time constant too.
 * 2. **`state` becomes an ordinary Kotlin property, with no `@JvmField`.** The field has **no
 *    reader and no writer anywhere in the tree** - the only mentions of this class outside it are the
 *    four constants above, plus a comment in the XMPP island's `MemorizingTrustManager` saying it
 *    kept the holder local - so there is no Java caller whose field access has to keep working, and
 *    adding `@JvmField` would be interop debt with no creditor. A future Java caller reads
 *    `getState()`/`setState()`, which is the idiomatic Kotlin-facing surface and still legal Java.
 *    This is the one place in these first files where the annotation count is deliberately zero
 *    rather than non-zero.
 * 3. **The class stays final**, as Kotlin classes are by default: nothing extends it (no `extends
 *    MTMDecision` anywhere), so no `open` is added. Its constructor stays public, so a Java
 *    `new MTMDecision()` would still compile if one is ever wanted.
 */
class MTMDecision {

    /** The decision in force, or [DECISION_INVALID] until one is given. */
    var state: Int = DECISION_INVALID

    companion object {
        const val DECISION_INVALID = 0
        const val DECISION_ABORT = 1
        const val DECISION_ONCE = 2
        const val DECISION_ALWAYS = 3
    }
}
