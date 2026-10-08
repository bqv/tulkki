package uk.xa0.tulkki.ui

import uk.xa0.tulkki.libs.Jid

/**
 * Which field of the create-account screen is at fault when a typed username and a typed server do
 * not make the Jid the account would be registered under.
 *
 * The screen used to answer that question twice and wrongly both times: it asked whether
 * [Jid.getLocal] equalled *itself*, so the username was never checked at all, and it reported every
 * [IllegalArgumentException] as a bad username, so an empty "own provider" server field read as a
 * bad name and the message landed on the wrong field. Both rules live here, away from the views, so
 * they are pinned by JVM tests rather than by driving the screen.
 *
 * **The case question.** `Jid.ofLocalAndDomain` runs the local part through jxmpp's `Localpart`,
 * which *case-folds* it: `"Alice"` becomes `"alice"` and `"АЛИСА"` becomes `"алиса"`. So the check
 * must compare ignoring case. Registering `alice@…` for a typed `Alice` is XMPP's own normalisation
 * and what this screen has always done, and comparing exactly - the shape the dead check was
 * reaching for - would newly refuse every capitalised name. What the check is for is the other
 * mapping, the one that is not case: `"İstanbul"` maps to `"i̇stanbul"` (a combining dot creeps in),
 * which is a name the owner did not type and is worth refusing.
 *
 * Pure: [Jid] and jxmpp only, no Android types.
 */
object SignupIdentity {

    /** The single field a check blames. */
    enum class Problem {
        NONE,
        USERNAME,
        SERVER,
    }

    /**
     * A local part known to be acceptable, used to ask whether the server alone is what cannot be a
     * Jid. The domain is validated nowhere else - jxmpp's `Domainpart` takes nearly any non-empty
     * string - so this is the only way to tell the two fields apart without restating its rules.
     */
    private const val PROBE_LOCAL = "probe"

    /**
     * The field at fault, or [Problem.NONE].
     *
     * The empty username is checked first because it is the field the screen has always pointed at,
     * and because a blank form is a username problem before it is anything else.
     *
     * @param jid the jid the two fields made, or `null` when they made none because the construction
     *     threw
     * @param typedUsername exactly what stands in the username field
     * @param server exactly what stands in the server field - the "own provider" one, or the domain
     *     the rest of the screen worked out
     */
    @JvmStatic
    fun problem(jid: Jid?, typedUsername: String?, server: String?): Problem {
        if (typedUsername.isNullOrEmpty()) {
            return Problem.USERNAME
        }
        if (server == null || server.trim { it <= ' ' }.isEmpty()) {
            // An empty "own provider" field is a server problem, and so is one holding only
            // whitespace, which jxmpp happily accepts as a domain. Java's `trim()` strips `<= ' '`,
            // not Kotlin's default whitespace set, so the explicit predicate is load-bearing.
            return Problem.SERVER
        }
        if (jid == null) {
            // Both fields are non-empty and the construction still threw. Ask which of the two alone
            // cannot be a Jid.
            try {
                Jid.ofLocalAndDomain(PROBE_LOCAL, server)
            } catch (e: IllegalArgumentException) {
                return Problem.SERVER
            }
            return Problem.USERNAME
        }
        val local = jid.getLocal()
        if (local == null || !local.equals(typedUsername, ignoreCase = true)) {
            return Problem.USERNAME
        }
        return Problem.NONE
    }
}
