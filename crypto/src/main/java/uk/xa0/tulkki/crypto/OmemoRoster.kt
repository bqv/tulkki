package uk.xa0.tulkki.crypto

import uk.xa0.tulkki.libs.Jid

/**
 * What the crypto island needs from a roster, in island-owned vocabulary.
 *
 * Pair 5 of the module cycle plan (docs/MIGRATION.md "The cycle rules" §3, D6): the interface is
 * declared on the island side, `uk.xa0.tulkki.data.model.Roster` implements it, and the
 * island stops naming `uk.xa0.tulkki.data.model`.
 */
interface OmemoRoster {

    fun getContact(jid: Jid): OmemoContact
}
