package uk.xa0.tulkki.data.updb

import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.libs.Jid

/**
 * The account a UnifiedPush distributor is bound to, and the Jid it is bound to, moved down out of
 * `uk.xa0.tulkki.app.services.UnifiedPushBroker` by 3.7 pair 2 and folded into `updb/` with the database it
 * is a parameter of (S5-3).
 *
 * It is a value type with no behaviour - two public finals a constructor fills - and `UnifiedPushDatabase`
 * takes one as a parameter, which is the whole reason `:data` named `:app` here. Named
 * `UnifiedPushTransport` rather than `Transport` because the wire layer already has a `Transport` of its own,
 * and two modules holding a class of the same simple name is a warning this commit has no reason to add.
 *
 * `open` because the Java class was: `UnifiedPushBroker.Transport` extends it to carry the island's
 * marker interface, and a Kotlin class is final by default.
 */
open class UnifiedPushTransport(
    @JvmField val account: Account,
    @JvmField val transport: Jid,
)
