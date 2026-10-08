package uk.xa0.tulkki.app

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.OkHttpClient
import uk.xa0.tulkki.app.http.HttpConnectionManager

/**
 * Tulkki: Glide's one module, ported from Java by the `applast` lane.
 *
 * <p>[GlideModule] is read by Glide's annotation processor at **build time**, not at runtime, so the
 * annotation and this class's visibility are load-bearing rather than decorative: the processor emits
 * `GeneratedAppGlideModuleImpl`, which names and instantiates this class, and it must stay a public
 * top-level class with a public no-argument constructor. Kotlin's defaults give both - a top-level
 * class is public and has a no-argument constructor when none is written - and every body below is
 * the Java's line for line, comments included.
 */
@GlideModule
class CustomGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {

        // Start with the app's base OkHttpClient, which is already configured with the app's custom
        // trust managers. This is the crucial step that makes direct connections (without a proxy)
        // work correctly.
        val baseClient: OkHttpClient = HttpConnectionManager.okHttpClient(context)

        val preferences: SharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context)

        // Create a dynamic ProxySelector. This selector's 'select' method will be called for each new
        // network request, so it will always use the most current proxy settings.
        val proxySelector =
                object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> {
                        val useI2p = preferences.getBoolean("use_i2p", false)
                        if (useI2p) {
                            return listOf(HttpConnectionManager.getProxy(true))
                        }
                        val useTor = preferences.getBoolean("use_tor", false)
                        if (useTor) {
                            return listOf(HttpConnectionManager.getProxy(false))
                        }

                        // When no custom proxy is active, it is VITAL to return Proxy.NO_PROXY.
                        // Returning null or an empty list would cause OkHttp to fall back to
                        // system-wide proxies, which we want to avoid. This forces a direct
                        // connection using our correctly configured (and trusted) OkHttpClient.
                        return listOf(Proxy.NO_PROXY)
                    }

                    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                        // This method is called if a connection to a proxy fails.
                        // You could add logging here for debugging if needed.
                    }
                }

        // Create a new client by cloning the base client and setting our dynamic proxy selector.
        val clientWithProxy: OkHttpClient =
                baseClient.newBuilder().proxySelector(proxySelector).build()

        val factory = OkHttpUrlLoader.Factory(clientWithProxy)

        registry.replace(GlideUrl::class.java, InputStream::class.java, factory)
    }
}
