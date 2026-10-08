package uk.xa0.tulkki.xmpp.utils

import android.app.Activity
import android.content.Intent
import android.view.MenuItem
import android.widget.Toast
import com.google.common.primitives.Bytes
import com.google.common.primitives.Longs
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.UUID
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: 3.7 C5-D - this island class names {@code AccountRef}, not the model.
 *
 * <p>The four account-returning statics were the whole of its {@code :xmpp -> :data} edge. Every
 * member they read - {@code isOptionSet}, {@code isOnlineAndConnected}, {@code getUuid},
 * {@code getJid}, {@code isEnabled}, {@code getDisplayName} - is already on the ref, so nothing was
 * added to either side and {@code :data} paid nothing.
 *
 * <p><strong>C5-E1 made the two "first" lookups element-type-preserving</strong>
 * ({@code <T extends AccountRef> T getFirst(List<T>)}), which is what deletes the three
 * {@code (Account)} casts the old {@code List<? extends AccountRef>} parameter forced: the argument
 * is now the holder's own model list, so {@code T} infers to {@code Account} and the caller's cast
 * is not merely total but unnecessary - the return type *is* the element type, a compiler-visible
 * fact rather than a grep. The same slice removed the five {@code (XmppConnectionService)}
 * overloads, because the service no longer has a {@code getAccounts()}: every caller now passes the
 * list it already holds.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`MANAGE_ACCOUNT_ACTIVITY` is a `@JvmField val` initialised where Java's static block did**,
 *    so `AccountUtils.MANAGE_ACCOUNT_ACTIVITY` is still a field read from Java and Kotlin.
 * 2. **The `List<? extends AccountRef>` parameters are Kotlin `List<AccountRef>`**, whose own
 *    declaration is covariant, so the JVM generic signature stays `List<? extends AccountRef>` and
 *    `List<Account>` still fits.
 * 3. **The four nullable lookups answer `AccountRef?`/`T?`**: Java returned `null` from
 *    [getFirstNotDisabled], [getFirst], [getPendingAccount] and [getFirstEnabled], and each call site
 *    tests for it.
 * 4. **`hasEnabledAccounts` still answers `false` on both paths.** Java's loop returns `false` for a
 *    disabled account and the method then returns `false` unconditionally, so it is constant-`false`;
 *    that is a real defect in the Java and it is preserved here rather than fixed, as this port's
 *    rule requires. It is reported, not repaired.
 * 5. **The `&=`, `|=` byte arithmetic of [createUuid4] is written with explicit `toInt()`/`toByte()`**
 *    because Kotlin has no compound assignment for a `ByteArray` element; the masks and the wrap are
 *    Java's.
 */
class AccountUtils {

