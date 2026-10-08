package uk.xa0.tulkki.app.utils

import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy

/**
 * The transcoder's four rate presets, as the two `:app` callers read them.
 *
 * <p>All four are Java-visible **static fields** - `XmppTulkkiHost`'s port adapter and
 * `AttachFileToConversationRunnable` read `TranscoderStrategies.VIDEO_720P` and its siblings - so
 * they live in a companion object as `@JvmField`s rather than in an `object`, where they would be
 * instance fields on the singleton and Java could not reach them.
 *
 * <p><strong>Two numeric traps, decided per site.</strong> The video bit rates were written
 * `2L * 1000 * 1000` and `1000 * 1000`: in Java the second is an `int` widened to the builder's
 * `long` parameter, which Kotlin will not do, so it is written `1000L * 1000` here - the same
 * 1,000,000. The two audio rates are `192L * 1000` and `128L * 1000` for the same reason. Every
 * value is unchanged; only the literals' types are explicit.
 *
 * <p>The private constructor threw `IllegalStateException("Do not instantiate me")` in the Java this
 * replaces. It is `private` here instead, which is unreachable from Kotlin and Java alike, so the
 * throw was already dead in every reachable path; nothing observable moves.
 */
class TranscoderStrategies private constructor() {

    companion object {

        @JvmField
        val VIDEO_720P: DefaultVideoStrategy =
                DefaultVideoStrategy.atMost(720)
                        .bitRate(2L * 1000 * 1000)
                        .frameRate(30)
                        .keyFrameInterval(3F)
                        .build()

        @JvmField
        val VIDEO_360P: DefaultVideoStrategy =
                DefaultVideoStrategy.atMost(360)
                        .bitRate(1000L * 1000)
                        .frameRate(30)
                        .keyFrameInterval(3F)
                        .build()

        // TODO do we want to add 240p (@500kbs) and 1080p (@4mbs?) ?
        // see suggested bit rates on https://www.videoproc.com/media-converter/bitrate-setting-for-h264.htm

        @JvmField
        val AUDIO_HQ: DefaultAudioStrategy =
                DefaultAudioStrategy.builder()
                        .bitRate(192L * 1000)
                        .channels(2)
                        .sampleRate(DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT)
                        .build()

        @JvmField
        val AUDIO_MQ: DefaultAudioStrategy =
                DefaultAudioStrategy.builder()
                        .bitRate(128L * 1000)
                        .channels(2)
                        .sampleRate(DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT)
                        .build()

        // TODO if we add 144p we definitely want to add a lower audio bit rate as well
    }
}
