package uk.xa0.tulkki.data.utils

import android.graphics.drawable.Drawable
import io.ipfs.cid.Cid

fun interface GetThumbnailForCid {
    fun getThumbnail(cid: Cid): Drawable?
}
