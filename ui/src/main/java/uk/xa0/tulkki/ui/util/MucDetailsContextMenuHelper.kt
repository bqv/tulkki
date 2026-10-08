package uk.xa0.tulkki.ui.util

import android.app.Activity
import android.preference.PreferenceManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Pair
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.ArrayList
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.ui.ConferenceDetailsActivity
import uk.xa0.tulkki.ui.ConversationLookup
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.ModerateRecentDialog
import uk.xa0.tulkki.ui.MucUsersActivity
import uk.xa0.tulkki.ui.R
import uk.xa0.tulkki.ui.XmppActivity
import uk.xa0.tulkki.ui.dialogs.showTulkkiDialog
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

object MucDetailsContextMenuHelper {
    private const val ACTION_BAN = 0
    private const val ACTION_GRANT_MEMBERSHIP = 1
    private const val ACTION_REMOVE_MEMBERSHIP = 2
    private const val ACTION_GRANT_ADMIN = 3
    private const val ACTION_REMOVE_ADMIN = 4
    private const val ACTION_GRANT_OWNER = 5
    private const val ACTION_REMOVE_OWNER = 6

    @JvmStatic
    fun getPermissionsChoices(
        activity: Activity,
        conversation: Conversation,
        user: MucOptions.User,
    ): Pair<Array<CharSequence>, Array<Int>> {
        val items = ArrayList<CharSequence>()
        val actions = ArrayList<Int>()
        val self = conversation.getMucOptions().getSelf()
        val mucOptions = conversation.getMucOptions()
        val isGroupChat = mucOptions.isPrivateAndNonAnonymous()
        if ((self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN) && self.getAffiliation().outranks(user.getAffiliation())) ||
            self.getAffiliation() == MucOptions.Affiliation.OWNER
        ) {
            if (!Config.DISABLE_BAN && user.getAffiliation() != MucOptions.Affiliation.OUTCAST) {
                items.add(activity.getString(if (isGroupChat) R.string.ban_from_conference else R.string.ban_from_channel))
                actions.add(ACTION_BAN)
            } else if (!Config.DISABLE_BAN) {
                items.add(if (isGroupChat) activity.getString(R.string.unban_from_group_chat) else activity.getString(R.string.unban_from_channel))
                actions.add(ACTION_REMOVE_MEMBERSHIP)
            }
            if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                items.add(activity.getString(R.string.grant_membership))
                actions.add(ACTION_GRANT_MEMBERSHIP)
            } else if (user.getAffiliation() == MucOptions.Affiliation.MEMBER) {
                items.add(activity.getString(R.string.remove_membership))
                actions.add(ACTION_REMOVE_MEMBERSHIP)
            }
        }
        if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
            if (!user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)) {
                items.add(activity.getString(R.string.grant_admin_privileges))
                actions.add(ACTION_GRANT_ADMIN)
            } else if (user.getAffiliation() == MucOptions.Affiliation.ADMIN) {
                items.add(activity.getString(R.string.remove_admin_privileges))
                actions.add(ACTION_REMOVE_ADMIN)
            }
            if (!user.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                items.add(activity.getString(R.string.grant_owner_privileges))
                actions.add(ACTION_GRANT_OWNER)
            } else if (user.getAffiliation() == MucOptions.Affiliation.OWNER) {
                items.add(activity.getString(R.string.remove_owner_privileges))
                actions.add(ACTION_REMOVE_OWNER)
            }
        }
        return Pair(items.toTypedArray(), actions.toTypedArray())
    }

    @JvmStatic
    fun configureMucDetailsContextMenu(
        activity: XmppActivity,
        menu: Menu,
        conversation: Conversation,
        user: MucOptions.User?,
    ) {
        val mucOptions = conversation.getMucOptions()
        val showMucPm = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("show_muc_pm", false)
        val isGroupChat = mucOptions.isPrivateAndNonAnonymous()
        val sendPrivateMessage = menu.findItem(R.id.send_private_message)
        val shareContactDetails = menu.findItem(R.id.share_contact_details)
        val showAvatar = menu.findItem(R.id.action_show_avatar)
        if (user != null && user.getAvatar() != null) {
            showAvatar.setVisible(true)
        }
        val blockAvatar = menu.findItem(R.id.action_block_avatar)
        if (user != null && user.getAvatar() != null) {
            blockAvatar.setVisible(true)
        }

        val muteParticipant = menu.findItem(R.id.action_mute_participant)
        val unmuteParticipant = menu.findItem(R.id.action_unmute_participant)
        if (user != null && user.getOccupantId() != null) {
            if (activity.xmppConnectionService.isMucUserMuted(user)) {
                unmuteParticipant.setVisible(true)
            } else {
                muteParticipant.setVisible(true)
            }
        }

        if (user != null && user.getRealJid() != null) {
            val showContactDetails = menu.findItem(R.id.action_contact_details)
            val startConversation = menu.findItem(R.id.start_conversation)
            val removeFromRoom = menu.findItem(R.id.remove_from_room)
            val managePermissions = menu.findItem(R.id.manage_permissions)
            removeFromRoom.setTitle(if (isGroupChat) R.string.remove_from_room else R.string.remove_from_channel)
            val invite = menu.findItem(R.id.invite)
            startConversation.setVisible(true)
            val contact = user.getContact()
            val self = conversation.getMucOptions().getSelf()
            if ((contact != null && contact.showInRoster()) || mucOptions.isPrivateAndNonAnonymous()) {
                showContactDetails.setVisible(contact == null || !contact.isSelf())
            }
            if ((activity is ConferenceDetailsActivity || activity is MucUsersActivity) && user.getRole() == MucOptions.Role.NONE) {
                invite.setVisible(true)
            }
            var managePermissionsVisible = false
            if ((self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN) && self.getAffiliation().outranks(user.getAffiliation())) ||
                self.getAffiliation() == MucOptions.Affiliation.OWNER
            ) {
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                    managePermissionsVisible = true
                } else if (user.getAffiliation() == MucOptions.Affiliation.MEMBER) {
                    managePermissionsVisible = true
                }
                if (!Config.DISABLE_BAN) {
                    managePermissionsVisible = true
                }
            }
            if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                    managePermissionsVisible = true
                } else if (user.getAffiliation() == MucOptions.Affiliation.OWNER) {
                    managePermissionsVisible = true
                }
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)) {
                    managePermissionsVisible = true
                } else if (user.getAffiliation() == MucOptions.Affiliation.ADMIN) {
                    managePermissionsVisible = true
                }
            }
            managePermissions.setVisible(managePermissionsVisible)
            sendPrivateMessage.setVisible(showMucPm && user.isOnline() && !isGroupChat && mucOptions.allowPm() && user.getRole().ranks(MucOptions.Role.VISITOR))
            shareContactDetails.setVisible(user.isOnline() && !isGroupChat && mucOptions.allowPm() && user.getRole().ranks(MucOptions.Role.VISITOR))
        } else {
            sendPrivateMessage.setVisible(showMucPm && user != null && user.isOnline())
            sendPrivateMessage.setEnabled(user != null && mucOptions.allowPm() && user.getRole().ranks(MucOptions.Role.VISITOR))
            shareContactDetails.setVisible(user != null && user.isOnline())
            shareContactDetails.setEnabled(user != null && mucOptions.allowPm() && user.getRole().ranks(MucOptions.Role.VISITOR))
        }
    }

    /**
     * The live entries of a participant's menu, in `muc_details_context.xml`'s own order.
     *
     * <p>This is the Compose twin of [configureMucDetailsContextMenu]: same conditions, same order,
     * expressed as a list instead of a `Menu`. The XML left every item invisible and the configuring
     * method turned some on; this returns exactly the turned-on ones. The two stay side by side while
     * `ConversationFragment` still inflates the menu file (it is that lane's to convert), and the
     * XML-backed method goes with the file.
     */
    @JvmStatic
    fun visibleEntries(
        activity: XmppActivity,
        conversation: Conversation,
        user: MucOptions.User?,
    ): List<MucDetailsEntry> {
        val entries = ArrayList<MucDetailsEntry>()
        val mucOptions = conversation.getMucOptions()
        val isGroupChat = mucOptions.isPrivateAndNonAnonymous()
        val showMucPm =
            PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("show_muc_pm", false)
        val self = mucOptions.getSelf()
        val contact = user?.getContact()
        val hasRealJid = user != null && user.getRealJid() != null
        val hasAvatar = user != null && user.getAvatar() != null
        val hasOccupantId = user != null && user.getOccupantId() != null
        val muted =
            user != null &&
                user.getOccupantId() != null &&
                activity.xmppConnectionService.isMucUserMuted(user)

        // XML order: start, show avatar, contact details, block avatar, mute, unmute, invite,
        // send private message, share contact details, manage permissions.
        if (hasRealJid) {
            entries.add(MucDetailsEntry(MucDetailsAction.START_CONVERSATION, R.string.start_chat))
        }
        if (hasAvatar) {
            entries.add(MucDetailsEntry(MucDetailsAction.SHOW_AVATAR, R.string.show_avatar))
        }
        if (hasRealJid &&
            ((contact != null && contact.showInRoster()) || isGroupChat) &&
            (contact == null || !contact.isSelf())
        ) {
            entries.add(
                MucDetailsEntry(MucDetailsAction.CONTACT_DETAILS, R.string.action_contact_details)
            )
        }
        if (hasAvatar) {
            entries.add(MucDetailsEntry(MucDetailsAction.BLOCK_AVATAR, R.string.block_avatar))
        }
        if (hasOccupantId && !muted) {
            entries.add(MucDetailsEntry(MucDetailsAction.MUTE_PARTICIPANT, R.string.mute_locally))
        }
        if (hasOccupantId && muted) {
            entries.add(MucDetailsEntry(MucDetailsAction.UNMUTE_PARTICIPANT, R.string.unmute_locally))
        }
        if (hasRealJid &&
            (activity is ConferenceDetailsActivity || activity is MucUsersActivity) &&
            user != null &&
            user.getRole() == MucOptions.Role.NONE
        ) {
            entries.add(MucDetailsEntry(MucDetailsAction.INVITE, R.string.invite_again))
        }
        if (hasRealJid && user != null) {
            if (showMucPm &&
                user.isOnline() &&
                !isGroupChat &&
                mucOptions.allowPm() &&
                user.getRole().ranks(MucOptions.Role.VISITOR)
            ) {
                entries.add(
                    MucDetailsEntry(
                        MucDetailsAction.SEND_PRIVATE_MESSAGE,
                        R.string.send_private_message,
                    )
                )
            }
            if (user.isOnline() &&
                !isGroupChat &&
                mucOptions.allowPm() &&
                user.getRole().ranks(MucOptions.Role.VISITOR)
            ) {
                entries.add(
                    MucDetailsEntry(
                        MucDetailsAction.SHARE_CONTACT_DETAILS,
                        R.string.invite_to_start_chat,
                    )
                )
            }
        } else {
            if (user != null && showMucPm && user.isOnline()) {
                entries.add(
                    MucDetailsEntry(
                        MucDetailsAction.SEND_PRIVATE_MESSAGE,
                        R.string.send_private_message,
                        enabled =
                            mucOptions.allowPm() &&
                                user.getRole().ranks(MucOptions.Role.VISITOR),
                    )
                )
            }
            if (user != null && user.isOnline()) {
                entries.add(
                    MucDetailsEntry(
                        MucDetailsAction.SHARE_CONTACT_DETAILS,
                        R.string.invite_to_start_chat,
                        enabled =
                            mucOptions.allowPm() &&
                                user.getRole().ranks(MucOptions.Role.VISITOR),
                    )
                )
            }
        }
        if (hasRealJid && user != null) {
            var managePermissionsVisible = false
            if ((self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN) &&
                    self.getAffiliation().outranks(user.getAffiliation())) ||
                self.getAffiliation() == MucOptions.Affiliation.OWNER
            ) {
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER) ||
                    user.getAffiliation() == MucOptions.Affiliation.MEMBER
                ) {
                    managePermissionsVisible = true
                }
                if (!Config.DISABLE_BAN) {
                    managePermissionsVisible = true
                }
            }
            if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.OWNER) ||
                    user.getAffiliation() == MucOptions.Affiliation.OWNER
                ) {
                    managePermissionsVisible = true
                }
                if (!user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN) ||
                    user.getAffiliation() == MucOptions.Affiliation.ADMIN
                ) {
                    managePermissionsVisible = true
                }
            }
            if (managePermissionsVisible) {
                entries.add(
                    MucDetailsEntry(MucDetailsAction.MANAGE_PERMISSIONS, R.string.manage_permission)
                )
            }
        }
        return entries
    }

    @JvmStatic
    fun onContextItemSelected(item: MenuItem, user: MucOptions.User, activity: XmppActivity): Boolean {
        return onContextItemSelected(item, user, activity, null)
    }

    @JvmStatic
    fun maybeModerateRecent(activity: XmppActivity, conversation: Conversation, user: MucOptions.User) {
        if (!conversation.getMucOptions().getSelf().getRole().ranks(MucOptions.Role.MODERATOR) ||
            !conversation.getMucOptions().hasFeature("urn:xmpp:message-moderate:0")
        ) return

        // `dialog_quickedit.xml` is gone; the field it held is `ModerateRecentDialog`'s, prefilled
        // with the same "spam" and sent as the reason for every message. The builder's alert is the
        // dialog's own title, question and Yes/No, and answering Yes dismisses it before the send,
        // exactly as the builder's button did.
        activity.showTulkkiDialog { dismiss ->
            ModerateRecentDialog(
                onDismiss = dismiss,
                onModerate = { reason ->
                    dismiss()
                    for (m in conversation.findMessagesBy(user)) {
                        activity.xmppConnectionService.moderateMessage(
                            conversation.getAccount() ?: throw NullPointerException(),
                            m,
                            reason,
                        )
                    }
                },
            )
        }
    }

    /** The XML menu-id to verb map; it exists only while `muc_details_context.xml` does. */
    private fun actionOf(menuId: Int): MucDetailsAction? =
        when (menuId) {
            R.id.start_conversation -> MucDetailsAction.START_CONVERSATION
            R.id.action_show_avatar -> MucDetailsAction.SHOW_AVATAR
            R.id.action_contact_details -> MucDetailsAction.CONTACT_DETAILS
            R.id.action_block_avatar -> MucDetailsAction.BLOCK_AVATAR
            R.id.action_mute_participant -> MucDetailsAction.MUTE_PARTICIPANT
            R.id.action_unmute_participant -> MucDetailsAction.UNMUTE_PARTICIPANT
            R.id.invite -> MucDetailsAction.INVITE
            R.id.send_private_message -> MucDetailsAction.SEND_PRIVATE_MESSAGE
            R.id.share_contact_details -> MucDetailsAction.SHARE_CONTACT_DETAILS
            R.id.manage_permissions -> MucDetailsAction.MANAGE_PERMISSIONS
            R.id.remove_from_room -> MucDetailsAction.REMOVE_FROM_ROOM
            else -> null
        }

    /** The same map the other way, so the dispatch body below still reads one `menuId`. */
    private fun menuIdOf(action: MucDetailsAction): Int =
        when (action) {
            MucDetailsAction.START_CONVERSATION -> R.id.start_conversation
            MucDetailsAction.SHOW_AVATAR -> R.id.action_show_avatar
            MucDetailsAction.CONTACT_DETAILS -> R.id.action_contact_details
            MucDetailsAction.BLOCK_AVATAR -> R.id.action_block_avatar
            MucDetailsAction.MUTE_PARTICIPANT -> R.id.action_mute_participant
            MucDetailsAction.UNMUTE_PARTICIPANT -> R.id.action_unmute_participant
            MucDetailsAction.INVITE -> R.id.invite
            MucDetailsAction.SEND_PRIVATE_MESSAGE -> R.id.send_private_message
            MucDetailsAction.SHARE_CONTACT_DETAILS -> R.id.share_contact_details
            MucDetailsAction.MANAGE_PERMISSIONS -> R.id.manage_permissions
            MucDetailsAction.REMOVE_FROM_ROOM -> R.id.remove_from_room
        }

    /**
     * The XML menu's selection, resolved to a verb and run: the `Menu` world's one entry point, kept
     * while `ConversationFragment` still inflates the file.
     */
    @JvmStatic
    fun onContextItemSelected(
        item: MenuItem,
        user: MucOptions.User,
        activity: XmppActivity,
        fingerprint: String?,
    ): Boolean {
        return onMucDetailsAction(
            actionOf(item.getItemId()) ?: return false,
            user,
            activity,
            fingerprint,
        )
    }

    /** One of the participant menu's verbs, run. */
    @JvmStatic
    fun onMucDetailsAction(
        action: MucDetailsAction,
        user: MucOptions.User,
        activity: XmppActivity,
        fingerprint: String?,
    ): Boolean {
        val conversation = user.getConversation()
        val onAffiliationChanged = if (activity is uk.xa0.tulkki.xmpp.services.OnAffiliationChanged) activity else null
        val jid = user.getRealJid()
        val menuId = menuIdOf(action)
        if (menuId == R.id.action_show_avatar) {
            activity.ShowAvatarPopup(user)
            return true
        } else if (menuId == R.id.action_contact_details) {
            val realJid = user.getRealJid()
            val account = conversation.getAccount() ?: throw NullPointerException()
            val contact = if (realJid == null) null else account.getRoster().getContact(realJid)
            if (contact != null) {
                activity.switchToContactDetails(contact, fingerprint)
            }
            return true
        } else if (menuId == R.id.action_block_avatar) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.block_media)
                .setMessage(R.string.block_avatar_question)
                .setPositiveButton(uk.xa0.tulkki.data.R.string.yes) { dialog, whichButton ->
                    activity.xmppConnectionService.blockMedia(
                        FileBackends.get().getAvatarFile(user.getAvatar()),
                    )
                    FileBackends.get().getAvatarFile(user.getAvatar()).delete()
                    activity.avatarService().clear(user)
                    val contact = user.getContact()
                    if (contact != null) activity.avatarService().clear(contact)
                    user.setAvatar(null)
                    activity.xmppConnectionService.updateConversationUi()
                }
                .setNegativeButton(uk.xa0.tulkki.data.R.string.no, null)
                .show()
            return true
        } else if (menuId == R.id.action_mute_participant) {
            if (activity.xmppConnectionService.muteMucUser(user)) {
                activity.xmppConnectionService.updateConversationUi()
            } else {
                Toast.makeText(activity, "Failed to mute", Toast.LENGTH_SHORT).show()
            }
            return true
        } else if (menuId == R.id.action_unmute_participant) {
            if (activity.xmppConnectionService.unmuteMucUser(user)) {
                activity.xmppConnectionService.updateConversationUi()
            } else {
                Toast.makeText(activity, "Failed to unmute", Toast.LENGTH_SHORT).show()
            }
            return true
        } else if (menuId == R.id.start_conversation) {
            startConversation(user, activity)
            return true
        } else if (menuId == R.id.manage_permissions) {
            val choices = getPermissionsChoices(activity, conversation, user)
            val selected = intArrayOf(-1)
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.manage_permission_with_nick, UIHelper.getDisplayName(user)))
                .setSingleChoiceItems(choices.first, -1) { dialog, whichItem ->
                    selected[0] = whichItem
                }
                .setPositiveButton(R.string.action_complete) { dialog, whichButton ->
                    val realJid = jid ?: throw NullPointerException()
                    when (if (selected[0] >= 0) choices.second[selected[0]] else -1) {
                        ACTION_BAN -> {
                            activity.xmppConnectionService.changeAffiliationInConference(
                                conversation,
                                realJid,
                                uk.xa0.tulkki.xmpp.refs.MucOptionsRef.AffiliationRef.OUTCAST,
                                onAffiliationChanged,
                            )
                            if (user.getRole() != MucOptions.Role.NONE) {
                                activity.xmppConnectionService.changeRoleInConference(
                                    conversation,
                                    user.getName() ?: throw NullPointerException(),
                                    uk.xa0.tulkki.xmpp.refs.MucOptionsRef.RoleRef.NONE,
                                )
                            }
                            maybeModerateRecent(activity, conversation, user)
                        }
                        ACTION_GRANT_MEMBERSHIP, ACTION_REMOVE_ADMIN, ACTION_REMOVE_OWNER -> {
                            activity.xmppConnectionService.changeAffiliationInConference(
                                conversation,
                                realJid,
                                uk.xa0.tulkki.xmpp.refs.MucOptionsRef.AffiliationRef.MEMBER,
                                onAffiliationChanged,
                            )
                        }
                        ACTION_GRANT_ADMIN -> {
                            activity.xmppConnectionService.changeAffiliationInConference(
                                conversation,
                                realJid,
                                uk.xa0.tulkki.xmpp.refs.MucOptionsRef.AffiliationRef.ADMIN,
                                onAffiliationChanged,
                            )
                        }
                        ACTION_GRANT_OWNER -> {
                            activity.xmppConnectionService.changeAffiliationInConference(
                                conversation,
                                realJid,
                                uk.xa0.tulkki.xmpp.refs.MucOptionsRef.AffiliationRef.OWNER,
                                onAffiliationChanged,
                            )
                        }
                        ACTION_REMOVE_MEMBERSHIP -> {
                            activity.xmppConnectionService.changeAffiliationInConference(
                                conversation,
                                realJid,
                                uk.xa0.tulkki.xmpp.refs.MucOptionsRef.AffiliationRef.NONE,
                                onAffiliationChanged,
                            )
                        }
                    }
                }
                .setNeutralButton(uk.xa0.tulkki.data.R.string.cancel, null)
                .show()
            return true
        } else if (menuId == R.id.remove_from_room) {
            removeFromRoom(user, activity, onAffiliationChanged)
            return true
        } else if (menuId == R.id.send_private_message) {
            if (activity is ConversationListActivity) {
                val conversationFragment = ConversationLookup.get(activity)
                if (conversationFragment != null) {
                    conversationFragment.privateMessageWith(user.getFullJid())
                    return true
                }
            }
            activity.privateMsgInMuc(conversation, user.getName())
            return true
        } else if (menuId == R.id.share_contact_details) {
            val message = Message(
                conversation,
                "/me invites you to chat " + (conversation.getAccount() ?: throw NullPointerException()).getShareableUri(),
                conversation.getNextEncryption(),
            )
            Message.configurePrivateMessage(message, user.getFullJid())
            /* This triggers a gajim bug right now https://dev.gajim.org/gajim/gajim/-/issues/11900
            final var rosterx = new Element("x", "http://jabber.org/protocol/rosterx");
            final var ritem = rosterx.addChild("item");
            ritem.setAttribute("action", "add");
            ritem.setAttribute("name", conversation.getMucOptions().getSelf().getNick());
            ritem.setAttribute("jid", conversation.getAccount().getJid().asBareJid().toString());
            message.addPayload(rosterx);*/
            activity.xmppConnectionService.sendMessage(message)
            return true
        } else if (menuId == R.id.invite) {
            if (user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                activity.xmppConnectionService.directInvite(
                    conversation,
                    (jid ?: throw NullPointerException()).asBareJid(),
                )
            } else {
                activity.xmppConnectionService.invite(conversation, jid ?: throw NullPointerException())
            }
            return true
        } else {
            return false
        }
    }

    private fun removeFromRoom(
        user: MucOptions.User,
        activity: XmppActivity,
        onAffiliationChanged: uk.xa0.tulkki.xmpp.services.OnAffiliationChanged?,
    ) {
        val conversation = user.getConversation()
        if (conversation.getMucOptions().membersOnly()) {
            activity.xmppConnectionService.changeAffiliationInConference(
                conversation,
                user.getRealJid() ?: throw NullPointerException(),
                uk.xa0.tulkki.xmpp.refs.MucOptionsRef.AffiliationRef.NONE,
                onAffiliationChanged,
            )
            if (user.getRole() != MucOptions.Role.NONE) {
                activity.xmppConnectionService.changeRoleInConference(
                    conversation,
                    user.getName() ?: throw NullPointerException(),
                    uk.xa0.tulkki.xmpp.refs.MucOptionsRef.RoleRef.NONE,
                )
            }
        } else {
            val builder = MaterialAlertDialogBuilder(activity)
            builder.setTitle(R.string.ban_from_conference)
            val jid = (user.getRealJid() ?: throw NullPointerException()).asBareJid().toString()
            val message = SpannableString(activity.getString(R.string.removing_from_public_conference, jid))
            val start = message.toString().indexOf(jid)
            if (start >= 0) {
                message.setSpan(TypefaceSpan("monospace"), start, start + jid.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            builder.setMessage(message)
            builder.setNegativeButton(uk.xa0.tulkki.data.R.string.cancel, null)
            builder.setPositiveButton(R.string.ban_now) { dialog, which ->
                activity.xmppConnectionService.changeAffiliationInConference(
                    conversation,
                    user.getRealJid() ?: throw NullPointerException(),
                    uk.xa0.tulkki.xmpp.refs.MucOptionsRef.AffiliationRef.OUTCAST,
                    onAffiliationChanged,
                )
                if (user.getRole() != MucOptions.Role.NONE) {
                    activity.xmppConnectionService.changeRoleInConference(
                        conversation,
                        user.getName() ?: throw NullPointerException(),
                        uk.xa0.tulkki.xmpp.refs.MucOptionsRef.RoleRef.NONE,
                    )
                }
            }
            builder.create().show()
        }
    }

    private fun startConversation(user: MucOptions.User, activity: XmppActivity) {
        val realJid = user.getRealJid() ?: return
        val newConversation = activity.xmppConnectionService.findOrCreateConversation(
            user.getAccount(),
            realJid.asBareJid(),
            false,
            true,
        ) as Conversation
        activity.switchToConversation(newConversation)
    }
}
