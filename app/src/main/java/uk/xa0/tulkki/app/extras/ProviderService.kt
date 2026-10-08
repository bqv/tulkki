package uk.xa0.tulkki.app.extras

import android.os.AsyncTask
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.HashSet
import uk.xa0.tulkki.app.http.HttpConnectionManager
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Fetches the public provider list into [providers], from the network or from the bundled resource.
 *
 * <p>`getProviders()` and the `providers` list stay **static fields/methods**: `ProviderCatalog`'s
 * `getRandomServer` reads the method and `UiAppHost` runs the task, so the list is a `@JvmField` in
 * the companion and the accessor a `@JvmStatic` one. The instance field the task reads,
 * `xmppConnectionService`, was package-private and is `private` here - nothing in the tree ever
 * assigns it, in the Java either, which is why both tunnel flags are always false.
 *
 * <p><strong>The three reads of `XmppActivity.staticXmppConnectionService` are left as platform-typed
 * dereferences, deliberately.</strong> Java read that static field three times without holding it, so
 * a null there is an NPE in the `else` branch that the surrounding `catch (Exception)` turns into
 * `isError` - and that is a real outcome of this method, not an accident to smooth over. Kotlin
 * cannot smart-cast a mutable Java static field, so binding it to a local would be the change, not
 * the port.
 *
 * <p><strong>A null `jid` still fails the whole parse.</strong> `getProviderFromJSON` called
 * `provider.isEmpty()` with no check, so a list entry without a `jid` threw an NPE, and the caller's
 * catch recorded `isError` and the update counted as failed. Kotlin cannot dereference the nullable
 * value either, so the throw is written out at the same point rather than turning a failed update
 * into a silently skipped entry.
 */
class ProviderService : AsyncTask<XmppActivity, Any, Boolean>() {

    private var xmppConnectionService: XmppConnectionService? = null

    private var mUseTor: Boolean = false

    private var mUseI2P: Boolean = false

    override fun doInBackground(vararg activity: XmppActivity?): Boolean {
        val jsonString = StringBuilder()
        var isError = false
        val service = xmppConnectionService
        mUseTor = service != null && service.useTorToConnect()
        mUseI2P = service != null && service.useI2PToConnect()
        if (!mUseTor && mUseI2P) {
            // Do not update providers if all connections tunneled to I2P (via settings checkbox) without Tor
            return true
        }

        try {
            if (XmppActivity.staticXmppConnectionService != null &&
                    XmppActivity.staticXmppConnectionService.getBooleanPreference(
                            "load_providers_list_external",
                            R.bool.load_providers_list_external) &&
                    XmppActivity.staticXmppConnectionService.hasInternetConnection()) {
                Log.d(
                        ProviderCatalog.LOGTAG,
                        "ProviderService: Updating provider list from " +
                                ProviderCatalog.PROVIDER_URL)
                val inputStream =
                        HttpConnectionManager.open(
                                ProviderCatalog.PROVIDER_URL, mUseTor, mUseI2P)
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line = reader.readLine()
                while (line != null) {
                    jsonString.append(line)
                    line = reader.readLine()
                }
                inputStream.close()
                reader.close()
            } else {
                Log.d(
                        ProviderCatalog.LOGTAG,
                        "ProviderService: Updating provider list from " + "local")
                val inputStream =
                        XmppActivity.staticXmppConnectionService
                                .resources
                                .openRawResource(R.raw.providers_a)
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line = reader.readLine()
                while (line != null) {
                    jsonString.append(line)
                    line = reader.readLine()
                }
                inputStream.close()
                reader.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isError = true
        }

        try {
            getProviderFromJSON(jsonString.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            isError = true
        }
        if (isError) {
            Log.d(ProviderCatalog.LOGTAG, "ProviderService: Updating provider list failed")
        }
        return !isError
    }

    private fun getProviderFromJSON(json: String) {
        val providersList: List<ProviderHelper> =
                Gson().fromJson(json, object : TypeToken<List<ProviderHelper>>() {}.type)

        for (i in providersList.indices) {
            val provider = providersList[i].get_jid()
            if (provider == null) {
                // The Java dereferenced this without a check: a jid-less entry failed the parse,
                // and the caller's catch made that a failed update.
                throw NullPointerException()
            }
            if (provider.isNotEmpty()) {
                if (!ProviderCatalog.DOMAIN.BLACKLISTED_DOMAINS.contains(provider)) {
                    Log.d(
                            ProviderCatalog.LOGTAG,
                            "ProviderService: Updating provider list. Adding " + provider)
                    providers.add(provider)
                }
            }
        }
    }

    companion object {

        @JvmField val providers: MutableList<String> = ArrayList()

        @JvmStatic
        fun getProviders(): List<String> {
            val provider = HashSet<String>()
            if (!providers.isEmpty()) {
                provider.addAll(providers)
            }
            return ArrayList(provider)
        }
    }
}
