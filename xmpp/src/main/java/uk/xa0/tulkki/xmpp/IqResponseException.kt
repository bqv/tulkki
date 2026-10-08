package uk.xa0.tulkki.xmpp

/**
 * Tulkki: an IQ that carried an error the connection could not answer.
 *
 * Ported from `IqResponseException.java`. It is the
 * batch's smallest leaf and its whole Java-visible surface is one constructor. The message stays
 * nullable because Java allowed `null` and the only caller's argument, `AbstractParser
 * .extractErrorMessage`, answers `null` when the error carries neither text nor a condition.
 */
class IqResponseException(message: String?) : Exception(message)
