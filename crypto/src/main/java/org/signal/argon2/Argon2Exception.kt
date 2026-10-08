package org.signal.argon2

import java.util.Locale

class Argon2Exception internal constructor(message: String) : Exception(message) {

    internal constructor(nativeErrorValue: Int, nativeErrorMessage: String) :
        this(String.format(Locale.US, "Argon failed %d: %s", nativeErrorValue, nativeErrorMessage))
}