    companion object {

        @JvmField
        val MANAGE_ACCOUNT_ACTIVITY: Class<*>? = getManageAccountActivityClass()

        @JvmStatic
        fun hasEnabledAccounts(accounts: List<AccountRef>): Boolean {
            for (account in accounts) {
                if (account.isOptionSet(AccountRef.OPTION_DISABLED)) {
                    return false
                }
            }
            return false
        }

        @JvmStatic
        fun publicDeviceId(account: AccountRef, installationId: Long): String {
            val uuid: UUID =
                try {
                    UUID.fromString(account.getUuid())
                } catch (e: IllegalArgumentException) {
                    return account.getUuid()
                        ?: throw NullPointerException("account has no uuid")
                }
            return createUuid4(uuid.mostSignificantBits, installationId).toString()
        }

        @JvmStatic
        fun createUuid4(mostSigBits: Long, leastSigBits: Long): UUID {
            val bytes = Bytes.concat(Longs.toByteArray(mostSigBits), Longs.toByteArray(leastSigBits))
            bytes[6] = (bytes[6].toInt() and 0x0f).toByte() /* clear version        */
            bytes[6] = (bytes[6].toInt() or 0x40).toByte() /* set to version 4     */
            bytes[8] = (bytes[8].toInt() and 0x3f).toByte() /* clear variant        */
            bytes[8] = (bytes[8].toInt() or 0x80).toByte() /* set to IETF variant  */
            val byteBuffer = ByteBuffer.wrap(bytes)
            return UUID(byteBuffer.getLong(), byteBuffer.getLong())
        }

        @JvmStatic
        fun getEnabledAccounts(source: List<AccountRef>): List<String> {
            val accounts = ArrayList<String>()
            for (account in source) {
                if (account.isEnabled()) {
                    accounts.add(account.getJid().asBareJid().toString())
                }
            }
            return accounts
        }

        /**
         * **Distinctly named** because the two `getFirstEnabled` overloads this class used to carry -
         * one taking the service, one taking a list - erase to the same signature now that both take a
         * list. They are genuinely different questions and both are kept: this one is "the first account
         * the owner has not disabled" (the option bit), the one below is "the first account that is up,
         * or failing that the first enabled one" (the connection state). Merging them would change a
         * behaviour, so the name moves instead - the same device `AccountRef.replaceBookmarks` and
         * `AvatarPort.getAccountAvatar` use for the same class of fact.
         */
        @JvmStatic
        fun getFirstNotDisabled(accounts: List<AccountRef>): AccountRef? {
            for (account in accounts) {
                if (!account.isOptionSet(AccountRef.OPTION_DISABLED)) {
                    return account
                }
            }
            return null
        }

        @JvmStatic
        fun <T : AccountRef> getFirst(accounts: List<T>): T? {
            for (account in accounts) {
                return account
            }
            return null
        }

        @JvmStatic
        fun getPendingAccount(accounts: List<AccountRef>): AccountRef? {
            var pending: AccountRef? = null
            for (account in accounts) {
                if (!account.isOptionSet(AccountRef.OPTION_LOGGED_IN_SUCCESSFULLY)) {
                    pending = account
                } else {
                    return null
                }
            }
            return pending
        }

        @JvmStatic
        fun launchManageAccounts(activity: Activity) {
            if (MANAGE_ACCOUNT_ACTIVITY != null) {
                activity.startActivity(Intent(activity, MANAGE_ACCOUNT_ACTIVITY))
            } else {
                Toast.makeText(activity, R.string.feature_not_implemented, Toast.LENGTH_SHORT).show()
            }
        }

        private fun getManageAccountActivityClass(): Class<*>? =
            try {
                Class.forName("uk.xa0.tulkki.ui.ManageAccountActivity")
            } catch (e: ClassNotFoundException) {
                null
            }

        /**
         * Hide or show the two "manage accounts" menu items for what the plugin host supports.
         *
         * <p>The items are handed in rather than looked up here.  `action_accounts` and
         * `action_account` are declared by `@+id/` inside `:ui`'s menus, so after the split this island
         * cannot name them, and `move-res.py` cannot cut an id out of its carrier layout (the 3.8-r
         * renamed hand-work commit 945ecf86b8, section 3).  Every caller already holds the `Menu` it
         * inflated, so the lookup is the caller's.
         */
        @JvmStatic
        fun showHideMenuItems(manageAccounts: MenuItem?, manageAccount: MenuItem?) {
            if (manageAccount != null) {
                manageAccount.setVisible(MANAGE_ACCOUNT_ACTIVITY == null)
            }
            if (manageAccounts != null) {
                manageAccounts.setVisible(MANAGE_ACCOUNT_ACTIVITY != null)
            }
        }

        /**
         * The element type is preserved: a caller passing its own {@code List<Account>} gets an
         * {@code Account} back, so the three cast sites this slice deletes needed no cast at all.
         * An island caller passing {@code List<? extends AccountRef>} infers the wildcard's capture,
         * which is assignable to {@code AccountRef}.
         */
        @JvmStatic
        fun <T : AccountRef> getFirstEnabled(accounts: List<T>): T? {
            for (account in accounts) {
                if (account.isOnlineAndConnected()) {
                    return account
                }
            }
            for (account in accounts) {
                if (account.isEnabled()) {
                    return account
                }
            }
            return null
        }
    }
}
