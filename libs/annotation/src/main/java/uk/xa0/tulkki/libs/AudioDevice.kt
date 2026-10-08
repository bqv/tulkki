package uk.xa0.tulkki.libs

/**
 * The audio route a call is using, extracted from `uk.xa0.tulkki.app.services.CallIntegration` by
 * 3.7 pair 3.
 *
 * It is a `:libs` type because that is the only home all three of its namers may reach: the XMPP
 * island may name `:libs` and nothing above it, `:ui` may name `:libs`, and `:app` may name
 * everything. The enum was nested in a `:app` class, so the island's two
 * `CallIntegration.AudioDevice` parameters and `:ui`'s one callback override were both naming the
 * composition root; a value type with no behaviour belongs in the one module every side already
 * depends on. Nothing about it changed in the move.
 *
 * **Converted from Java (2026-10-08, lane E).** The Java `enum` is a Kotlin `enum class`: the six
 * constants, `values()` and `valueOf(String)` come out with the same names and descriptors, and
 * Kotlin adds one `getEntries()` beside them. It is **not** the wildcard boundary it was once
 * blamed for. Measured with kotlinc 2.3.21: a parameter-position `Set<AudioDevice>` compiles to
 * `Set<? extends AudioDevice>` whether `AudioDevice` is a Java enum **or** a Kotlin `enum class`,
 * while a Java `final class` argument gets no wildcard - so the wildcard follows the type being an
 * enum, not the language declaring it, and every Kotlin signature that mentions this type is
 * byte-for-byte unmoved by the conversion. The `@JvmSuppressWildcards` sites that keep
 * `onAudioDeviceChanged` invariant (`OnJingleRtpConnectionUpdate.kt`, `AppRTCAudioManager.kt`)
 * stay exactly as necessary as they were.
 */
enum class AudioDevice {
    NONE,
    SPEAKER_PHONE,
    WIRED_HEADSET,
    EARPIECE,
    BLUETOOTH,
    STREAMING,
}
