package uk.xa0.tulkki.xmpp.jingle

import android.util.Log
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.Iterables
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo

/**
 * Tulkki: the two halves of the SDP-to-Jingle candidate mapping, out of `JingleRtpConnection`.
 *
 * `parseCandidates` was Java's `private static`: it reads only the `SessionDescription` it is handed,
 * and it moves untouched. `toIdentificationTags` was a private instance method whose only borrow
 * from the connection was the log line's bare JID, so it takes that `Jid` as its second parameter
 * now and the call site passes `id.account.getJid()`.
 *
 * The two are `@JvmStatic` members of an `internal object`, which is the shape this package already
 * uses for Java's statics (`IceServers.parse`, `Media.audioOnly`, `ContentAddition.of`): `internal`
 * is the narrowest Kotlin spelling a same-module Java caller can name, the class is public only in
 * bytecode, and `@JvmStatic` leaves the Java call sites reading `RtpCandidates.parseCandidates(...)`
 * with the descriptor Java's own static had.
 *
 * `parseCandidates` keeps Java's null-and-empty test on the `mid` attribute (`isNullOrEmpty` is the
 * Kotlin spelling of the `Strings.isNullOrEmpty` it used, and it smart-casts the same way), and
 * `toIdentificationTags` keeps the `size == 0` test and the log line's `"" + jid`.
 */
internal object RtpCandidates {

    @JvmStatic
    fun parseCandidates(
        answer: SessionDescription,
    ): ImmutableMultimap<String, IceUdpTransportInfo.Candidate> {
        val candidateBuilder = ImmutableMultimap.Builder<String, IceUdpTransportInfo.Candidate>()
        for (media in answer.media) {
            val mid = Iterables.getFirst(media.attributes.get("mid"), null)
            if (mid.isNullOrEmpty()) {
                continue
            }
            for (sdpCandidate in media.attributes.get("candidate")) {
                val candidate =
                    IceUdpTransportInfo.Candidate.fromSdpAttributeValue(sdpCandidate, null)
                if (candidate != null) {
                    candidateBuilder.put(mid, candidate)
                }
            }
        }
        return candidateBuilder.build()
    }

    @JvmStatic
    fun toIdentificationTags(rtpContentMap: RtpContentMap, logJid: Jid): List<String> {
        val originalGroup = rtpContentMap.group
        val identificationTags =
            if (originalGroup == null) {
                rtpContentMap.getNames()
            } else {
                originalGroup.getIdentificationTags()
            }
        if (identificationTags.size == 0) {
            Log.w(
                Config.LOGTAG,
                "" +
                    logJid.asBareJid() +
                    ": no identification tags found in initial offer. we won't be able to" +
                    " calculate mLineIndices",
            )
        }
        return identificationTags
    }
}
