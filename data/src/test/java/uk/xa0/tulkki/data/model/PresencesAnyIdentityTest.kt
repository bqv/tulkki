package uk.xa0.tulkki.data.model

import org.junit.Assert
import org.junit.Test

/**
 * Tulkki: the null `type` that crashed the Enter-JID dialog.
 *
 * `PresencesRef.anyIdentity(String, String)` is a bare Java signature and
 * `EnterJidDialog.populateGateways` - which every `onCreateDialog` of that dialog runs for a roster
 * contact with a presence - calls it as `anyIdentity("gateway", null)` twice. A null `type` means
 * "any type" and the model's own `ServiceDiscoveryResult.hasIdentity` has always said so; the
 * port's first cut declared both parameters non-null, so Kotlin's parameter check threw before the
 * body could return `false` for an empty presence set.
 *
 * The cell is deliberately a pure JVM test: an empty `Presences` needs no Android type, and the
 * empty set takes the body's first branch, so what is pinned is precisely the parameter check the
 * crash was in.
 */
class PresencesAnyIdentityTest {

    @Test
    fun aNullTypeIsAQuestionTheEmptySetCanAnswer() {
        val presences = Presences()

        Assert.assertFalse(presences.anyIdentity("gateway", null))
        Assert.assertFalse(presences.anyIdentity(null, null))
    }
}
