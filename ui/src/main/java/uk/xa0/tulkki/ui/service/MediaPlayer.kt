package uk.xa0.tulkki.ui.service

class MediaPlayer : android.media.MediaPlayer() {

    private var streamType: Int = 0

    override fun setAudioStreamType(streamType: Int) {
        this.streamType = streamType
        super.setAudioStreamType(streamType)
    }

    fun getAudioStreamType(): Int {
        return streamType
    }
}
