package uk.xa0.tulkki.app.extras

import android.util.Log
import java.util.Arrays
import java.util.Random
import uk.xa0.tulkki.app.BuildConfig

/**
 * The provider catalogue: where the list comes from, which domains are refused, and one random
 * domain to register on.
 *
 * <p>`LOGTAG` and `PROVIDER_URL` stay Java-visible **static fields** - `ProviderService` reads both -
 * so they are `@JvmField`/`const` in the companion rather than members of an `object`.
 *
 * <p>`DOMAIN` is the same story one level down: `ProviderService` reads
 * `ProviderCatalog.DOMAIN.BLACKLISTED_DOMAINS` as a **field** and `UiAppHost` calls
 * `ProviderCatalog.DOMAIN.getRandomServer()`, so `DOMAIN` is a class with a private constructor and
 * a companion whose list is a `@JvmField` and whose method is `@JvmStatic`. In an `object` the
 * list would have been an instance field on the singleton and Java could not read it.
 *
 * <p><strong>There is no fallback domain any more.</strong> Upstream shipped one as a fallback
 * list and again as the literal `getRandomServer` returned when the fetch threw; both are gone,
 * because a domain the app substitutes silently is a provider choice the owner did not make.
 * `getRandomServer` answers **null** when the catalogue is empty, and the screen that asks for one
 * says so and opens the own-server field instead.
 */
class ProviderCatalog private constructor() {

    companion object {

        @JvmField val LOGTAG: String = BuildConfig.APP_NAME

        const val PROVIDER_URL: String = "https://data.xmpp.net/providers/v2/providers-A.json"
    }

    class DOMAIN private constructor() {

        companion object {

            // don't use these servers in provider list
            @JvmField val BLACKLISTED_DOMAINS: List<String> = Arrays.asList<String>()

            // choose a random server for registration; null when no catalogue is available, which
            // the caller must answer by asking the owner for a server rather than by guessing one
            @JvmStatic
            fun getRandomServer(): String? {
                val domains: List<String> = ProviderService.getProviders()
                if (domains.isEmpty()) {
                    Log.d(
                            ProviderCatalog.LOGTAG,
                            "No provider list available; the server must be entered by hand")
                    return null
                }
                val domain: String = domains[Random().nextInt(domains.size)]
                Log.d(ProviderCatalog.LOGTAG, "MagicCreate account on domain: " + domain)
                return domain
            }
        }
    }
}
