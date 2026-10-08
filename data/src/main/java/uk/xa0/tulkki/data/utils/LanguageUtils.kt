package uk.xa0.tulkki.data.utils

import com.google.common.collect.ImmutableMap
import java.util.Locale

object LanguageUtils {

    private val LANGUAGE_MAP: Map<String, String> =
        ImmutableMap.builder<String, String>()
            .put("german", "de")
            .put("deutsch", "de")
            .put("english", "en")
            .put("russian", "ru")
            .build()

    @JvmStatic
    fun convert(input: String?): String? {
        if (input == null) {
            return null
        }
        val out = LANGUAGE_MAP[input.lowercase(Locale.US)]
        return out ?: input
    }
}
