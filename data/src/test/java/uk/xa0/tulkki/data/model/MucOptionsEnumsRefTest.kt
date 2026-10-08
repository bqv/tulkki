package uk.xa0.tulkki.data.model

import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef

/**
 * Tulkki: 3.7 C5-E2. `MucOptionsRef` carries three copies of model enum data, and each one can
 * drift silently on a green build:
 *
 * - `ErrorRef` and `AffiliationRef` are islands' own enums whose constants are mapped by name
 *   (`valueOf(error.name())`, `Affiliation.valueOf(...)`) - a constant added on one side only is a
 *   runtime `IllegalArgumentException` on a path only a real room reaches.
 * - `RoleRef.toString()` is a **copy** of the model's own (`name().toLowerCase()`) and the island
 *   only ever stringifies it into `IqGenerator.changeRole`'s request, so an edit on one side only is
 *   a wire-behaviour change with no other symptom.
 *
 * So this pins all three, and it is deliberately a pure JVM test: neither enum's static initialiser
 * touches an Android type (`Affiliation`'s `resId` field is not copied to the island, and `RoleRef`
 * copies only the name).
 */
class MucOptionsEnumsRefTest {

    @Test
    fun everyModelErrorHasARefConstant() {
        for (error in MucOptions.Error.values()) {
            val ref = MucOptionsRef.ErrorRef.valueOf(error.name)
            Assert.assertEquals(error.name, ref.name)
        }
        Assert.assertEquals(
            MucOptions.Error.values().size, MucOptionsRef.ErrorRef.values().size)
    }

    @Test
    fun everyRefErrorHasAModelConstant() {
        for (ref in MucOptionsRef.ErrorRef.values()) {
            Assert.assertEquals(ref.name, MucOptions.Error.valueOf(ref.name).name)
        }
    }

    /**
     * The mapping the model's `changeAffiliation(Jid, AffiliationRef)` and the island's `error()`
     * both use, in both directions, plus the constant count - which is what makes `valueOf` total.
     */
    @Test
    fun everyModelAffiliationHasARefConstantAndBack() {
        for (affiliation in MucOptions.Affiliation.values()) {
            val ref = MucOptionsRef.AffiliationRef.valueOf(affiliation.name)
            Assert.assertEquals(affiliation.name, ref.name)
            Assert.assertEquals(
                ref.name, MucOptions.Affiliation.valueOf(ref.name).name)
        }
        Assert.assertEquals(
            MucOptions.Affiliation.values().size,
            MucOptionsRef.AffiliationRef.values().size)
    }

    /** The copied `toString()` must answer what the model's does, for every role. */
    @Test
    fun theRoleStringIsTheModels() {
        for (role in MucOptions.Role.values()) {
            val ref = MucOptionsRef.RoleRef.valueOf(role.name)
            Assert.assertEquals(
                "role string drifted for " + role.name,
                role.toString(),
                ref.toString())
        }
        Assert.assertEquals(MucOptions.Role.values().size, MucOptionsRef.RoleRef.values().size)
    }
}
