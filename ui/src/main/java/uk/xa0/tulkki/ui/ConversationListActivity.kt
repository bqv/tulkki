/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package uk.xa0.tulkki.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Fragment
import android.app.FragmentManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import android.util.Pair
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.collect.ImmutableList
import io.michaelrocks.libphonenumber.android.NumberParseException
import java.util.TreeMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Collectors
import org.openintents.openpgp.util.OpenPgpApi
import uk.xa0.tulkki.crypto.OmemoSetting
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.ListItem.Tag
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.ui.chrome.ChromeMenuItem
import uk.xa0.tulkki.ui.compose.setTulkkiContent
import uk.xa0.tulkki.ui.conversationlist.ChromeIconAction
import uk.xa0.tulkki.ui.conversationlist.ChromeSearchAction
import uk.xa0.tulkki.ui.conversationlist.ConversationDrawerState
import uk.xa0.tulkki.ui.conversationlist.ConversationLeading
import uk.xa0.tulkki.ui.conversationlist.ConversationListChrome
import uk.xa0.tulkki.ui.conversationlist.ConversationListChromeSession
import uk.xa0.tulkki.ui.conversationlist.ConversationListChromeState
import uk.xa0.tulkki.ui.conversationlist.ConversationListPanes
import uk.xa0.tulkki.ui.conversationlist.ConversationNavBadges
import uk.xa0.tulkki.ui.conversationlist.ConversationNavTab
import uk.xa0.tulkki.ui.conversationlist.DrawerEntry
import uk.xa0.tulkki.ui.conversationlist.DrawerProfile
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.interfaces.OnBackendConnected
import uk.xa0.tulkki.ui.interfaces.OnConversationArchived
import uk.xa0.tulkki.ui.interfaces.OnConversationListItemUpdated
import uk.xa0.tulkki.ui.interfaces.OnConversationRead
import uk.xa0.tulkki.ui.interfaces.OnConversationSelected
import uk.xa0.tulkki.ui.pinnedmessage.PinnedMessageRepository
import uk.xa0.tulkki.ui.util.ActivityResult
import uk.xa0.tulkki.ui.util.AvatarWorkerTask
import uk.xa0.tulkki.ui.util.ConversationMenuConfigurator
import uk.xa0.tulkki.ui.util.MenuDoubleTabUtil
import uk.xa0.tulkki.ui.util.PendingItem
import uk.xa0.tulkki.ui.util.SoftKeyboardUtils
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.ui.widget.AvatarView
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnUpdateBlocklist
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.XmppUri
import uk.xa0.tulkki.xml.Namespace

/**
 * The conversation list and the drawer that filters it.
 *
 * Ported from Java by the `ui44e` lane, and its two panes, its toolbar, its drawer and its bottom
 * bar are Compose now: `activity_conversation_list.xml`, its `layout-w945dp` twin and the four menus
 * they inflated (`activity_conversation_list.xml`, `fragment_conversation_list.xml`,
 * `mam_pref_fix.xml`, `bottom_navigation_menu_chat.xml`) are deleted, and
 * [uk.xa0.tulkki.ui.conversationlist.ConversationListChrome] draws what they declared. Every
 * question this class answered is still answered here: which drawer id filters, which id opens a
 * screen, which tag is selected, which profile is active, which fragments are where.
 *
 * **What the deleted MaterialDrawer was, and what is here instead.** The library's
 * `MaterialDrawerSliderView` and `AccountHeaderView` carried an item adapter this class mutated on
 * every read: badges were pushed with `updateBadge`, tag items were added and removed, and the
 * header's profiles were added, updated and pruned. None of that survives, because none of it was
 * content - it was the library's own bookkeeping. The content is rebuilt as plain values on every
 * read ([ConversationDrawerState]), so a read is now a state assignment rather than a sequence of
 * adapter mutations, and the two things the adapter's identity carried are explicit: a drawer id,
 * and whether a tag is in [selectedTag].
 *
 * **The one thing that is deferred.** A `ComposeView` composes when its window attaches, which is
 * after `onCreate` and after `onStart`. The fragment containers `main_fragment` and
 * `secondary_fragment` are created by that composition, so nothing here may commit a transaction
 * before it: [attachPanes] is what runs the first commit, from the pane's own callback, and every
 * other fragment read is null-tolerant until then. [onBackendConnected] and [openConversation]
 * therefore park their work if it arrives early.
 *
 * The Java's unguarded dereferences of the `:data` model's nullable members are kept as
 * `?: throw NullPointerException()` (the convention `ui43g` set for `ConferenceDetailsActivity`),
 * so a call site that threw in Java still throws here rather than silently changing behaviour.
 */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
