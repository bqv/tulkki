package uk.xa0.tulkki.ui.util

import android.os.Parcel
import android.os.Parcelable

class ScrollState : Parcelable {

    @JvmField
    val position: Int

    @JvmField
    val offset: Int

    private constructor(parcel: Parcel) {
        position = parcel.readInt()
        offset = parcel.readInt()
    }

    constructor(position: Int, offset: Int) {
        this.position = position
        this.offset = offset
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(position)
        dest.writeInt(offset)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ScrollState> = object : Parcelable.Creator<ScrollState> {
            override fun createFromParcel(parcel: Parcel): ScrollState = ScrollState(parcel)

            override fun newArray(size: Int): Array<ScrollState?> = arrayOfNulls(size)
        }
    }
}
