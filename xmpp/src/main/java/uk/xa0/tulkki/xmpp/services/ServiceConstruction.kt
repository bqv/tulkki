package uk.xa0.tulkki.xmpp.services

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import org.conscrypt.Conscrypt
import org.jxmpp.stringprep.libidn.LibIdnXmppStringprep
import uk.xa0.tulkki.libs.TextAvatar
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.Resolver
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.LongConsumer
import java.util.function.LongSupplier
import java.util.function.Supplier

/**
 * Tulkki: the service's construction, lifted out of `XmppConnectionService`
 *.
 *
 * `onCreate` is the one place the boundary's ports and crypto handles are taken from the factory the
 * composition root installed. Every field it writes is **C74's**, not this chunk's, so none of them
 * move: the assignments stay a private Java method, `installTulkkiPorts`, handed in as the `Consumer`
 * the Kotlin calls once the factory has answered. The post-install reaches are the Java's private
 * accessors (`tulkkiPorts()`, `themePort()`, `compatibility()`, `omemoSettings()`,
 * `liveLocationHook()`), so they arrive as `Supplier`s and their `require`/`IllegalStateException`
 * still fires at the first use when no factory is installed - the same statement the Java threw at.
 * The two old-name fields the body dereferences (`mNotificationService`, `mChannelDiscoveryService`)
 * arrive the same way, and the private slots `mDrawableCache` and `mLastActivity` are written
 * through the setters the Java delegation binds, so no visibility was widened.
 *
 * `mForceDuringOnCreate` (this chunk's own `AtomicBoolean`) travels by value. The Java's statement
 * order is kept exactly, including the `Security.insertProviderAt` catch of `Throwable`, and the
 * `mLastActivity == 0` read before the preference is written. The one dead local the Java carried
 * (`final Context appCtx =
 * getApplicationContext();`, never read) is dropped.
 */
object ServiceConstruction {

    @JvmStatic
    fun onCreate(
        service: XmppConnectionService,
        factory: TulkkiPorts.Factory?,
        installPorts: Consumer<TulkkiPorts>,
        liveLocationHook: Supplier<LiveLocationHook>,
        tulkkiPorts: Supplier<TulkkiPorts>,
        themePort: Supplier<ThemePort>,
        compatibility: Supplier<CompatibilityPort>,
        omemoSettings: Supplier<OmemoSettingsPort>,
        forceDuringOnCreate: AtomicBoolean,
        notificationService: Supplier<NotificationPort>,
        channelDiscovery: Supplier<ChannelDiscoveryPort>,
        setDrawableCache: Consumer<LruCache<String, Drawable>>,
        lastActivityGet: LongSupplier,
        lastActivitySet: LongConsumer,
        initializeDatabaseInBackground: Runnable,
        lastActivitySettingKey: String,
    ) {
        // Tulkki: the boundary's two ports, from the factory the composition root installed at
        // process start. Both belong to this service for the life of the process, like everything else
        // here, so this is where they are taken - and a build that forgot the install is named by
        // `sendGate()` at the first outgoing stanza rather than sending it untranslated.
        val tulkkiFactory = factory
        if (tulkkiFactory != null) {
            val ports = tulkkiFactory.create(service)
            installPorts.accept(ports)
        }
        liveLocationHook.get().setOnSessionExpired(Runnable { service.updateConversationUi() })
        tulkkiPorts.get().loggingHook().install()
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.FINEST)
        LibIdnXmppStringprep.setup()
        service.setTheme(themePort.get().theme())
        themePort.get().applyCustomColors(service)
        if (compatibility.get().runsTwentySix()) {
            notificationService.get().initializeChannels()
        }
        channelDiscovery.get().initializeMuclumbusService()
        forceDuringOnCreate.set(compatibility.get().runsAndTargetsTwentySix(service))
        service.toggleForegroundService()
        Teardown.setDestroyed(false)
        omemoSettings.get().apply(service)
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (throwable: Throwable) {
            Log.e(Config.LOGTAG, "unable to initialize security provider", throwable)
        }
        Resolver.init(service)
        service.updateMemorizingTrustManager()
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        setDrawableCache.accept(
            object : LruCache<String, Drawable>(cacheSize) {
                override fun sizeOf(key: String, drawable: Drawable): Int {
                    if (drawable is BitmapDrawable) {
                        val bitmap = drawable.bitmap
                        if (bitmap == null) return 1024

                        return bitmap.byteCount / 1024
                    } else if (drawable is TextAvatar) {
                        return 50
                    } else {
                        return drawable.intrinsicWidth * drawable.intrinsicHeight * 40 / 1024
                    }
                }
            },
        )
        if (lastActivityGet.asLong == 0L) {
            lastActivitySet.accept(
                service.getPreferences().getLong(lastActivitySettingKey, System.currentTimeMillis()),
            )
        }

        Log.d(Config.LOGTAG, "starting database initialization in background...")
        Thread(initializeDatabaseInBackground, "db-init").start()
    }
}
