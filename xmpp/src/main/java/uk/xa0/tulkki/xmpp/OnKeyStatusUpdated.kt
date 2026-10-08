package uk.xa0.tulkki.xmpp

import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort

/**
 * Tulkki: OMEMO key-fetch progress, reported in the island's own enum.
 *
 * Ported from `OnKeyStatusUpdated.java`.
 *
 * **`report` is nullable.** The port's first spelling asserted it was non-null because
 * `XmppConnectionService.keyStatusUpdated` always forwarded the enum it received — but
 * `AxolotlService.registerDevices` reports a changed device list to its listeners with a literal
 * `null`, and the pre-port Java fan-out forwarded that too. Annotated, not narrowed, exactly as
 * master's `@Nullable` on the Java did before this conversion: the crash that assumption caused is
 * in `UiUpdateDispatch.kt`, which now takes `FetchStatus?` for the same reason.
 */
interface OnKeyStatusUpdated {
    fun onKeyStatusUpdated(report: OmemoSessionPort.FetchStatus?)
}
