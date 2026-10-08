package uk.xa0.tulkki.xmpp.mam

/**
 * Tulkki: why a MAM query ended without a `<fin>`.
 *
 * "Design: synchronisation" §2.2 and §3.2: today a timed-out query is simply removed from the
 * service's set and its callback invoked with `done = false`, which is indistinguishable from a
 * finished one - so the pass over the gap either never runs or runs over a database that is still
 * being filled. This names the three ways that happens, so the ledger can record the region as
 * `DEGRADED` rather than lose it. The island's words, reduced to what the engine distinguishes;
 * `ABORTED_AT_LIMIT` is not here because that one arrives as a `<fin>` with a count at the ceiling.
 *
 * Ported from `MamAbort.java`.
 * It is *ours*, so it is converted in place rather than reproduced: the Java enum's whole surface is
 * the three constants plus the compiler-generated `values()`/`valueOf()`, and Kotlin's `enum class`
 * generates both with the same descriptors. Nothing is static-in-a-body, so no `@JvmStatic` is owed,
 * and there is no Java `switch` over it (the one reader, `SyncEngine.reasonOf`, is Kotlin and an
 * exhaustive `when`), so no constant needs a `const val` stand-in.
 */
enum class MamAbort {
    /** The IQ timed out. */
    TIMEOUT,
    /** The server answered an error rather than a `<fin>`. */
    ERROR,
    /** The query was killed - a conversation closed, a session torn down. */
    KILLED,
}
