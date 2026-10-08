package uk.xa0.tulkki.ui.utils

import uk.xa0.tulkki.data.utils.DisplayNames

/**
 * The `:ui` entry point for XEP-0392 nickname colours. The implementation moved down to
 * [DisplayNames.rgbFromNick] in `:data` to kill the forbidden `:data` -> `:ui` edge (D9); this
 * delegates, so the `:ui` and `:app` callers that tint a tag or a conference avatar did not move.
 *
 * The class is kept rather than deleted - it is what those five call sites name - even though it is
 * now a one-line forwarder; the duplication is the accepted price of not touching them.
 */
object XEP0392Helper {

    @JvmStatic
    fun rgbFromNick(name: String?): Int = DisplayNames.rgbFromNick(name)
}
