package uk.xa0.tulkki.crypto.axolotl

import android.content.ContentValues
import android.database.Cursor

class FingerprintStatus private constructor() : Comparable<FingerprintStatus> {

    private var trust: Trust = Trust.UNTRUSTED
    private var active: Boolean = false
    private var lastActivation: Long = DO_NOT_OVERWRITE

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false

        val that = o as FingerprintStatus

        return active == that.active && trust == that.trust
    }

    override fun hashCode(): Int {
        var result = trust.hashCode()
        result = 31 * result + (if (active) 1 else 0)
        return result
    }

    fun toContentValues(): ContentValues {
        val contentValues = ContentValues()
        contentValues.put(SQLiteAxolotlStore.TRUST, trust.toString())
        contentValues.put(SQLiteAxolotlStore.ACTIVE, if (active) 1 else 0)
        if (lastActivation != DO_NOT_OVERWRITE) {
            contentValues.put(SQLiteAxolotlStore.LAST_ACTIVATION, lastActivation)
        }
        return contentValues
    }

    fun isTrustedAndActive(): Boolean = active && isTrusted()

    fun isTrusted(): Boolean = trust == Trust.TRUSTED || isVerified()

    fun isUnverified(): Boolean = trust == Trust.TRUSTED

    fun isVerified(): Boolean = trust == Trust.VERIFIED || trust == Trust.VERIFIED_X509

    fun isCompromised(): Boolean = trust == Trust.COMPROMISED

    fun isActive(): Boolean = active

    fun toActive(): FingerprintStatus {
        val status = FingerprintStatus()
        status.trust = trust
        // Tulkki: port-11, upstream `055b57172d` - stamp the activation time only when the key
        // really was inactive. The condition used to test the freshly constructed `status` (always
        // inactive), which rewrote last_activation on every reactivation and silently reset the
        // auto-expiry clock.
        if (!this.active) {
            status.lastActivation = System.currentTimeMillis()
        }
        status.active = true
        return status
    }

    fun toInactive(): FingerprintStatus {
        val status = FingerprintStatus()
        status.trust = trust
        status.active = false
        return status
    }

    fun getTrust(): Trust = trust

    fun toVerified(): FingerprintStatus {
        val status = FingerprintStatus()
        status.active = active
        status.trust = Trust.VERIFIED
        return status
    }

    fun toUntrusted(): FingerprintStatus {
        val status = FingerprintStatus()
        status.active = active
        status.trust = Trust.UNTRUSTED
        return status
    }

    override fun compareTo(o: FingerprintStatus): Int {
        if (active == o.active) {
            if (lastActivation > o.lastActivation) {
                return -1
            } else if (lastActivation < o.lastActivation) {
                return 1
            } else {
                return 0
            }
        } else if (active) {
            return -1
        } else {
            return 1
        }
    }

    fun getLastActivation(): Long = lastActivation

    enum class Trust {
        COMPROMISED,
        UNDECIDED,
        UNTRUSTED,
        TRUSTED,
        VERIFIED,
        VERIFIED_X509
    }

    companion object {
        private const val DO_NOT_OVERWRITE = -1L

        @JvmStatic
        fun fromCursor(cursor: Cursor): FingerprintStatus {
            val status = FingerprintStatus()
            try {
                status.trust =
                    Trust.valueOf(
                        cursor.getString(cursor.getColumnIndex(SQLiteAxolotlStore.TRUST))
                    )
            } catch (e: IllegalArgumentException) {
                status.trust = Trust.UNTRUSTED
            }
            status.active =
                cursor.getInt(cursor.getColumnIndex(SQLiteAxolotlStore.ACTIVE)) > 0
            status.lastActivation =
                cursor.getLong(cursor.getColumnIndex(SQLiteAxolotlStore.LAST_ACTIVATION))
            return status
        }

        @JvmStatic
        fun createActiveUndecided(): FingerprintStatus {
            val status = FingerprintStatus()
            status.trust = Trust.UNDECIDED
            status.active = true
            status.lastActivation = System.currentTimeMillis()
            return status
        }

        @JvmStatic
        fun createActiveTrusted(): FingerprintStatus {
            val status = FingerprintStatus()
            status.trust = Trust.TRUSTED
            status.active = true
            status.lastActivation = System.currentTimeMillis()
            return status
        }

        @JvmStatic
        fun createActiveVerified(x509: Boolean): FingerprintStatus {
            val status = FingerprintStatus()
            status.trust = if (x509) Trust.VERIFIED_X509 else Trust.VERIFIED
            status.active = true
            return status
        }

        @JvmStatic
        fun createActive(trusted: Boolean?): FingerprintStatus =
            createActive(trusted != null && trusted)

        @JvmStatic
        fun createActive(trusted: Boolean): FingerprintStatus {
            val status = FingerprintStatus()
            status.trust = if (trusted) Trust.TRUSTED else Trust.UNTRUSTED
            status.active = true
            return status
        }

        @JvmStatic
        fun createInactiveVerified(): FingerprintStatus {
            val status = FingerprintStatus()
            status.trust = Trust.VERIFIED
            status.active = false
            return status
        }
    }
}
