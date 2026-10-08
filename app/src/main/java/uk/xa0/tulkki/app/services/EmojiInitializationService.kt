package uk.xa0.tulkki.app.services

import android.content.Context
import android.util.Log
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import uk.xa0.tulkki.xmpp.Config

/**
 * Installs `EmojiCompat` at process start.
 *
 * <p>An `object` with one `fun execute`, because the Java was a class whose only member was a
 * `public static` method and whose only caller - `TulkkiApplication.onCreate`, Kotlin since
 * `port-6` - writes `EmojiInitializationService.execute(context)`. Nothing ever constructed the
 * class; the Java's implicit public constructor has no Kotlin spelling in an `object`, and no call
 * site wants one.
 *
 * <p>Interop tax: 0. `port-18` dropped the `@JvmStatic` the port kept while that caller was Java:
 * no `.java` file names `EmojiInitializationService`, so §5.5's audit leaves it owed to nobody.
 *
 * <p>`registerInitCallback` takes the Java's anonymous `EmojiCompat.InitCallback`, an abstract class,
 * as an `object :`; both overrides keep their `super` calls and the initialised/failed log lines
 * verbatim, including the `@Nullable Throwable` parameter that stays `Throwable?`.
 *
 * <p>Name-string audit: 0 hits for `EmojiInitializationService` in the manifest, `res/xml`,
 * `res/layout*`, `preferences_*.xml` or the ProGuard rules.
 */
object EmojiInitializationService {

    fun execute(context: Context) {
        EmojiCompat.init(BundledEmojiCompatConfig(context).setReplaceAll(true))
                .registerInitCallback(
                        object : EmojiCompat.InitCallback() {
                            override fun onInitialized() {
                                Log.d(Config.LOGTAG, "initialized EmojiCompat")
                                super.onInitialized()
                            }

                            override fun onFailed(throwable: Throwable?) {
                                Log.e(
                                        Config.LOGTAG,
                                        "failed to initialize EmojiCompat",
                                        throwable)
                                super.onFailed(throwable)
                            }
                        })
    }
}
