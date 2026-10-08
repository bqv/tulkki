package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: channel discovery, in island vocabulary.
 *
 * <p>Two lifecycle answers and nothing else: the island starts the directory client at process
 * start and drops its cache when the accounts change. The search itself is no longer a member -
 * port-13's `RoomRef` deletion moved it to the adapter the composition root builds, which is the
 * only thing that names it.
 */
interface ChannelDiscoveryPort {

    fun initializeMuclumbusService()

    fun cleanCache()
}
