package uk.xa0.tulkki.data.utils

import java.util.regex.Pattern

/**
 * The `geo:` URI shape, moved down from `uk.xa0.tulkki.ui.utils.GeoHelper` to kill the forbidden
 * `:data` -> `:ui` edge (D9). `GeoHelper.GEO_URI` still exists and now points at this field, so the
 * `:ui` and `:xmpp` readers that match a body against it did not move.
 *
 * The pattern is a pure regular expression - no resource, no `Context` - which is all
 * `Message.isGeoUri()` needs of it. `GeoHelper` keeps everything else it holds (`parseGeoPoint`, the
 * intents, the map previews), because all of that needs a `Context`, an `Activity` or an `Intent` and
 * belongs to the later half of the split.
 */
object GeoUris {

    @JvmField
    val GEO_URI: Pattern =
        Pattern.compile(
            "geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)(?:,-?\\d+(?:\\.\\d+)?)?(?:;crs=[\\w-]+)?(?:;u=\\d+(?:\\.\\d+)?)?(?:;[\\w-]+=(?:[\\w-_.!~*'()]|%[\\da-f][\\da-f])+)*(\\?z=\\d+)?",
            Pattern.CASE_INSENSITIVE,
        )
}
