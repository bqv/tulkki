package uk.xa0.tulkki.libs

/**
 * Tulkki: the XMPP island's view of `uk.xa0.tulkki.data.model.ServiceDiscoveryResult`.
 *
 * Declared in the island and implemented by the model class in `:data`, which is a parser over the
 * island's own wire types (`Element`, `Namespace`, `xmpp.forms`, `Iq`) - which is why the model can
 * never move down to this module and only the interface does.
 *
 * **Why `:libs` (2026-10-08, lane `G`).** It was
 * `uk.xa0.tulkki.xmpp.refs.ServiceDiscoveryResultRef` - the island's name for the model while
 * `:xmpp` could not name `:data`. `:libs` is the one module in the order that every side may reach,
 * so the interface moves here and the ref file is its `git rm`; the move is **package-only** and
 * every namer changes only its import line. **Only the interface moves**: the `:data` class and
 * everything behind it stay put, so nothing is widened.
 *
 * **Respell from Java (2026-10-08, lane `G`).** Five members plus the nested `IdentityRef`, every
 * one JDK or `:libs`. The nullabilities are the model's own, not a choice: `getVer()`,
 * `getFeatures()` and `getIdentities()` are non-null in `ServiceDiscoveryResult` (`:288`, `:290`,
 * `:292`), `getExtendedDiscoInformation` answers `String?` (`:311`), `hasIdentity` takes two
 * nullable parts (`:308`), and the nested `Identity.getName()` answers `String?` (`:387`). The two
 * variance spellings the Java wrote as `? extends` are Kotlin's declaration-site variance:
 * `List<IdentityRef>` emits `java.util.List<? extends IdentityRef>`, identical to the Java
 * descriptor.
 */
interface ServiceDiscoveryResultRef {

    interface IdentityRef {
        fun getName(): String?
    }

    fun getIdentities(): List<IdentityRef>

    fun getVer(): String

    fun hasIdentity(category: String?, type: String?): Boolean

    fun getFeatures(): List<String>

    fun getExtendedDiscoInformation(formType: String, name: String): String?
}
