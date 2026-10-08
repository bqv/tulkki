package uk.xa0.tulkki.xmpp

/**
 * Tulkki: a registration/gateway query's answer, where an error is an absent prompt.
 *
 * Ported from `OnGatewayResult.java`. Both parameters
 * are nullable, read off the behaviour and the call sites: `XmppConnectionService.fetchFromGateway`
 * calls `onGatewayResult(null, error)` and `EnterJidDialog` calls `onGatewayResult(prompt, null)`
 * and `onGatewayResult(null, null)`. The one Java implementor is a lambda in `EnterJidDialog`, and
 * the SAM shape is kept.
 */
interface OnGatewayResult {
    // if prompt is null, there was an error
    // errorText may or may not be set
    fun onGatewayResult(prompt: String?, errorText: String?)
}
