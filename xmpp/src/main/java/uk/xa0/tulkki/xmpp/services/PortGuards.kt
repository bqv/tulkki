package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the composition root's read guards, lifted out of `XmppConnectionService`
 *.
 *
 * The chunk's three **write** points (`installDataStatics`, `installPortsFactory`,
 * `installTrustPort`) and the three `static volatile` slots they write stay on the service: chunk
 * `C74`'s row owns those fields, `TulkkiApplication.onCreate` names the installs as
 * `XmppConnectionService.install…`, and `PortInstallGuardTest` recognises a holder by the slot and
 * the installer sitting in the same file - so moving them would take chunk `C74`'s state, a call
 * site outside the island and the guard test's own table with it. What moves is the **read** half:
 * the read-into-a-local-then-test idiom and the failure that names the install. Every guard here
 * therefore takes its slot **by value**, and the service's own method keeps the single read.
 *
 * The statements are the Java's byte for byte, because `PortInstallGuardTest` scans them for the
 * install token they name and refuses a message that names an install no row reviewed. Nothing here
 * returns null: a missing install is a build fault, never a runtime state.
 */
object PortGuards {

    /** The `:data` statics, or a loud failure naming the install point. */
    @JvmStatic
    fun dataStatics(
        statics: XmppConnectionService.DataStatics?,
    ): XmppConnectionService.DataStatics =
        statics
            ?: throw IllegalStateException(
                "Tulkki's data statics were never installed: the composition root must call" +
                    " XmppConnectionService.installDataStatics at process start, and it has" +
                    " not",
            )

    /** The trust port for the callers that have no service to ask. */
    @JvmStatic
    fun trustPort(port: TrustPort?): TrustPort =
        port
            ?: throw IllegalStateException(
                "Tulkki's trust port was never installed: the composition root must call" +
                    " XmppConnectionService.installTrustPort at process start, and it has" +
                    " not",
            )

    /** The OMEMO settings handle, installed from the same ports factory as the send gate. */
    @JvmStatic
    fun omemoSettings(
        port: OmemoSettingsPort?,
    ): OmemoSettingsPort =
        port
            ?: throw IllegalStateException(
                "Tulkki's OMEMO settings handle was never installed: the composition root must" +
                    " call XmppConnectionService.installPortsFactory at process start," +
                    " and it has not",
            )

    /** The choke point that keeps an untranslated stanza from being sent. */
    @JvmStatic
    fun sendGate(gate: SendGate?): SendGate =
        gate
            ?: throw IllegalStateException(
                "Tulkki's send gate was never installed: the composition root must call" +
                    " XmppConnectionService.installPortsFactory at process start, and it" +
                    " has not",
            )
}
