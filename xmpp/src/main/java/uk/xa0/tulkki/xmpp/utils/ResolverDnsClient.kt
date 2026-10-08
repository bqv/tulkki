package uk.xa0.tulkki.xmpp.utils

import org.minidns.dnsmessage.DnsMessage
import org.minidns.iterative.ReliableDnsClient

/**
 * The client `Resolver` resolves through, and the fork's "DNSSEC on by default" delta expressed
 * where it can still be expressed.
 *
 * The fork's `DnsClient` defaulted `askForDnssec` to `true`, and that class's `newQuestion` turned
 * the flag into EDNS's DO bit; the release's default is `false`. Only `DnsClient` carries the
 * accessor, and the client `Resolver` used to hold is hla's `ResolverApi.INSTANCE`, which builds its
 * own `ReliableDnsClient` — so the flag cannot be *set* on it. This subclass is the accessor:
 * `ReliableDnsClient`'s two inner clients both call this override after their own `newQuestion`, so
 * the DO bit is set here.
 *
 * The DNSSEC path needs none of it: `DnssecClient` sets DO and CD in its own `newQuestion`.
 *
 * Two divergences from the fork, recorded rather than hidden:
 *
 *  1. `ResolverApi.INSTANCE` stays unused: a subclass cannot be injected into it, so `Resolver` owns
 *     this instance instead. Both share `AbstractDnsClient.DEFAULT_CACHE`, so the cache the resolver
 *     clears is the one these clients fill.
 *  2. The override covers both inner clients, so the *iterative* fallback also asks for DO. In the
 *     fork the flag lived on the recursive `DnsClient` alone, and the inner `IterativeDnsClient` has
 *     no such field. One override cannot tell the two apart: the only signal is the recursion bit,
 *     which the builder takes through `setRecursionDesired` and exposes no getter for.
 */
internal class ResolverDnsClient : ReliableDnsClient() {

    override fun newQuestion(questionMessage: DnsMessage.Builder): DnsMessage.Builder {
        questionMessage.ednsBuilder.setDnssecOk()
        return super.newQuestion(questionMessage)
    }
}