class ConversationListActivity :
    XmppActivity(),
    OnConversationSelected,
    OnConversationArchived,
    OnConversationListItemUpdated,
    OnConversationRead,
    uk.xa0.tulkki.xmpp.services.OnAccountUpdate,
    uk.xa0.tulkki.xmpp.services.OnConversationUpdate,
    uk.xa0.tulkki.xmpp.services.OnRosterUpdate,
    OnUpdateBlocklist,
    uk.xa0.tulkki.xmpp.services.OnShowErrorToast,
    uk.xa0.tulkki.xmpp.services.OnAffiliationChanged,
    uk.xa0.tulkki.xmpp.services.OnRoomDestroy {
    private val pendingViewIntent = PendingItem<Intent>()
    private val postponedActivityResult = PendingItem<ActivityResult>()
    private var mActivityPaused = true
    private val mRedirectInProcess = AtomicBoolean(false)
    private var refreshForNewCaps = false
    private var newCapsJids: MutableSet<Jid> = HashSet()
    private var mRequestCode = -1
    private var showLastSeen = false
    private var savedState: Bundle? = null
    private var selectedTag: HashSet<Tag> = HashSet()
    private var mainFilter = DRAWER_ALL_CHATS
    private var refreshAccounts = true

    /** The bar, the drawer and the frame, written by every read and read by the composition. */
    private val chrome = ConversationListChromeSession()

    /**
     * The avatar the bar draws, loaded by the tree's own `AvatarWorkerTask`, which writes into a
     * view. It is created once and handed to the bar through an `AndroidView`, so the loader and the
     * view it was given are the same pair the XML bar was given.
     */
    private val toolbarAvatarView: ImageView by lazy {
        AvatarView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    }

    /**
     * The "All accounts" pseudo-profile's image: the deleted `ProfileDrawerItem`'s `iconRes`, which
     * was `R.drawable.tulkki_logo`. It is resolved once rather than per read, because a fresh
     * `Drawable` every read would make [ConversationDrawerState] unequal to itself and recompose the
     * drawer for nothing.
     */
    private val allAccountsLogo by lazy { getDrawable(R.drawable.tulkki_logo) }

    /** Whether the composition's panes exist; nothing commits a transaction into them before they do. */
    private var panesReady = false

    /** A conversation that arrived before the panes did, replayed by [attachPanes]. */
    private var postponedConversation: Pair<Conversation, Bundle?>? = null

    /** A backend connection that arrived before the panes did, replayed by [attachPanes]. */
    private var postponedBackendConnected = false

    /** The tag rows the drawer is showing, by the id the row carries: `1000 + index`. */
    private var drawerTags: List<Tag> = ArrayList()

    /** The profile rows the drawer is showing, by the id the row carries: `100 + index`. */
    private var drawerProfiles: List<Account?> = ArrayList()

    /** The conversation the bar's title belongs to, so a tap can open its details. */
    private var titleConversation: Conversation? = null

    /**
     * The drawer's active profile, where the deleted `AccountHeaderView.activeProfile` was. `null`
     * is the `All accounts` pseudo-profile, which the header's own first-run rule chooses exactly
     * when there is more than one account.
     */
    private var activeAccount: Account? = null

    /** Whether [activeAccount] is the owner's choice or the header's first-run rule's. */
    private var activeAccountSettled = false

    /**
     * The last assembled bar state. It is a plain field rather than the observable itself because
     * three readers write different thirds of it - a read writes the badges and the two visibility
     * flags, `invalidateActionBarTitle` writes the title block, and `showNavigationBar` writes one
     * flag - and [applyChrome] is the one place the composition is told.
     */
    private var chromeState = ConversationListChromeState()

    /** Hands the assembled bar state to the composition. */
    private fun applyChrome() {
        chrome.update(chromeState)
    }

    private var pinnedMessageRepositoryBacking: PinnedMessageRepository? = null

    override fun refreshUiReal() {
        if (xmppConnectionService == null) {
            return
        }

        invalidateOptionsMenu()
        for (id in FRAGMENT_ID_NOTIFICATION_ORDER) {
            refreshFragment(id)
        }
        refreshForNewCaps = false
        newCapsJids.clear()
        val selectedAccount = selectedAccount()

        // Show badge for unread message in bottom nav
        val unreadCount = xmppConnectionService.unreadCount()

        // Show badge for new stories in bottom nav
        val lastRead = getPreferences().getLong("last_read_story_timestamp", 0)
        val hasNewStories = xmppConnectionService.getStories().stream().anyMatch { s -> s.getPublished() > lastRead }

        // Show badge for new posts in bottom nav
        val lastReadPosts = getPreferences().getLong("last_read_post_timestamp", 0)
        val db = DatabaseBackend.get()
        val hasNewPosts = db != null && db.getPosts().stream().anyMatch { p ->
            val published = p.published
            published != null && published.time > lastReadPosts
        }

        // Show badge for missed calls in bottom nav
        val hasNewMissedCalls = xmppConnectionService.getNotificationService().hasNewMissedCalls()

        // The four `getOrCreateBadge` calls, and the deleted `setDrawerLockMode` pair, are one state
        // assignment now.
        chromeState =
            chromeState.copy(
                badges =
                    ConversationNavBadges(
                        chats = unreadCount,
                        calls = hasNewMissedCalls,
                        stories = hasNewStories,
                        feeds = hasNewPosts,
                    ),
                drawerEnabled = getBooleanPreference("show_nav_drawer", R.bool.show_nav_drawer),
                twoPane = twoPane(),
            )

        val chatRequestsPref = xmppConnectionService.getStringPreference("chat_requests", R.string.default_chat_requests)
        val accountUnreads = HashMap<Account?, Int>()
        val tags = TreeMap<Tag, Int>()
        val conversationList = ArrayList<Conversation>()
        var totalUnread = 0
        var dmUnread = 0
        var channelUnread = 0
        var chatRequests = 0
        populateWithOrderedConversationList(conversationList, false, false)
        for (c in conversationList) {
            val unread = c.unreadCount(xmppConnectionService)
            if (selectedAccount == null || selectedAccount.getUuid() == (c.getAccount() ?: throw NullPointerException()).getUuid()) {
                if (c.isChatRequest(chatRequestsPref)) {
                    chatRequests++
                } else {
                    totalUnread += unread
                    if (c.getMode() == Conversation.MODE_MULTI) {
                        channelUnread += unread
                    } else {
                        dmUnread += unread
                    }
                }
            }
            accountUnreads[c.getAccount()] = (accountUnreads[c.getAccount()] ?: 0) + unread
        }
        filterByMainFilter(conversationList)
        for (c in conversationList) {
            if (selectedAccount == null || selectedAccount.getUuid() == (c.getAccount() ?: throw NullPointerException()).getUuid()) {
                val unread = c.unreadCount(xmppConnectionService)
                for (tag in c.getTags(this)) {
                    if ("Channel" == tag.name) continue
                    tags[tag] = (tags[tag] ?: 0) + unread
                }
            }
        }

        // The drawer's content, rebuilt whole where the deleted adapter was mutated: the four
        // `updateBadge` calls, the inserted `Chat requests` item, the added, removed and sorted tag
        // items and the header's profiles are four lists now, and the only identity that survives is
        // the drawer id each row carries.
        val accounts = AccountRegistry.get().getAccounts()
        // The deleted `AccountHeaderView`'s own first-run rule: the first profile it is given
        // becomes active, and the `All accounts` profile is the first one exactly when there is more
        // than one account. An active account that leaves the registry falls back to the rule again.
        if (!activeAccountSettled || (activeAccount != null && accounts.none { it == activeAccount })) {
            activeAccount = if (accounts.size > 1) null else accounts.firstOrNull()
            activeAccountSettled = true
        }
        val profiles = ArrayList<DrawerProfile>()
        val profileAccounts = ArrayList<Account?>()
        val hasPhoneAccounts = accounts.any { a -> a.getGateways("pstn").size > 0 }
        if (accounts.size > 1) {
            profiles.add(
                DrawerProfile(
                    id = PROFILE_ID_BASE + profiles.size,
                    name = getString(R.string.all_accounts),
                    description = getString(R.string.all_accounts),
                    // The deleted `ProfileDrawerItem` carried `R.drawable.tulkki_logo` as its
                    // `iconRes`, so the logo goes in the profile's circular image slot - not an
                    // entry icon - and the header draws it too while this pseudo-profile is active.
                    avatar = allAccountsLogo,
                    badge = 0,
                    selected = selectedAccount == null,
                ),
            )
            profileAccounts.add(null)
        }
        val accountAvatarSize = resources.getDimension(R.dimen.avatar_on_drawer).toInt()
        for (a in accounts) {
            val avatar = xmppConnectionService.getAvatarService().getAccountAvatar(a, accountAvatarSize, true)
            if (avatar == null) {
                val task = AvatarWorkerTask(this, R.dimen.avatar_on_drawer)
                try {
                    task.execute(a)
                } catch (ignored: RejectedExecutionException) {
                }
                refreshAccounts = true
            }
            profiles.add(
                DrawerProfile(
                    id = PROFILE_ID_BASE + profiles.size,
                    name = a.getDisplayName() ?: "",
                    description = a.getJid().asBareJid().toString(),
                    avatar = avatar,
                    badge = accountUnreads[a] ?: 0,
                    selected = a == selectedAccount,
                ),
            )
            profileAccounts.add(a)
        }
        profiles.add(
            DrawerProfile(
                id = DRAWER_MANAGE_ACCOUNT,
                name = getString(R.string.action_accounts),
                description = "",
                avatar = null,
                // The deleted `ProfileSettingDrawerItem`'s own `iconRes`.
                icon = R.drawable.ic_settings_24dp,
                badge = 0,
                selected = false,
            ),
        )
        profiles.add(
            DrawerProfile(
                id = DRAWER_ADD_ACCOUNT,
                name = getString(R.string.action_add_account),
                description = "",
                avatar = null,
                icon = R.drawable.ic_add_24dp,
                badge = 0,
                selected = false,
            ),
        )
        if (hasPhoneAccounts) {
            profiles.add(
                DrawerProfile(
                    id = DRAWER_MANAGE_PHONE_ACCOUNTS,
                    // The literal the deleted header carried, kept as it was.
                    name = "Manage Phone Accounts",
                    description = "",
                    avatar = null,
                    icon = R.drawable.ic_call_24dp,
                    badge = 0,
                    selected = false,
                ),
            )
        }
        drawerProfiles = profileAccounts

        val filters =
            listOf(
                DrawerEntry(
                    id = DRAWER_ALL_CHATS,
                    label = getString(R.string.all_chats),
                    icon = R.drawable.ic_chat_24dp,
                    selected = mainFilter == DRAWER_ALL_CHATS,
                ),
                DrawerEntry(
                    id = DRAWER_UNREAD_CHATS,
                    label = getString(R.string.unread_chats),
                    icon = R.drawable.chat_unread_24dp,
                    badge = totalUnread,
                    selected = mainFilter == DRAWER_UNREAD_CHATS,
                ),
                DrawerEntry(
                    id = DRAWER_DIRECT_MESSAGES,
                    label = getString(R.string.direct_messages),
                    icon = R.drawable.ic_person_24dp,
                    badge = dmUnread,
                    selected = mainFilter == DRAWER_DIRECT_MESSAGES,
                ),
                DrawerEntry(
                    id = DRAWER_CHANNELS,
                    label = getString(R.string.channels),
                    icon = R.drawable.ic_group_24dp,
                    badge = channelUnread,
                    selected = mainFilter == DRAWER_CHANNELS,
                ),
            )
        val requests =
            if (chatRequests > 0) {
                listOf(
                    DrawerEntry(
                        id = DRAWER_CHAT_REQUESTS,
                        label = getString(R.string.chat_requests),
                        icon = R.drawable.ic_person_add_24dp,
                        badge = chatRequests,
                        selected = mainFilter == DRAWER_CHAT_REQUESTS,
                    ),
                )
            } else {
                emptyList()
            }
        val tagList = ArrayList<Tag>()
        val tagRows = ArrayList<DrawerEntry>()
        for (entry in tags.entries) {
            tagRows.add(
                DrawerEntry(
                    id = TAG_ID_BASE + tagList.size,
                    label = entry.key.name,
                    badge = entry.value,
                    selected = selectedTag.contains(entry.key),
                ),
            )
            tagList.add(entry.key)
        }
        drawerTags = tagList
        val sticky =
            listOf(
                DrawerEntry(
                    id = DRAWER_TULKKI_SETTINGS,
                    label = getString(R.string.tulkki_settings_title),
                    description = getString(R.string.tulkki_drawer_summary),
                    icon = R.drawable.ic_tulkki_translate_24dp,
                ),
                DrawerEntry(
                    id = DRAWER_MEDIA_GALLERY,
                    label = getString(R.string.media_gallery),
                    icon = R.drawable.ic_image_24dp,
                ),
                DrawerEntry(
                    id = DRAWER_SETTINGS,
                    label = getString(R.string.action_settings),
                    icon = R.drawable.ic_settings_24dp,
                ),
            )
        chrome.updateDrawer(
            ConversationDrawerState(
                profiles = profiles,
                items = filters,
                requests = requests,
                tags = tagRows,
                sticky = sticky,
            ),
        )

        refreshAccounts = false
        applyChrome()
        invalidateActionBarTitle()
        invalidateOptionsMenu()
    }

    override fun onBackendConnected() {
        // The composition that creates the two fragment containers has not run yet, so a transaction
        // here would find no view. The whole body is replayed by `attachPanes`.
        if (!panesReady) {
            postponedBackendConnected = true
            return
        }
        backendConnected()
    }

    /**
     * The deleted `onBackendConnected`, with the one early return the header's own construction used
     * to have. It is reached either because the panes already exist or from [attachPanes], which is
     * the composition's own callback.
     */
    private fun backendConnected() {
        val useSavedState = savedState
        savedState = null
        if (performRedirectIfNecessary(true)) {
            return
        }
        xmppConnectionService.getNotificationService().setIsInForeground(true)
        var intent = pendingViewIntent.pop()
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (twoPane()) {
                    notifyFragmentOfBackendConnected(R.id.main_fragment)
                }
            } else {
                intent = null
            }
        }

        if (intent == null) {
            for (id in FRAGMENT_ID_NOTIFICATION_ORDER) {
                notifyFragmentOfBackendConnected(id)
            }

            val activityResult = postponedActivityResult.pop()
            if (activityResult != null) {
                handleActivityResult(activityResult)
            }
            if (twoPane() &&
                ConversationLookup.conversation(this) == null
            ) {
                // The fragment answers a local uuid now that its rows are snapshots, so the entity is
                // resolved here, at the one place that wants one.
                val suggestion = ConversationListFragment.suggestionUuid(this)
                val conversation =
                    if (suggestion == null || xmppConnectionService == null) {
                        null
                    } else {
                        xmppConnectionService.findConversationByUuid(suggestion) as Conversation?
                    }
                if (conversation != null) {
                    openConversation(conversation, null)
                }
            }
            showDialogsIfMainIsOverview()
        }

        if (useSavedState != null) {
            mainFilter = useSavedState.getLong("mainFilter", DRAWER_ALL_CHATS)
            selectedTag = useSavedState.getSerializable("selectedTag") as HashSet<Tag>
            // The deleted `header.withSavedInstance` kept the active profile; a bare address is what
            // survives an account list rebuild, and a `null` address is the `All accounts` profile.
            val activeJid = useSavedState.getString("activeAccount")
            activeAccount =
                if (activeJid == null) {
                    null
                } else {
                    AccountRegistry.get().getAccounts().firstOrNull {
                        it.getJid().asBareJid().toString() == activeJid
                    }
                }
            activeAccountSettled = true
        }
        refreshUiReal()
    }

    /**
     * The first commit, and the composition's own signal that the two containers exist.
     *
     * A `ComposeView` composes when its window attaches - after `onCreate` and after `onStart` - so
     * `main_fragment` and `secondary_fragment` do not exist while `onCreate` runs. Everything that
     * needs one waits for this: the initial transaction, the title's read of the panes, the dialogs
     * that belong to the overview and a backend connection that arrived first.
     *
     * <p>**And a restored fragment has to be re-attached.** The framework creates a restored
     * fragment's view in `onStart`, when these containers did not exist yet, and a restored fragment
     * is spared the "No view found" throw but is not retried either - it would draw nothing. A
     * `detach`/`attach` pair re-runs the state machine with the container now present, which is the
     * only public way to ask for that; it costs the restored scroll position, which the fragment
     * saves for a rotation and not for this.
     */
    private fun attachPanes() {
        if (panesReady) {
            return
        }
        panesReady = true
        initializeFragments()
        // The deleted `onCreate` could read the panes straight after its transaction because the
        // layout's containers existed; here the frame that follows has to be forced, or
        // `findFragmentById` answers `null` for the fragment the read below is about.
        executePendingTransactions(fragmentManager)
        for (id in intArrayOf(R.id.main_fragment, R.id.secondary_fragment)) {
            val fragment = fragmentManager.findFragmentById(id) ?: continue
            if (fragment.view == null) {
                fragmentManager.beginTransaction().detach(fragment).attach(fragment).commit()
            }
        }
        executePendingTransactions(fragmentManager)
        invalidateActionBarTitle()
        showDialogsIfMainIsOverview()
        if (postponedBackendConnected) {
            postponedBackendConnected = false
            backendConnected()
        }
        // The fragments are told nothing here, deliberately: the backend-connected callback has one
        // caller, the service's own `onBackendConnected`, and the branch above replays it when that
        // arrived before these panes. A pane that exists first therefore waits for the binder
        // instead of being told early, which read `xmppConnectionService` before it was assigned.
        // The two conditions - this pane and the bound service - gate the notification whichever
        // arrives first, and neither alone can fire it.
        val postponed = postponedConversation
        if (postponed != null) {
            postponedConversation = null
            openConversation(postponed.first, postponed.second)
        }
    }

    /** The `-w945dp` qualifier's own rule: a smallest screen width of at least 945 dp. */
    private fun twoPane(): Boolean =
        resources.configuration.smallestScreenWidthDp >= WIDE_SCREEN_WIDTH_DP

    /** The deleted `onDrawerItemClickListener`: one id, one decision. */
    private fun onDrawerItemClick(id: Long) {
        if (id == DRAWER_SETTINGS) {
            startActivity(Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java))
            return
        } else if (id == DRAWER_TULKKI_SETTINGS) {
            // Tulkki: SettingsActivity opens the one screen it is named here, so the row lands on
            // Tulkki's settings rather than the settings list the row above it opens.
            val tulkkiSettings = Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java)
            tulkkiSettings.putExtra(uk.xa0.tulkki.ui.activity.SettingsActivity.EXTRA_SETTINGS_FRAGMENT, TulkkiSettingsFragment::class.java.name)
            startActivity(tulkkiSettings)
            return
        } else if (id == DRAWER_START_CHAT_CONTACT) {
            launchStartConversation()
        } else if (id == DRAWER_START_CHAT_NEW) {
            launchStartConversation(R.id.create_contact)
        } else if (id == DRAWER_START_CHAT_GROUP) {
            launchStartConversation(R.id.create_private_group_chat)
        } else if (id == DRAWER_START_CHAT_PUBLIC) {
            launchStartConversation(R.id.create_public_channel)
        } else if (id == DRAWER_START_CHAT_DISCOVER) {
            launchStartConversation(R.id.discover_public_channels)
        } else if (id == DRAWER_MEDIA_GALLERY) {
            startActivity(Intent(this, MediaBrowserActivity::class.java))
            return
        } else if (id == DRAWER_ALL_CHATS || id == DRAWER_UNREAD_CHATS || id == DRAWER_DIRECT_MESSAGES || id == DRAWER_CHANNELS || id == DRAWER_CHAT_REQUESTS) {
            selectedTag.clear()
            mainFilter = id
        } else if (id >= TAG_ID_BASE) {
            val tag = drawerTags.getOrNull((id - TAG_ID_BASE).toInt()) ?: return
            selectedTag.clear()
            selectedTag.add(tag)
        }

        val fm = fragmentManager
        while (fm.backStackEntryCount > 0) {
            try {
                fm.popBackStackImmediate()
            } catch (e: IllegalStateException) {
                break
            }
        }

        refreshUi()
    }

    /** The deleted `onDrawerItemLongClickListener`: the same ids, and the tag toggle it added. */
    private fun onDrawerItemLongClick(id: Long) {
        if (id == DRAWER_ALL_CHATS || id == DRAWER_UNREAD_CHATS || id == DRAWER_DIRECT_MESSAGES || id == DRAWER_CHANNELS || id == DRAWER_CHAT_REQUESTS) {
            selectedTag.clear()
            mainFilter = id
        } else if (id >= TAG_ID_BASE) {
            val tag = drawerTags.getOrNull((id - TAG_ID_BASE).toInt()) ?: return
            if (selectedTag.contains(tag)) {
                selectedTag.remove(tag)
            } else {
                selectedTag.add(tag)
            }
        }

        refreshUi()
    }

    /** The deleted `onAccountHeaderListener`: the profile rows, and the three that are actions. */
    private fun onAccountSelected(id: Long, isCurrent: Boolean) {
        if (isCurrent) return // Ignore switching to already selected profile

        if (id == DRAWER_MANAGE_ACCOUNT) {
            AccountUtils.launchManageAccounts(this)
            return
        }

        if (id == DRAWER_ADD_ACCOUNT) {
            startActivity(Intent(this, EditAccountActivity::class.java))
            return
        }

        if (id == DRAWER_MANAGE_PHONE_ACCOUNTS) {
            val permissions: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            requestPermissions(permissions, REQUEST_MICROPHONE)
            return
        }

        // A profile row is the header's own selection: the deleted drawer set its active profile
        // before the listener ran, and this is where that answer is kept now.
        activeAccount = drawerProfiles.getOrNull((id - PROFILE_ID_BASE).toInt())
        activeAccountSettled = true

        val fm = fragmentManager
        while (fm.backStackEntryCount > 0) {
            try {
                fm.popBackStackImmediate()
            } catch (e: IllegalStateException) {
                break
            }
        }

        refreshUi()
    }

    /** The deleted `onAccountHeaderProfileImageListener`: the active profile's own avatar. */
    private fun onAccountAvatar() {
        val account = activeAccount
        if (account == null) {
            AccountUtils.launchManageAccounts(this)
        } else {
            switchToAccount(account)
        }
    }

    override fun colorCodeAccounts(): Boolean {
        return if (activeAccount != null) false else super.colorCodeAccounts()
    }

    override fun populateWithOrderedConversationList(list: MutableList<Conversation>) {
        populateWithOrderedConversationList(list, true, true)
    }

    fun populateWithOrderedConversationList(list: MutableList<Conversation>, filter: Boolean, sort: Boolean) {
        if (sort) {
            super.populateWithOrderedConversationList(list)
        } else {
            list.addAll(xmppConnectionService.getConversationList() as List<Conversation>)
        }

        if (!filter) return
        filterByMainFilter(list)

        val selectedAccount = selectedAccount()

        for (c in ImmutableList.copyOf(list)) {
            if (selectedAccount != null && selectedAccount.getUuid() != (c.getAccount() ?: throw NullPointerException()).getUuid()) {
                list.remove(c)
            } else if (selectedTag.isNotEmpty()) {
                val tags = HashSet(c.getTags(this))
                tags.retainAll(selectedTag)
                if (tags.isEmpty()) list.remove(c)
            }
        }
    }

    protected fun selectedAccount(): Account? = activeAccount

    protected fun filterByMainFilter(list: MutableList<Conversation>) {
        val chatRequests = xmppConnectionService.getStringPreference("chat_requests", R.string.default_chat_requests)
        for (c in ImmutableList.copyOf(list)) {
            if (mainFilter == DRAWER_CHANNELS && c.getMode() != Conversation.MODE_MULTI) {
                list.remove(c)
            } else if (mainFilter == DRAWER_DIRECT_MESSAGES && c.getMode() == Conversation.MODE_MULTI) {
                list.remove(c)
            } else if (mainFilter == DRAWER_UNREAD_CHATS && c.unreadCount(xmppConnectionService) < 1) {
                list.remove(c)
            } else if (mainFilter == DRAWER_CHAT_REQUESTS && !c.isChatRequest(chatRequests)) {
                list.remove(c)
            }
            if (mainFilter != DRAWER_CHAT_REQUESTS && c.isChatRequest(chatRequests)) {
                list.remove(c)
            }
        }
    }

    override fun launchStartConversation() {
        launchStartConversation(0)
    }

    fun launchStartConversation(goTo: Int) {
        StartConversationActivity.launch(
            this,
            selectedAccount(),
            selectedTag.joinToString(", ") { tag -> tag.name },
            goTo,
        )
    }

    private fun performRedirectIfNecessary(noAnimation: Boolean): Boolean {
        return performRedirectIfNecessary(null, noAnimation)
    }

    private fun performRedirectIfNecessary(ignore: Conversation?, noAnimation: Boolean): Boolean {
        if (xmppConnectionService == null) {
            return false
        }

        val isConversationListEmpty = xmppConnectionService.isConversationListEmpty(ignore)
        if (isConversationListEmpty && mRedirectInProcess.compareAndSet(false, true)) {
            val intent = UiHost.installed().redirectionIntent(this)
            if (noAnimation) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            runOnUiThread {
                startActivity(intent)
                if (noAnimation) {
                    overridePendingTransition(0, 0)
                }
            }
        }
        return mRedirectInProcess.get()
    }

    private fun showDialogsIfMainIsOverview() {
        val incomplete = if (xmppConnectionService != null) AccountRegistry.get().onboardingIncomplete() else null
        if (incomplete != null) {
            UiHost.installed().finishOnboarding(this, incomplete.first, incomplete.second)
        }
        if (xmppConnectionService == null || xmppConnectionService.isOnboarding()) {
            return
        }
        val fragment = fragmentManager.findFragmentById(R.id.main_fragment)
        if (fragment is ConversationListFragment) {
            if (UiHost.installed().checkForCrash(this)) return
            if (offerToSetupDiallerIntegration()) return
            if (openBatteryOptimizationDialogIfNeeded()) return
            if (requestNotificationPermissionIfNeeded()) return
            if (askAboutNomedia()) return
        }
    }

    @SuppressLint("HardwareIds")
    private fun getBatteryOptimizationPreferenceKey(): String {
        val device = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return "show_battery_optimization" + (device ?: "")
    }

    private fun setNeverAskForBatteryOptimizationsAgain() {
        getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply()
    }

    private fun openBatteryOptimizationDialogIfNeeded(): Boolean {
        if (isOptimizingBattery() &&
            getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true)
        ) {
            val builder = MaterialAlertDialogBuilder(this)
            builder.setTitle(R.string.battery_optimizations_enabled)
            builder.setMessage(
                getString(
                    R.string.battery_optimizations_enabled_dialog,
                    BuildConfig.APP_NAME,
                ),
            )
            builder.setPositiveButton(
                R.string.next,
            ) { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                val uri = Uri.parse("package:" + packageName)
                intent.setData(uri)
                try {
                    startActivityForResult(intent, REQUEST_BATTERY_OP)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(
                        this,
                        R.string.device_does_not_support_battery_op,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            builder.setOnDismissListener { setNeverAskForBatteryOptimizationsAgain() }
            val dialog = builder.create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()
            return true
        }
        return false
    }

    private fun requestNotificationPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATION,
            )
            return true
        }
        return false
    }

    private fun askAboutNomedia(): Boolean {
        if (getPreferences().contains("nomedia")) return false

        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.show_media_title)
        builder.setMessage(R.string.show_media_summary)
        builder.setPositiveButton(uk.xa0.tulkki.data.R.string.yes) { _, _ ->
            getPreferences().edit().putBoolean("nomedia", false).apply()
        }
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.no) { _, _ ->
            getPreferences().edit().putBoolean("nomedia", true).apply()
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return true
    }

    private fun offerToSetupDiallerIntegration(): Boolean {
        if (mRequestCode == DIALLER_INTEGRATION) {
            mRequestCode = -1
            return true
        }
        if (Build.VERSION.SDK_INT < 23) return false
        if (Build.VERSION.SDK_INT >= 33) {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM) && !packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)) return false
        } else {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE)) return false
        }

        val pstnGateways = AccountRegistry.get().getAccounts().stream()
            .flatMap { a -> a.getGateways("pstn").stream() }
            .map { a -> a.getJid().asBareJid().toString() }
            .collect(Collectors.toSet())

        if (pstnGateways.size < 1) return false
        val fromPrefs = getPreferences().getStringSet("pstn_gateways", setOf("UPGRADE")) ?: setOf()
        getPreferences().edit().putStringSet("pstn_gateways", pstnGateways).apply()
        pstnGateways.removeAll(fromPrefs)
        if (pstnGateways.size < 1) return false

        if (fromPrefs.contains("UPGRADE")) return false

        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(getString(R.string.dialler_integration_title))
        builder.setMessage("Tulkki can integrate with your system's dialler app, so you can dial calls through your configured gateway " + pstnGateways.joinToString(", ") + ".\n\nEnabling this integration will require granting microphone permission to the app.  Would you like to enable it now?")
        builder.setPositiveButton(uk.xa0.tulkki.data.R.string.yes) { _, _ ->
            val permissions: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            requestPermissions(permissions, REQUEST_MICROPHONE)
        }
        builder.setNegativeButton(uk.xa0.tulkki.data.R.string.no) { _, _ ->
            showDialogsIfMainIsOverview()
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return true
    }

    private fun notifyFragmentOfBackendConnected(id: Int) {
        val fragment = fragmentManager.findFragmentById(id)
        if (fragment is OnBackendConnected) {
            fragment.onBackendConnected()
        }
    }

    private fun refreshFragment(id: Int) {
        val fragment = fragmentManager.findFragmentById(id)
        if (fragment is XmppFragment) {
            fragment.refresh()
            if (refreshForNewCaps) fragment.refreshForNewCaps(newCapsJids)
        }
    }

    private fun processViewIntent(intent: Intent): Boolean {
        val uuid = intent.getStringExtra(EXTRA_CONVERSATION)
        val conversation =
            if (uuid != null) {
                xmppConnectionService.findConversationByUuidReliable(uuid) as Conversation?
            } else {
                null
            }
        if (conversation == null) {
            Log.d(Config.LOGTAG, "unable to view conversation with uuid:$uuid")
            return false
        }
        openConversation(conversation, intent.extras)
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults)
        if (grantResults.size > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                when (requestCode) {
                    REQUEST_OPEN_MESSAGE -> {
                        refreshUiReal()
                        ConversationLookup.openPendingMessage(this)
                    }

                    REQUEST_PLAY_PAUSE -> ConversationLookup.startStopPending(this)

                    REQUEST_MICROPHONE -> {
                        val intent = Intent()
                        intent.setComponent(
                            ComponentName(
                                "com.android.server.telecom",
                                "com.android.server.telecom.settings.EnableAccountPreferenceActivity",
                            ),
                        )
                        try {
                            startActivityForResult(intent, DIALLER_INTEGRATION)
                        } catch (e: ActivityNotFoundException) {
                            displayToast("Dialler integration not available on your OS")
                        }
                    }
                }
            } else {
                if (requestCode != REQUEST_POST_NOTIFICATION) showDialogsIfMainIsOverview()
            }
        } else {
            if (requestCode != REQUEST_POST_NOTIFICATION) showDialogsIfMainIsOverview()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == DIALLER_INTEGRATION) {
            mRequestCode = requestCode
            try {
                startActivity(Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
            } catch (e: ActivityNotFoundException) {
                displayToast("Dialler integration not available on your OS")
            }
            return
        }

        val activityResult = ActivityResult.of(requestCode, resultCode, data)
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult)
        } else {
            postponedActivityResult.push(activityResult)
        }
    }

    private fun handleActivityResult(activityResult: ActivityResult) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data)
        } else {
            handleNegativeActivityResult(activityResult.requestCode)
        }
        if (activityResult.requestCode == REQUEST_BATTERY_OP) {
            // the result code is always 0 even when battery permission were granted
            requestNotificationPermissionIfNeeded()
            uk.xa0.tulkki.xmpp.services.ForegroundServiceLifecycle.toggleForegroundService(xmppConnectionService)
        }
    }

    private fun handleNegativeActivityResult(requestCode: Int) {
        val conversation = ConversationLookup.conversationReliable(this)
        when (requestCode) {
            ConversationRequests.REQUEST_DECRYPT_PGP -> {
                if (conversation == null) {
                    return
                }
                ((conversation.getAccount() ?: throw NullPointerException()).getPgpDecryptionService() ?: throw NullPointerException()).giveUpCurrentDecryption()
            }

            REQUEST_BATTERY_OP -> setNeverAskForBatteryOptimizationsAgain()
        }
    }

    private fun handlePositiveActivityResult(requestCode: Int, data: Intent?) {
        val conversation = ConversationLookup.conversationReliable(this)
        if (conversation == null) {
            Log.d(Config.LOGTAG, "conversation not found")
            return
        }
        when (requestCode) {
            ConversationRequests.REQUEST_DECRYPT_PGP -> ((conversation.getAccount() ?: throw NullPointerException()).getPgpDecryptionService() ?: throw NullPointerException()).continueDecryption(data ?: throw NullPointerException())

            REQUEST_CHOOSE_PGP_ID -> {
                val id = data?.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0) ?: 0
                if (id != 0L) {
                    (conversation.getAccount() ?: throw NullPointerException()).setPgpSignId(id)
                    announcePgp(conversation.getAccount() ?: throw NullPointerException(), null, null, onOpenPGPKeyPublished)
                } else {
                    choosePgpSignId(conversation.getAccount() ?: throw NullPointerException())
                }
            }

            REQUEST_ANNOUNCE_PGP ->
                announcePgp(conversation.getAccount() ?: throw NullPointerException(), conversation, data, onOpenPGPKeyPublished)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedState = savedInstanceState
        ConversationMenuConfigurator.reloadFeatures(this)
        OmemoSetting.load(this)
        // The chrome draws its bar behind the system bars and hands the panes the space they leave,
        // so the window must not inset itself for them first - on every API level, which is what
        // `enableEdgeToEdge` settles. See TulkkiChrome for the whole pattern.
        enableEdgeToEdge()
        fragmentManager.addOnBackStackChangedListener { invalidateActionBarTitle() }
        fragmentManager.addOnBackStackChangedListener { showDialogsIfMainIsOverview() }
        fragmentManager.addOnBackStackChangedListener {
            backPressedCallback.isEnabled = fragmentManager.backStackEntryCount > 0
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        chromeState =
            chromeState.copy(
                tab = ConversationNavTab.CHATS,
                twoPane = twoPane(),
                drawerEnabled = getBooleanPreference("show_nav_drawer", R.bool.show_nav_drawer),
            )
        setTulkkiContent(darkTheme = isDark()) {
            ConversationListChrome(
                state = chrome.state,
                drawer = chrome.drawer,
                // The loader writes into a view; the bar hosts that one view through an AndroidView
                // and sizes it to nothing while there is no conversation to show.
                avatar = toolbarAvatarView,
                menu = chromeMenu(),
                onUp = { handleUp() },
                onDrawerItem = { id -> onDrawerItemClick(id) },
                onDrawerItemLongClick = { id -> onDrawerItemLongClick(id) },
                onAccount = { id, current -> onAccountSelected(id, current) },
                onAccountAvatar = { onAccountAvatar() },
                onTab = { tab -> onTab(tab) },
                onTitle = { titleConversation?.let { openConversationDetails(it) } },
                actions = {
                    if (searchVisible()) {
                        ChromeSearchAction(
                            combined = combinedSearchVisible(),
                            onSearchAll = { openSearchAllChats() },
                            onSearchThis = { openSearchThisConversation() },
                        )
                    }
                    if (qrCodeScanVisible()) {
                        ChromeIconAction(
                            icon = R.drawable.ic_qr_code_scanner_24dp,
                            label = getString(R.string.scan_qr_code),
                        ) {
                            UriHandlerActivity.scan(this@ConversationListActivity)
                        }
                    }
                    if (reportSpamVisible()) {
                        ChromeIconAction(
                            icon = R.drawable.ic_report_24dp,
                            label = getString(R.string.report_spam),
                        ) {
                            reportSpam()
                        }
                    }
                },
            ) {
                ConversationListPanes(
                    twoPane = chrome.state.twoPane,
                    mainFragmentId = R.id.main_fragment,
                    secondaryFragmentId = R.id.secondary_fragment,
                    onReady = { attachPanes() },
                )
            }
        }
        applyChrome()
        val intent: Intent?
        if (savedInstanceState == null) {
            intent = getIntent()
        } else {
            intent = savedInstanceState.getParcelable("intent")
        }
        if (isViewOrShareIntent(intent)) {
            pendingViewIntent.push(intent)
            setIntent(createLauncherIntent(this))
        }
    }

    /**
     * The bar's overflow, where `activity_conversation_list.xml`'s and
     * `fragment_conversation_list.xml`'s reachable items were. The two menus' own visibility rules
     * are kept whole: the onboarding gate hides the four destinations' entries and settings, the
     * bottom bar's visibility hides the three it duplicates, `show_nav_drawer` decides the media
     * gallery, one account or many decides `Manage accounts` against `Manage account`, and
     * note-to-self needs exactly one account.
     */
    private fun chromeMenu(): List<ChromeMenuItem> {
        val service = xmppConnectionService
        val onboarding = service == null || service.isOnboarding()
        val accounts = AccountRegistry.get().getAccounts()
        val items = ArrayList<ChromeMenuItem>()
        if (service != null && accounts.size == 1) {
            items.add(ChromeMenuItem(getString(R.string.note_to_self)) { openNoteToSelf() })
        }
        if (!onboarding && !navigationBarVisible()) {
            items.add(ChromeMenuItem(getString(R.string.feeds)) { openFeeds() })
        }
        if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            items.add(ChromeMenuItem(getString(R.string.action_accounts)) { AccountUtils.launchManageAccounts(this) })
        } else {
            items.add(
                ChromeMenuItem(getString(R.string.action_account)) {
                    switchToAccount(AccountUtils.getFirst(accounts) ?: throw NullPointerException())
                },
            )
        }
        if (!onboarding && !getBooleanPreference("show_nav_drawer", R.bool.show_nav_drawer)) {
            items.add(ChromeMenuItem(getString(R.string.media_gallery)) { startActivity(Intent(this, MediaBrowserActivity::class.java)) })
        }
        if (!onboarding && !navigationBarVisible()) {
            items.add(ChromeMenuItem(getString(R.string.stories)) { openStories() })
            items.add(ChromeMenuItem(getString(R.string.calls)) { openCalls() })
        }
        if (!onboarding) {
            items.add(ChromeMenuItem(getString(R.string.action_settings)) { startActivity(Intent(this, uk.xa0.tulkki.ui.activity.SettingsActivity::class.java)) })
        }
        return items
    }

    /** The search affordance, where `action_search`'s own `showAsAction="always"` put it. */
    private fun searchVisible(): Boolean =
        resources.getBoolean(R.bool.show_combined_search_options) ||
            resources.getBoolean(R.bool.show_individual_search_options)

    /** Whether the search affordance carries the two-entry submenu the wide layouts ask for. */
    private fun combinedSearchVisible(): Boolean = resources.getBoolean(R.bool.show_combined_search_options)

    /** The deleted `onCreateOptionsMenu`'s QR gate, live rather than deferred to a menu prepare. */
    private fun qrCodeScanVisible(): Boolean {
        if (!isCameraFeatureAvailable()) {
            return false
        }
        val service = xmppConnectionService
        if (service != null && service.isOnboarding()) {
            return false
        }
        if (!resources.getBoolean(R.bool.show_qr_code_scan)) {
            return false
        }
        return fragmentManager.findFragmentById(R.id.main_fragment) is ConversationListFragment
    }

    /** The deleted `onCreateOptionsMenu`'s report-spam gate: the overview, on chat requests. */
    private fun reportSpamVisible(): Boolean =
        fragmentManager.findFragmentById(R.id.main_fragment) is ConversationListFragment &&
            mainFilter == DRAWER_CHAT_REQUESTS

    override fun onConversationSelected(conversation: Conversation) {
        clearPendingViewIntent()
        if (ConversationLookup.conversation(this) === conversation) {
            Log.d(
                Config.LOGTAG,
                "ignore onConversationSelected() because conversation is already open",
            )
            return
        }
        openConversation(conversation, null)
    }

    fun clearPendingViewIntent() {
        if (pendingViewIntent.clear()) {
            Log.e(Config.LOGTAG, "cleared pending view intent")
        }
    }

    fun navigationBarVisible(): Boolean = chromeState.showNavBar

    fun showNavigationBar(): Boolean {
        if (!getBooleanPreference("show_nav_bar", R.bool.show_nav_bar)) {
            chromeState = chromeState.copy(showNavBar = false)
            applyChrome()
            return false
        }

        chromeState = chromeState.copy(showNavBar = true)
        applyChrome()
        return true
    }

    /**
     * The deleted `onOptionsItemSelected`'s `android.R.id.home` branch, now the bar's own leading
     * icon: leave a conversation if one is open, walk the back stack back to the list, and otherwise
     * do nothing - the drawer handle is the bar's own, and it opens the drawer it owns.
     */
    private fun handleUp() {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return
        }
        val fm = fragmentManager
        if (Build.VERSION.SDK_INT >= 26) {
            val f = fm.fragments[fm.fragments.size - 1]
            if (f != null && f is ConversationFragment) {
                if (f.onBackPressed()) {
                    return
                }
            }
        }
        if (fm.backStackEntryCount > 0) {
            try {
                fm.popBackStack()
            } catch (e: IllegalStateException) {
                Log.w(Config.LOGTAG, "Unable to pop back stack after pressing home button")
            }
        }
    }

    /** The deleted `action_search_all_chats`. */
    private fun openSearchAllChats() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    /** The deleted `action_search_this_conversation`. */
    private fun openSearchThisConversation() {
        val conversation = ConversationLookup.conversation(this) ?: return
        val intent = Intent(this, SearchActivity::class.java)
        intent.putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, conversation.getUuid())
        startActivity(intent)
    }

    /** The deleted `action_report_spam`. */
    private fun reportSpam() {
        val list = ArrayList<Conversation>()
        populateWithOrderedConversationList(list, true, false)
        AlertDialog.Builder(this)
            .setTitle(R.string.report_spam)
            .setMessage(R.string.block_user_and_spam_question)
            .setPositiveButton(uk.xa0.tulkki.data.R.string.yes) { _, _ ->
                for (conversation in list) {
                    val m = conversation.getLatestMessage()
                    xmppConnectionService.sendBlockRequest(
                            conversation.getAccount(), conversation.getBlockedJid(),
                            true, m.getServerMsgId())
                }
            }
            .setNegativeButton(uk.xa0.tulkki.data.R.string.no, null).show()
    }

    /** The deleted `action_note_to_self`: one account, its own contact, one conversation. */
    private fun openNoteToSelf() {
        val accounts = AccountRegistry.get().getAccounts()
        if (accounts.size == 1) {
            val self = Contact(accounts[0].getSelfContact())
            val conversation =
                xmppConnectionService.findOrCreateConversation(
                    self.getAccount(),
                    self.getJid(),
                    false,
                    false,
                    null,
                    true,
                    null,
                ) as Conversation
            SoftKeyboardUtils.hideSoftKeyboard(this)
            switchToConversation(conversation)
        }
    }

    /** The deleted `action_feeds`, and the same intent the deleted bottom bar started. */
    private fun openFeeds() {
        val i = Intent(applicationContext, PostsActivity::class.java)
        i.putExtra("show_nav_bar", true)
        startActivity(i)
        overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
    }

    /** The deleted `action_stories`. */
    private fun openStories() {
        val i = Intent(applicationContext, StoriesActivity::class.java)
        i.putExtra("show_nav_bar", true)
        startActivity(i)
        overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
    }

    /** The deleted `action_calls`. */
    private fun openCalls() {
        val i = Intent(applicationContext, CallsActivity::class.java)
        i.putExtra("show_nav_bar", true)
        startActivity(i)
        overridePendingTransition(R.animator.fade_in, R.animator.fade_out)
    }

    /** One of the four destinations: `chats` is this screen and does nothing. */
    private fun onTab(tab: ConversationNavTab) {
        when (tab) {
            ConversationNavTab.CHATS -> Unit
            ConversationNavTab.FEEDS -> openFeeds()
            ConversationNavTab.STORIES -> openStories()
            ConversationNavTab.CALLS -> openCalls()
        }
    }

    private fun displayToast(msg: String) {
        runOnUiThread {
            Toast.makeText(this@ConversationListActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAffiliationChangedSuccessful(jid: Jid) {
    }

    override fun onAffiliationChangeFailed(jid: Jid, resId: Int) {
        displayToast(getString(resId, jid.asBareJid().toString()))
    }

    private fun openConversation(conversation: Conversation, extras: Bundle?) {
        if (!panesReady) {
            // The container this ends in does not exist yet; `attachPanes` replays it. Without this
            // the transaction would be enqueued against an id no view carries yet.
            postponedConversation = Pair(conversation, extras)
            return
        }
        val fragmentManager = fragmentManager
        executePendingTransactions(fragmentManager)
        var conversationFragment = fragmentManager.findFragmentById(R.id.secondary_fragment) as? ConversationFragment
        val mainNeedsRefresh: Boolean
        if (conversationFragment == null) {
            mainNeedsRefresh = false
            val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
            if (mainFragment is ConversationFragment) {
                conversationFragment = mainFragment
            } else {
                conversationFragment = ConversationFragment()
                val fragmentTransaction = fragmentManager.beginTransaction()
                fragmentTransaction.replace(R.id.main_fragment, conversationFragment)
                fragmentTransaction.addToBackStack(null)
                try {
                    fragmentTransaction.commit()
                } catch (e: IllegalStateException) {
                    Log.w(Config.LOGTAG, "state loss while opening conversation", e)
                    // allowing state loss is probably fine since view intents et all are already
                    // stored and a click can probably be 'ignored'
                    return
                }
            }
        } else {
            mainNeedsRefresh = true
        }
        (conversationFragment ?: throw NullPointerException()).reInit(conversation, extras ?: Bundle())
        if (mainNeedsRefresh) {
            refreshFragment(R.id.main_fragment)
        }
        // Tulkki: the `R.id.textinput` field this focused is deleted with the Java text row, and the
        // call was already dead before that: the field sat in the `input` row the Compose composer
        // hides, and a `GONE` view cannot take focus. The field the owner types in is the Compose
        // composer's, and it takes the caret from its own host's focus request on resume.
        invalidateActionBarTitle()
    }

    fun onXmppUriClicked(uri: Uri): Boolean {
        val xmppUri = XmppUri(uri)
        if (xmppUri.isValidJid() && !xmppUri.hasFingerprints()) {
            val conversation =
                xmppConnectionService.findUniqueConversationByJid(xmppUri) as Conversation?
            if (conversation != null) {
                if (xmppUri.getParameter("password") != null) {
                    xmppConnectionService.providePasswordForMuc(conversation, xmppUri.getParameter("password"))
                }
                if (xmppUri.isAction("command")) {
                    startCommand(conversation.getAccount() ?: throw NullPointerException(), xmppUri.getJid(), xmppUri.getParameter("node"))
                } else {
                    val extras = Bundle()
                    extras.putString(Intent.EXTRA_TEXT, xmppUri.getBody())
                    if (xmppUri.isAction("message")) extras.putString(EXTRA_POST_INIT_ACTION, "message")
                    openConversation(conversation, extras)
                }
                return true
            }
        }
        return false
    }

    fun onTelUriClicked(uri: Uri, acct: Account?): Boolean {
        val tel: String
        try {
            tel = UiHost.installed().normalizePhoneNumber(this, uri.schemeSpecificPart)
        } catch (e: IllegalArgumentException) {
            return false
        } catch (e: NumberParseException) {
            return false
        } catch (e: NullPointerException) {
            return false
        }

        val accountsList: List<Account> = if (acct == null) AccountRegistry.get().getAccounts() else listOf(acct)
        val gateways = accountsList
            .flatMap { account -> account.getGateways("pstn") + account.getGateways("sms") }
            .map { a -> a.getJid().asBareJid().toString() }
            .toSet()

        for (gateway in gateways) {
            if (onXmppUriClicked(Uri.parse("xmpp:$tel@$gateway"))) return true
        }

        if (gateways.size == 1 && acct != null) {
            openConversation(
                xmppConnectionService.findOrCreateConversation(
                    acct,
                    Jid.ofLocalAndDomain(tel, gateways.iterator().next()),
                    false,
                    true,
                ) as Conversation,
                null,
            )
            return true
        }

        return false
    }


    override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && keyEvent.isCtrlPressed) {
            val conversationFragment = ConversationLookup.get(this)
            if (conversationFragment != null && conversationFragment.onArrowUpCtrlPressed()) {
                return true
            }
        }
        return super.onKeyDown(keyCode, keyEvent)
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        val pendingIntent = pendingViewIntent.peek()
        savedInstanceState.putParcelable("intent", pendingIntent ?: getIntent())
        savedInstanceState.putLong("mainFilter", mainFilter)
        savedInstanceState.putSerializable("selectedTag", selectedTag)
        // Where the deleted `drawer.saveInstanceState`/`header.saveInstanceState` pair kept the
        // selection and the active profile.
        savedInstanceState.putString("activeAccount", activeAccount?.getJid()?.asBareJid()?.toString())
        super.onSaveInstanceState(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        mRedirectInProcess.set(false)

        // The deleted `setSelectedItemId(R.id.chats)`: this screen is the chats destination, so the
        // bar's own selection is that tab on every start.
        chromeState = chromeState.copy(tab = ConversationNavTab.CHATS)
        applyChrome()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        showLastSeen = preferences.getBoolean("last_activity", resources.getBoolean(uk.xa0.tulkki.xmpp.R.bool.last_activity))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isViewOrShareIntent(intent)) {
            if (xmppConnectionService != null) {
                clearPendingViewIntent()
                processViewIntent(intent)
            } else {
                pendingViewIntent.push(intent)
            }
        } else if (intent.getAction() == ACTION_DESTROY_MUC) {
            try {
                val extras = intent.extras
                if (extras != null && extras.containsKey("MUC_UUID")) {
                    Log.d(Config.LOGTAG, "Get " + intent.getAction() + " intent for " + extras.getString("MUC_UUID"))
                    val conversation =
                        xmppConnectionService.findConversationByUuid(
                            extras.getString("MUC_UUID"),
                        ) as Conversation? ?: return
                    xmppConnectionService.clearConversationHistory(conversation)
                    xmppConnectionService.destroyRoom(conversation, this@ConversationListActivity)
                    endConversation(conversation)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        setIntent(createLauncherIntent(this))
    }

    fun endConversation(conversation: Conversation) {
        xmppConnectionService.archiveConversation(conversation)
        onConversationArchived(conversation)
    }

    override fun onPause() {
        mActivityPaused = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        mActivityPaused = false
    }

    private fun initializeFragments() {
        val fragmentManager = fragmentManager
        var transaction = fragmentManager.beginTransaction()
        val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
        val secondaryFragment =
            fragmentManager.findFragmentById(R.id.secondary_fragment)
        if (mainFragment != null) {
            if (twoPane()) {
                if (mainFragment is ConversationFragment) {
                    fragmentManager.popBackStack()
                    transaction.remove(mainFragment)
                    transaction.commit()
                    fragmentManager.executePendingTransactions()
                    transaction = fragmentManager.beginTransaction()
                    transaction.replace(R.id.secondary_fragment, mainFragment)
                    transaction.replace(R.id.main_fragment, ConversationListFragment())
                    transaction.commit()
                    return
                }
            } else {
                if (secondaryFragment is ConversationFragment) {
                    transaction.remove(secondaryFragment)
                    transaction.commit()
                    fragmentManager.executePendingTransactions()
                    transaction = fragmentManager.beginTransaction()
                    transaction.replace(R.id.main_fragment, secondaryFragment)
                    transaction.addToBackStack(null)
                    transaction.commit()
                    return
                }
            }
        } else {
            transaction.replace(R.id.main_fragment, ConversationListFragment())
        }
        if (twoPane() && secondaryFragment == null) {
            transaction.replace(R.id.secondary_fragment, ConversationFragment())
        }
        transaction.commit()
    }

    /**
     * The bar's title block, where the deleted `invalidateActionBarTitle` wrote four views. The two
     * panes' branches are kept whole: a conversation in the *main* pane is the phone's single pane
     * and takes the back arrow (gated on the deleted cheogram onboarding test), while a conversation
     * in the *secondary* pane leaves the drawer handle where the owner's `show_nav_drawer` put it -
     * which is what the deleted method's own early `return` did.
     *
     * <p>The typing line's `noto_sans_bold_italic` is carried as [ConversationListChromeState.subtitleEmphasis];
     * the deleted bar's `isSelected` marquee is not, so a subtitle too long for the bar ellipsizes
     * instead of scrolling. That is chrome, not a decision.
     */
    private fun invalidateActionBarTitle() {
        val fragmentManager = fragmentManager
        val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
        val secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment)
        val mainConversation = (mainFragment as? ConversationFragment)?.getConversation()
        val secondaryConversation = (secondaryFragment as? ConversationFragment)?.getConversation()
        val conversation = mainConversation ?: secondaryConversation
        if (conversation == null) {
            titleConversation = null
            chromeState =
                chromeState.copy(
                    title = BuildConfig.APP_NAME,
                    subtitle = null,
                    subtitleEmphasis = false,
                    showAvatar = false,
                    titleClickable = false,
                    leading = drawerLeading(),
                )
            applyChrome()
            return
        }
        val subtitle = conversationSubtitle(conversation, emphasisAllowed = mainConversation == null)
        AvatarWorkerTask.loadAvatar(conversation, toolbarAvatarView, R.dimen.muc_avatar_actionbar)
        titleConversation = conversation
        chromeState =
            chromeState.copy(
                title = conversation.getName()?.toString() ?: "",
                subtitle = subtitle.first,
                subtitleEmphasis = subtitle.second,
                showAvatar = true,
                titleClickable = true,
                leading =
                    if (mainConversation != null) {
                        // The deleted `setDisplayHomeAsUpEnabled(!onboarding || jid != cheogram.com)`
                        // was the arrow's one gate.
                        if (!xmppConnectionService.isOnboarding() ||
                            conversation.getJid() != Jid.of("cheogram.com")
                        ) {
                            ConversationLeading.BACK
                        } else {
                            ConversationLeading.NONE
                        }
                    } else {
                        drawerLeading()
                    },
            )
        applyChrome()
    }

    /** The deleted `show_nav_drawer` branch: the drawer handle, or no leading icon at all. */
    private fun drawerLeading(): ConversationLeading =
        if (getBooleanPreference("show_nav_drawer", R.bool.show_nav_drawer)) {
            ConversationLeading.DRAWER
        } else {
            ConversationLeading.NONE
        }

    /**
     * The bar's second line, and whether it is the deleted bar's bold-italic typing line.
     *
     * @param emphasisAllowed whether this conversation is the secondary pane's: the deleted method
     *     set `noto_sans_bold_italic` only from that branch.
     */
    private fun conversationSubtitle(conversation: Conversation, emphasisAllowed: Boolean): Pair<String?, Boolean> {
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            if (conversation.withSelf()) {
                return Pair(null, false)
            }
            val state = conversation.getIncomingChatState()
            if (state == ChatState.COMPOSING) {
                return Pair(getString(R.string.is_typing), emphasisAllowed)
            }
            if (showLastSeen &&
                conversation.getContact().getLastseen() > 0 &&
                conversation.getContact().getPresences().allOrNonSupport(Namespace.IDLE)
            ) {
                return Pair(
                    UIHelper.lastseen(
                        applicationContext,
                        conversation.getContact().isActive(),
                        conversation.getContact().getLastseen(),
                    ),
                    false,
                )
            }
            return Pair(null, false)
        }
        var state = ChatState.COMPOSING
        var userWithChatStates = conversation.getMucOptions().getUsersWithChatState(state, 5)
        if (userWithChatStates.isEmpty()) {
            state = ChatState.PAUSED
            userWithChatStates = conversation.getMucOptions().getUsersWithChatState(state, 5)
        }
        val users = conversation.getMucOptions().getUsers(true)
        if (state == ChatState.COMPOSING) {
            if (userWithChatStates.isEmpty()) {
                // Unreachable: `state` is COMPOSING only while that list was non-empty. The deleted
                // method left the previous line in place here; drawing nothing is the closest a
                // state-driven bar can come to that.
                return Pair(null, false)
            }
            if (userWithChatStates.size == 1) {
                val user = userWithChatStates[0]
                return Pair(getString(R.string.contact_is_typing, UIHelper.getDisplayName(user)), false)
            }
            val builder = StringBuilder()
            for (user in userWithChatStates) {
                if (builder.isNotEmpty()) {
                    builder.append(", ")
                }
                builder.append(UIHelper.getDisplayName(user))
            }
            return Pair(getString(R.string.contacts_are_typing, builder.toString()), false)
        }
        return if (users.isEmpty()) {
            Pair(getString(R.string.one_participant), false)
        } else {
            Pair(getString(R.string.more_participants, users.size), false)
        }
    }

    private fun openConversationDetails(conversation: Conversation) {
        if (conversation.getMode() == Conversational.MODE_MULTI) {
            ConferenceDetailsActivity.open(this, conversation)
        } else {
            val contact = conversation.getContact()
            if (contact.isSelf()) {
                switchToAccount(conversation.getAccount() ?: throw NullPointerException())
            } else {
                switchToContactDetails(contact)
            }
        }
    }

    // Tulkki: `verifyOtrSessionDialog` was here, with `verification_choices.xml`'s popup. Its only
    // caller was `ConversationFragment.clickToVerify` - the conversation's own snackbar action - so it
    // moved into the fragment that asked for it and the menu file is gone
    // (`ConversationFragment.verifyOtrSessionMenu`).

    override fun onConversationArchived(conversation: Conversation?) {
        if (performRedirectIfNecessary(conversation, false)) {
            return
        }
        val fragmentManager = fragmentManager
        val mainFragment = fragmentManager.findFragmentById(R.id.main_fragment)
        if (mainFragment is ConversationFragment) {
            try {
                fragmentManager.popBackStack()
            } catch (e: IllegalStateException) {
                Log.w(
                    Config.LOGTAG,
                    "state loss while popping back state after archiving conversation",
                    e,
                )
                // this usually means activity is no longer active; meaning on the next open we will
                // run through this again
            }
            return
        }
        val secondaryFragment =
            fragmentManager.findFragmentById(R.id.secondary_fragment)
        if (secondaryFragment is ConversationFragment) {
            if (secondaryFragment.getConversation() === conversation) {
                // The uuid the fragment answers is resolved to its entity here, as at the other site.
                // The Kotlin parameter is nullable (the Java's was a platform type) and the Java
                // dereferenced it at this one site, so the guard is the Java's own NPE.
                val uuid = (conversation ?: throw NullPointerException()).getUuid()
                val suggestion = ConversationListFragment.suggestionUuid(this, uuid)
                val next =
                    if (suggestion == null || xmppConnectionService == null) {
                        null
                    } else {
                        xmppConnectionService.findConversationByUuid(suggestion) as Conversation?
                    }
                if (next != null) {
                    openConversation(next, null)
                }
            }
        }
    }

    override fun onConversationListItemUpdated() {
        val fragment = fragmentManager.findFragmentById(R.id.main_fragment)
        if (fragment is ConversationListFragment) {
            fragment.refresh()
        }
    }

    override fun switchToConversation(conversation: Conversation?) {
        Log.d(Config.LOGTAG, "override")
        if (conversation == null) return
        openConversation(conversation, null)
    }

    override fun onConversationRead(conversation: Conversation, upToUuid: String) {
        if (!mActivityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation, upToUuid)
        } else {
            Log.d(Config.LOGTAG, "ignoring read callback. mActivityPaused=$mActivityPaused")
        }
    }

    override fun onAccountUpdate() {
        refreshAccounts = true
        refreshUi()
    }

    override fun onConversationUpdate(newCaps: Boolean) {
        if (performRedirectIfNecessary(false)) {
            return
        }
        refreshForNewCaps = newCaps
        refreshUi()
    }

    // Tulkki: 3.7 pair 9, part 15 - the island's ref, written fully qualified with no import
    // (rounds 151/161); `getJid()` is the only member it reads and the ref declares it.
    override fun onRosterUpdate(
        reason: uk.xa0.tulkki.xmpp.services.UpdateRosterReason,
        contact: uk.xa0.tulkki.xmpp.refs.ContactRef?,
    ) {
        if (reason != uk.xa0.tulkki.xmpp.services.UpdateRosterReason.AVATAR) {
            refreshForNewCaps = true
            if (contact != null) newCapsJids.add(contact.getJid().asBareJid())
        }
        refreshUi()
    }

    override fun OnUpdateBlocklist(status: OnUpdateBlocklist.Status) {
        refreshUi()
    }

    override fun onShowErrorToast(resId: Int) {
        runOnUiThread { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }
    }

    private val backPressedCallback: OnBackPressedCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val fm = fragmentManager
                if (Build.VERSION.SDK_INT >= 26) {
                    val f = fm.fragments[fm.fragments.size - 1]
                    if (f != null && f is ConversationFragment) {
                        if (f.onBackPressed()) {
                            return
                        }
                    }
                }
                if (fm.backStackEntryCount > 0) {
                    try {
                        fm.popBackStack()
                    } catch (e: IllegalStateException) {
                        Log.w(Config.LOGTAG, "Unable to pop back stack in OnBackPressedCallback")
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }

    fun getPinnedMessageRepository(): PinnedMessageRepository {
        if (pinnedMessageRepositoryBacking == null) {
            pinnedMessageRepositoryBacking = PinnedMessageRepository(this)
        }
        return pinnedMessageRepositoryBacking ?: throw NullPointerException()
    }

    override fun onRoomDestroySucceeded() {
        val conversation = ConversationLookup.conversationReliable(this)
        val groupChat = conversation != null && conversation.isPrivateAndNonAnonymous()
        displayToast(getString(if (groupChat) R.string.destroy_room_succeed else R.string.destroy_channel_succeed))
    }

    override fun onRoomDestroyFailed() {
        val conversation = ConversationLookup.conversationReliable(this)
        val groupChat = conversation != null && conversation.isPrivateAndNonAnonymous()
        displayToast(getString(if (groupChat) R.string.destroy_room_failed else R.string.destroy_channel_failed))
    }

    companion object {
        // Tulkki: the action and the extra key below keep their pre-rename text on purpose - a posted
        // notification is not cancelled by a package replace, so a PendingIntent built by the previous
        // build comes back with the old action and the old extra key. Renaming either one means the tap
        // is not recognised as a view intent (isViewOrShareIntent, below) and the download action reads
        // null. They are data the OS holds, not names the compiler resolves; see docs/MIGRATION.md "The
        // literal audit" F4.
        const val ACTION_VIEW_CONVERSATION = "eu.siacs.conversations.action.VIEW"
        const val EXTRA_CONVERSATION = "conversationUuid"
        const val EXTRA_DOWNLOAD_UUID = "eu.siacs.conversations.download_uuid"
        const val EXTRA_AS_QUOTE = "uk.xa0.app.as_quote"
        const val EXTRA_NICK = "nick"
        const val EXTRA_IS_PRIVATE_MESSAGE = "pm"
        const val EXTRA_DO_NOT_APPEND = "do_not_append"
        const val EXTRA_POST_INIT_ACTION = "post_init_action"
        const val POST_ACTION_RECORD_VOICE = "record_voice"
        const val EXTRA_THREAD = "threadId"
        const val EXTRA_TYPE = "type"
        const val EXTRA_NODE = "node"
        const val EXTRA_JID = "jid"
        const val EXTRA_MESSAGE_UUID = "messageUuid"
        const val ACTION_DESTROY_MUC = "uk.xa0.app.DESTROY_MUC"

        private val VIEW_AND_SHARE_ACTIONS =
            listOf(
                ACTION_VIEW_CONVERSATION,
                Intent.ACTION_SEND,
                Intent.ACTION_SEND_MULTIPLE,
            )

        const val REQUEST_OPEN_MESSAGE = 0x9876
        const val REQUEST_PLAY_PAUSE = 0x5432
        const val REQUEST_MICROPHONE = 0x5432f
        const val DIALLER_INTEGRATION = 0x5432ff

        const val DRAWER_ALL_CHATS = 1L
        const val DRAWER_UNREAD_CHATS = 2L
        const val DRAWER_DIRECT_MESSAGES = 3L
        const val DRAWER_MANAGE_ACCOUNT = 4L
        const val DRAWER_ADD_ACCOUNT = 5L
        const val DRAWER_MANAGE_PHONE_ACCOUNTS = 6L
        const val DRAWER_CHANNELS = 7L
        const val DRAWER_CHAT_REQUESTS = 8L
        const val DRAWER_SETTINGS = 9L
        const val DRAWER_START_CHAT = 10L
        const val DRAWER_START_CHAT_CONTACT = 11L
        const val DRAWER_START_CHAT_NEW = 12L
        const val DRAWER_START_CHAT_GROUP = 13L
        const val DRAWER_START_CHAT_PUBLIC = 14L
        const val DRAWER_START_CHAT_DISCOVER = 15L
        const val DRAWER_MEDIA_GALLERY = 16L

        // Tulkki: the shortcut to Tulkki's own settings. It is a destination rather than a filter, so it
        // is never selectable, and 17 is simply the first value the list above leaves free.
        const val DRAWER_TULKKI_SETTINGS = 17L

        /**
         * The profile rows' ids, where the deleted header's own `headerId` counter was: the row at
         * index `i` carries `PROFILE_ID_BASE + i`, and the pseudo-profile that means "every account"
         * is index 0 when there is more than one account. The base is the 100 the deleted
         * `ProfileDrawerItem` for "All accounts" carried.
         */
        private const val PROFILE_ID_BASE = 100L

        /**
         * The tag rows' ids, where the deleted adapter's `var id = 1000L` was: the tag at index `i`
         * carries `TAG_ID_BASE + i`, and the deleted `id >= 1000` test that told a tag from a filter
         * is [TAG_ID_BASE] itself.
         */
        private const val TAG_ID_BASE = 1000L

        /**
         * The `layout-w945dp` directory's own threshold: a `-w<N>dp` resource is chosen when the
         * device's **smallest screen width** is at least `N` dp. The two-pane arrangement and the
         * two `show_*_search_options` booleans both live under that qualifier in the resources, and
         * this is the number they are chosen by.
         */
        private const val WIDE_SCREEN_WIDTH_DP = 945

        // secondary fragment (when holding the conversation, must be initialized before refreshing the
        // overview fragment
        private val FRAGMENT_ID_NOTIFICATION_ORDER =
            intArrayOf(
                R.id.secondary_fragment,
                R.id.main_fragment,
            )

        private fun isViewOrShareIntent(i: Intent?): Boolean {
            Log.d(Config.LOGTAG, "action: " + i?.action)
            val action = i?.action
            return i != null &&
                action != null &&
                VIEW_AND_SHARE_ACTIONS.contains(action) &&
                i.hasExtra(EXTRA_CONVERSATION)
        }

        private fun createLauncherIntent(context: Context): Intent {
            val intent = Intent(context, ConversationListActivity::class.java)
            intent.setAction(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            return intent
        }

        private fun executePendingTransactions(fragmentManager: FragmentManager) {
            try {
                fragmentManager.executePendingTransactions()
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "unable to execute pending fragment transactions")
            }
        }
    }
}
