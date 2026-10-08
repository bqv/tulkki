package uk.xa0.tulkki.xmpp.services

import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy

/** Tulkki: the two transcoder strategies the story path picks between. */
interface TranscoderStrategiesPort {

    fun video720p(): DefaultVideoStrategy

    fun video360p(): DefaultVideoStrategy

    fun audioHq(): DefaultAudioStrategy

    fun audioMq(): DefaultAudioStrategy
}
