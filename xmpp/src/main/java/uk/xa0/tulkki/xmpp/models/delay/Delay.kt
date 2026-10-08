package uk.xa0.tulkki.xmpp.models.delay

import uk.xa0.tulkki.annotation.XmlElement
import java.text.ParseException
import java.time.Instant
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.models.Extension
import uk.xa0.tulkki.xmpp.models.Timestamps

/**
 * XEP-0203 delayed delivery. The namespace moves onto the class, because Kotlin cannot annotate a
 * package; the javac annotation processor never sees this source, so the (`delay`,
 * `urn:xmpp:delay`) pair comes from the `@XmlElement` annotation.
 */
@XmlElement(namespace = Namespace.DELAY)
class Delay : Extension(Delay::class.java) {

    fun getStamp(): Instant? {
        val stamp = getAttribute("stamp")
        if (stamp.isNullOrEmpty()) {
            return null
        }
        return try {
            Instant.ofEpochMilli(Timestamps.parse(stamp))
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: ParseException) {
            null
        }
    }
}
