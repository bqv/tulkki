package uk.xa0.tulkki.data

/**
 * Tulkki: the seven drawable ids [FileBackend] draws its preview badges with.
 *
 * They are `:ui`'s: after the split `:data` owns no `res/` tree for them, so the definitions stay in the
 * module that draws them and arrive here as plain ints - the recorded shape is the caller supplies the
 * resource (the 3.8-r remaining hand-work commit cebe3e9388, section 2). `:ui`'s `UIHelper.previewIcons()` is
 * the one place the seven names are written, and `:app`'s `DataStaticsHost.newFileBackend` hands the value to
 * [FileBackend] at its single construction site, beside the service it already receives there.
 *
 * A plain int carrier on purpose: `:data` cannot build it (it cannot see the ids) and `:ui` can build it
 * without a `Context`, so the value exists before any service does. Flat accessors rather than a
 * `(boolean dark)` grouping, so each of the thirteen substitutions in [FileBackend] is 1:1 and "behaviour is
 * unchanged" is a reading of the diff.
 */
class PreviewIcons(
    private val audio: Int,
    private val playGifBlackIcon: Int,
    private val playGifWhiteIcon: Int,
    private val playVideoBlackIcon: Int,
    private val playVideoWhiteIcon: Int,
    private val openPdfBlackIcon: Int,
    private val openPdfWhiteIcon: Int,
) {

    fun audioFile(): Int = audio

    fun playGifBlack(): Int = playGifBlackIcon

    fun playGifWhite(): Int = playGifWhiteIcon

    fun playVideoBlack(): Int = playVideoBlackIcon

    fun playVideoWhite(): Int = playVideoWhiteIcon

    fun openPdfBlack(): Int = openPdfBlackIcon

    fun openPdfWhite(): Int = openPdfWhiteIcon
}
