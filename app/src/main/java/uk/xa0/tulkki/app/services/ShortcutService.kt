package uk.xa0.tulkki.app.services

import android.annotation.TargetApi
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Maps
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.FrequentContact
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.StartConversationActivity
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.ReplacingSerialSingleThreadExecutor

/**
 * Publishes the most frequent conversations as launcher shortcuts.
 *
 * <p>`ID_SEPARATOR` is a `const val` in the companion because Java reads the field itself:
 * `UiAppHost` returns `ShortcutService.ID_SEPARATOR` from its own port. It is the Java's
 * `public static final char`, still a `char` and still a JVM compile-time constant.
 *
 * <p>**All seven port members take `override`.** `uk.xa0.tulkki.xmpp.services.ShortcutPort` declares them,
 * so `refresh`, `report`, the three `getShortcutInfo` overloads and `createShortcut` are the port
 * surface; only the private helpers below are this class's own. `refresh()` forwards to
 * `refresh(false)`, as the Java's did.
 *
 * <p>The four private statics (`setConversation`, `contactsChanged`, `contactExists`, the two
 * `getShortcutId` overloads) are private companion members - Kotlin has no static method outside a
 * companion, and `private` on a companion member reaches the class body that calls them. The three
 * `getShortcutIntent` overloads stay instance methods, as the Java's were.
 *
 * <p>`Maps.uniqueIndex`'s key function is the Java method reference `Account::getUuid` written as a
 * trailing lambda, which is the same Guava `Function` through SAM conversion. Java's
 * `conversation == null ? null : conversation.getUuid()` is `conversation?.getUuid()`, and the
 * `String conversation` parameter is `String?` because the Java passed null and tested for it.
 *
 * <p>Name-string audit: 0 hits for `ShortcutService` in the manifest, `res/xml`, `res/layout*`,
 * `preferences_*.xml` and the ProGuard rules. The one bilingual string this file carries,
 * `eu.siacs.conversations.category.SHARE_TARGET`, is Android's own share-target category and is on
 * `tools/verify-allowlist`; it must not be renamed.
 */
