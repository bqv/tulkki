package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.xmpp.OnAdvancedStreamFeaturesLoaded
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef

/**
 * Tulkki: the avatar cache, in island vocabulary.
 *
 * <p>It is the largest of pair 4's ports because it is the only carrier whose object the island
 * hands *out*: `:ui`, `:data` and `:app` all read an avatar through
 * {@link XmppConnectionService#getAvatarService()}, so the port has to answer every overload
 * they call. The one place this file used a *nested* type of the class - the {@code instanceof}
 * that decides a placeholder's cache size - needs no member at all: pair 3 moved that marker to
 * {@code uk.xa0.tulkki.libs.TextAvatar}, which an island may name, so the test below names the marker.
 */
interface AvatarPort : OnAdvancedStreamFeaturesLoaded {

    fun get(avatarable: Avatarable, size: Int, cachedOnly: Boolean): Drawable?

    // Tulkki: 3.7 C5-E2 **deleted** `Drawable get(MucOptions.User, int, boolean)`, and deletion -
    // not a retype - is the only correct choice here. `MucOptions.User implements Avatarable,
    // MucOptionsRef.UserRef` and the two are unrelated types (`Avatarable` is `uk.xa0.tulkki.libs`'
    // marker), so a `UserRef` overload
    // sitting beside `get(Avatarable, int, boolean)` above makes every caller holding a *model*
    // `MucOptions.User` ambiguous and javac rejects it - the same trap `getMessageAvatar` was
    // renamed around. No island caller holds a `UserRef` and asks for a drawable: every
    // `getAvatarService().get(` in `:ui`/`:app`/`:data` passes a contact, a conversation, an
    // account, a name or a message. If a `UserRef` consumer ever arrives, the device is a
    // distinctly-named `getUserAvatar(UserRef, ...)`, never a retype.

    /**
     * Tulkki: port-13 retired `ListItemRef` here, and 2026-10-08 retired `AvatarableRef` with
     * it. The arity-2 member takes `uk.xa0.tulkki.libs.Avatarable` itself now - the type moved to the
     * one module both the island and `:ui` may name, which is what let the ref go rather than
     * replace it - so `ListItem`, which implements `Avatarable`, satisfies it with nothing
     * between. The one caller - `StoryAdapter:108`, holding a `Contact` - selects this member
     * unchanged and
     * the adapter casts back to the `ListItem` whose avatar path differs. An arity-3
     * `get(ListItemRef, int, boolean)` was deleted earlier for having no caller at all; do not
     * re-add either as a "missing overload".
     */
    fun get(item: Avatarable, size: Int): Drawable?

    /**
     * Tulkki: **distinctly named on purpose** - the third member of the family
     * {@link #getAccountAvatar} and {@link #getMessageAvatar} opened, and the reason is a
     * class of fact rather than a style choice. `Conversation` implements both `Avatarable`,
     * which is `uk.xa0.tulkki.libs`' marker, and {@link ConversationRef}, and those two types are
     * unrelated, so a call with a *model* `Conversation` in hand matches this and
     * {@link #get(Avatarable, int, boolean)} equally and javac refuses it as ambiguous. No
     * call site passed a model `Conversation` with three arguments, so the trap was latent and
     * loud rather than live; it is renamed anyway, as prevention, because the next such caller
     * would have been the one to discover it. Both arities move together, exactly as the two
     * `getAccountAvatar` overloads did, so the port's `get` set holds no conversation at all.
     */
    fun getConversationAvatar(conversation: ConversationRef, size: Int): Drawable?

    fun getConversationAvatar(conversation: ConversationRef, size: Int, cachedOnly: Boolean): Drawable?

    /**
     * Tulkki: C5-E1 - **distinctly named on purpose**, and this is the case the two comments
     * around it predicted rather than a style choice. Retyping the parameter to {@link AccountRef}
     * is not enough: the model `Account` implements `Avatarable` and `AccountRef` is a
     * different type, so a call with a *model* `Account`
     * in hand matches this and {@link #get(Avatarable, int, boolean)} equally and javac
     * refuses it as ambiguous. Two `:ui` call sites (`ConversationListActivity:446`,
     * `MessageAdapter:3708`) proved it on the first compile. The name moves instead - the same
     * device {@link #getMessageAvatar} documents - so the port has no two members an `Account`
     * can select between.
     */
    fun getAccountAvatar(account: AccountRef, size: Int): Drawable?

    fun getAccountAvatar(account: AccountRef, size: Int, cachedOnly: Boolean): Drawable?

    /**
     * Tulkki: part 16 - **distinctly named on purpose**, and this is the shape rule the brief
     * warned about rather than a style choice. `Message` implements both `MessageRef` and
     * `Avatarable`, so once this member's parameter was the ref, a call with a *model* `Message`
     * in hand matched this and {@link #get(Avatarable, int, boolean)} equally and javac
     * refused it as ambiguous (`uk.xa0.tulkki.app.services.NotificationService`'s one call site). A cast
     * there would have papered over it for one caller and left the trap for the next; the name
     * moves instead, so the port has no two members a `Message` can select between.
     */
    fun getMessageAvatar(message: MessageRef, size: Int, cachedOnly: Boolean): Drawable?

    fun get(name: String?, seed: String?, size: Int, cachedOnly: Boolean): Drawable

    fun clear(contact: ContactRef)

    fun clear(conversation: ConversationRef)

    // Tulkki: 3.7 C5-E2 **deleted** `clear(MucOptions)` and `clear(MucOptions.User)`. Both had
    // ref twins right below (part 11), so keeping the model pair would have kept the import for
    // no caller; the ref twins are what every island site uses.

    /**
     * Tulkki: the ref, not the model type.
     *
     * <p>3.7 pair 9, the parameter half of cluster (f). The port is declared **here**, in the
     * island, and implemented by `:app`'s `AvatarAdapter` - so this signature change is an `:app`
     * edit and no `:ui` file is involved, which is worth stating because the prediction was that
     * this call site would be where the body-FQN ruling for `:ui` finally bites. It does not:
     * `:ui`'s own `UiHost.AvatarSource` has no `clear(Account)` overload at all.
     */
    fun clear(account: AccountRef)

    // -- part 11: the two overloads `PresenceParser` needs --------------------------------------
    //
    // The parser holds a `MucOptionsRef` and a `MucOptionsRef.UserRef` now, so the port has to
    // answer for those static types as well. Both objects really are the model's (the ref's whole
    // point is that `:data` implements it), so `:app`'s `AvatarAdapter` casts back once - and this
    // file is the island, so naming the refs here costs nothing.

    fun clear(options: MucOptionsRef?)

    fun clear(user: MucOptionsRef.UserRef)

    fun getRoundedShortcut(mucOptions: MucOptionsRef): Bitmap

    fun getRoundedShortcut(contact: ContactRef): Bitmap

    fun getRoundedShortcutWithIcon(contact: ContactRef): Bitmap

    fun systemUiAvatarSize(context: Context): Int
}
