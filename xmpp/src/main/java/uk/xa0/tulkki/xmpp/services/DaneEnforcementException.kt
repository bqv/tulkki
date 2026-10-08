package uk.xa0.tulkki.xmpp.services

import java.security.cert.CertificateException

/** Tulkki: the DANE refusal, a nine-line `CertificateException`. Converted from the Java. */
class DaneEnforcementException(message: String?) : CertificateException(message)