class ShortcutService(private val xmppConnectionService: XmppConnectionService) :
        uk.xa0.tulkki.xmpp.services.ShortcutPort {

    private val replacingSerialSingleThreadExecutor =
            ReplacingSerialSingleThreadExecutor(ShortcutService::class.java.getSimpleName())

    override fun refresh() {
        refresh(false)
    }

    override fun refresh(forceUpdate: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val r = Runnable { refreshImpl(forceUpdate) }
            replacingSerialSingleThreadExecutor.execute(r)
        }
    }

    // Tulkki: 3.7 pair 9, part 15 - the four contact methods below are ref-shaped now, because
    // `uk.xa0.tulkki.xmpp.services.ShortcutPort` is declared in the island and the island no longer names
    // the model type. This class is `:app`, so each of them casts back once and every body is
    // otherwise byte-identical; the `:ui` callers pass a `Contact`, which implements the ref.

    @TargetApi(25)
    override fun report(contactRef: ContactRef) {
        val contact = contactRef as Contact
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val shortcutManager =
                    xmppConnectionService.getSystemService(ShortcutManager::class.java)
            shortcutManager.reportShortcutUsed(getShortcutId(contact))
        }
    }

    @TargetApi(25)
    private fun refreshImpl(forceUpdate: Boolean) {
        // Tulkki: C5-E5 - the field is the island's ref now, and `getFrequentContacts` is first-party
        // surface; `get()` answers the same instance.
        val frequentContacts = uk.xa0.tulkki.data.DatabaseBackend.get().getFrequentContacts(30)
        // port-5: the index is keyed by account uuid and `AbstractEntity.getUuid()` is nullable; an
        // account with no uuid cannot be found by one, so it is not indexed.
        val accountsBuilder = ImmutableMap.Builder<String, Account>()
        for (entry in AccountRegistry.get().getAccounts()) {
            val uuid = entry.getUuid() ?: continue
            accountsBuilder.put(uuid, entry)
        }
        val accounts = accountsBuilder.build()
        val contactBuilder = ImmutableMap.Builder<FrequentContact, Contact>()
        for (frequentContact in frequentContacts) {
            val account = accounts.get(frequentContact.account)
            if (account != null) {
                val contact = account.getRoster().getContact(frequentContact.contact)
                contactBuilder.put(frequentContact, contact)
            }
        }
        val contacts = contactBuilder.build()
        val current = ShortcutManagerCompat.getDynamicShortcuts(xmppConnectionService)
        val needsUpdate = forceUpdate || contactsChanged(contacts.values, current)
        if (!needsUpdate) {
            Log.d(Config.LOGTAG, "skipping shortcut update")
            return
        }
        val newDynamicShortcuts = ImmutableList.Builder<ShortcutInfoCompat>()
        for (entry in contacts.entries) {
            val contact = entry.value
            val conversation = entry.key.conversation
            val shortcut = getShortcutInfo(contact, conversation)
            newDynamicShortcuts.add(shortcut)
        }
        if (ShortcutManagerCompat.setDynamicShortcuts(
                xmppConnectionService, newDynamicShortcuts.build())) {
            Log.d(Config.LOGTAG, "updated dynamic shortcuts")
        } else {
            Log.d(Config.LOGTAG, "unable to update dynamic shortcuts")
        }
    }

    override fun getShortcutInfo(contact: ContactRef): ShortcutInfoCompat {
        val conversation = xmppConnectionService.find(contact)
        val uuid = conversation?.getUuid()
        return getShortcutInfo(contact, uuid)
    }

    override fun getShortcutInfo(contactRef: ContactRef, conversation: String?): ShortcutInfoCompat {
        val contact = contactRef as Contact
        val builder =
                ShortcutInfoCompat.Builder(xmppConnectionService, getShortcutId(contact))
                        .setShortLabel(contact.getDisplayName())
                        .setIntent(getShortcutIntent(contact))
                        .setIsConversation()
        builder.setIcon(
                IconCompat.createWithBitmap(
                        xmppConnectionService.getAvatarService().getRoundedShortcut(contact)))
        if (conversation != null) {
            setConversation(builder, conversation)
        }
        return builder.build()
    }

    /**
     * Tulkki: 3.7 C5-E2 - the port member is ref-shaped so `XmppConnectionService` can stop naming the
     * model type. The body is the model method and casts once at the top; the private helpers below
     * stay model-typed, because they read `getConversation()`/`getAccount()` and nothing in them is
     * island-shaped. Replaced, not twinned: a twin would leave the port method unimplemented.
     */
    override fun getShortcutInfo(mucOptionsRef: MucOptionsRef): ShortcutInfoCompat {
        val mucOptions = mucOptionsRef as MucOptions
        val builder =
                ShortcutInfoCompat.Builder(xmppConnectionService, getShortcutId(mucOptions))
                        .setShortLabel(mucOptions.getConversation().getName())
                        .setIntent(getShortcutIntent(mucOptions))
                        .setIsConversation()
        builder.setIcon(
                IconCompat.createWithBitmap(
                        xmppConnectionService.getAvatarService().getRoundedShortcut(mucOptions)))
        // port-5: `Conversational.getUuid()` is nullable; a shortcut with no conversation uuid has no
        // conversation to point at, so the extra is left off rather than set to a stand-in.
        val conversationUuid = mucOptions.getConversation().getUuid()
        if (conversationUuid != null) {
            setConversation(builder, conversationUuid)
        }
        return builder.build()
    }

    private fun getShortcutIntent(mucOptions: MucOptions): Intent {
        val account = mucOptions.getAccount()
        return getShortcutIntent(
                account,
                Uri.parse(
                        String.format(
                                "xmpp:%s?join",
                                mucOptions.getConversation().getJid()!!.asBareJid().toString())))
    }

    private fun getShortcutIntent(contact: Contact): Intent {
        return getShortcutIntent(
                contact.getAccount(), Uri.parse("xmpp:" + contact.getJid().asBareJid().toString()))
    }

    private fun getShortcutIntent(account: Account, uri: Uri): Intent {
        val intent = Intent(xmppConnectionService, StartConversationActivity::class.java)
        intent.setAction(Intent.ACTION_VIEW)
        intent.setData(uri)
        intent.putExtra("account", account.getJid().asBareJid().toString())
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return intent
    }

    override fun createShortcut(contact: ContactRef, legacy: Boolean): Intent {
        val intent: Intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !legacy) {
            val shortcut = getShortcutInfo(contact)
            intent =
                    ShortcutManagerCompat.createShortcutResultIntent(
                            xmppConnectionService, shortcut)
        } else {
            intent = createShortcutResultIntent(contact)
        }
        return intent
    }

    private fun createShortcutResultIntent(contactRef: ContactRef): Intent {
        val contact = contactRef as Contact
        val avatarService: uk.xa0.tulkki.xmpp.services.AvatarPort =
                xmppConnectionService.getAvatarService()
        val icon: Bitmap = avatarService.getRoundedShortcutWithIcon(contact)
        val intent = Intent()
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, contact.getDisplayName())
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon)
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, getShortcutIntent(contact))
        return intent
    }

    companion object {

        const val ID_SEPARATOR = '#'

        private fun setConversation(builder: ShortcutInfoCompat.Builder, conversation: String) {
            builder.setCategories(ImmutableSet.of("eu.siacs.conversations.category.SHARE_TARGET"))
            val extras = PersistableBundle()
            extras.putString(ConversationListActivity.EXTRA_CONVERSATION, conversation)
            builder.setExtras(extras)
        }

        private fun contactsChanged(
                needles: Collection<Contact>,
                haystack: List<ShortcutInfoCompat>
        ): Boolean {
            for (needle in needles) {
                if (!contactExists(needle, haystack)) {
                    return true
                }
            }
            return needles.size != haystack.size
        }

        @TargetApi(25)
        private fun contactExists(
                needle: Contact,
                haystack: List<ShortcutInfoCompat>
        ): Boolean {
            for (shortcutInfo in haystack) {
                val label = shortcutInfo.getShortLabel()
                if (getShortcutId(needle) == shortcutInfo.getId() &&
                        needle.getDisplayName() == label.toString()) {
                    return true
                }
            }
            return false
        }

        private fun getShortcutId(contact: Contact): String {
            return Joiner.on(ID_SEPARATOR)
                    .join(
                            contact.getAccount().getJid().asBareJid().toString(),
                            contact.getJid().asBareJid().toString())
        }

        private fun getShortcutId(mucOptions: MucOptions): String {
            val account = mucOptions.getAccount()
            val jid = mucOptions.getConversation().getJid()!!
            return Joiner.on(ID_SEPARATOR)
                    .join(account.getJid().asBareJid().toString(), jid.asBareJid().toString())
        }
    }

    // 3.7 pair 2: the FrequentContact value type that used to be nested here moved down to
    // uk.xa0.tulkki.data.model.FrequentContact, because DatabaseBackend.getFrequentContacts returns it and
    // :data may not name this module. It is imported above.
}
