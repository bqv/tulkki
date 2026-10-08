package uk.xa0.tulkki.app.services

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.util.LruCache
import androidx.annotation.Nullable
import androidx.core.content.res.ResourcesCompat
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import uk.xa0.tulkki.libs.Avatarable
import uk.xa0.tulkki.libs.TextAvatar
import uk.xa0.tulkki.app.R
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Bookmark
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.ListItem
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.data.model.RawBlockable
import uk.xa0.tulkki.data.model.Room
import uk.xa0.tulkki.data.view.AvatarReader
import uk.xa0.tulkki.ui.host.UiHost
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.OnAdvancedStreamFeaturesLoaded
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The process's one avatar cache: a `Drawable` for an account, a conversation, a contact or a room,
 * drawn from a picture when there is one and from a letter tile when there is not.
 *
 * <p>**The service field is non-null here.** The Java started it at `null` and assigned it in the
 * constructor, so every read after construction was a non-null read; Kotlin says that directly in the
 * property initializer instead of carrying a nullable field through forty dereferences.
 *
 * <p>**Several statics stay `@JvmStatic`**: `get(Jid, int)` (`UiAppHost`) and `getSystemUiAvatarSize`
 * (`NotificationService` and `ConnectionService.kt`). `getRoundedShortcut`/`getRoundedShortcutWithIcon`
 * are instance methods and keep their shape. The tile helpers (`drawTile`, `drawAvatar`,
 * `getFirstLetter`, `emptyOnNull`, `getImpl`, `getRoundLauncherIcon`) were Java `private static`s and
 * are private companion functions here; the nested `TextDrawable` reaches them, which Kotlin allows.
 *
 * <p>Java's `'\0'` has no Kotlin source escape, so every NUL in a cache key is `'\u0000'` (and the
 * same in `StringBuilder.append`), which is the same character and therefore the same key. The cache
 * keys start with a `String`, so the `Jid`/`MucOptions` concatenations never need `.toString()`; only
 * a `Jid` on the *left* of a `+` would.
 */
class AvatarService(service: XmppConnectionService) :
        OnAdvancedStreamFeaturesLoaded,
        AvatarReader.Port,
        UiHost.AvatarSource {

    protected var mXmppConnectionService: XmppConnectionService = service

    private val sizes: MutableSet<Int> = HashSet()
    // port-5: the key is the conversation uuid, and `Conversational.getUuid()` is nullable. Java's
    // `HashMap` accepted a `null` key and stored it; the nullable key type keeps that exactly rather
    // than folding it to a stand-in.
    private val conversationDependentKeys: MutableMap<String?, MutableSet<String>> = HashMap()

    init {
        // 3.7 pair 2: the one avatar action `:data` performs reaches this cache through a port it
        // declares (`uk.xa0.tulkki.data.view.AvatarReader`), and this is the only object that can
        // implement it - the cache is per service and `XmppConnectionService` builds exactly one per
        // process. It is installed here rather than from `TulkkiApplication.onCreate` because there
        // is no avatar cache to point at before this constructor has run; the holder throws if it is
        // empty, so a missing install is a build fault and not a placeholder icon.
        AvatarReader.install(this)
    }

    override fun systemUiAvatarSize(context: Context): Int = getSystemUiAvatarSize(context)

    override fun get(avatarable: Avatarable, size: Int, cachedOnly: Boolean): Drawable? {
        return when (avatarable) {
            is Account -> get(avatarable, size, cachedOnly)
            is Conversation -> get(avatarable, size, cachedOnly)
            is Message -> get(avatarable, size, cachedOnly)
            is ListItem -> get(avatarable, size, cachedOnly)
            is MucOptions.User -> get(avatarable, size, cachedOnly)
            is Room -> get(avatarable, size, cachedOnly)
            else ->
                    throw AssertionError(
                            "AvatarService does not know how to generate avatar from " +
                                    avatarable.javaClass.name)
        }
    }

    private fun get(result: Room, size: Int, cacheOnly: Boolean): Drawable? {
        val room = result.getRoom()
        val conversation: Conversation? =
                if (room != null) {
                    mXmppConnectionService.findFirstMuc(room) as Conversation?
                } else {
                    null
                }
        if (conversation != null) {
            return get(conversation, size, cacheOnly)
        }
        return get(
                CHANNEL_SYMBOL,
                if (room != null) room.asBareJid().toString() else result.getName(),
                size,
                cacheOnly)
    }

    private fun get(contact: Contact, size: Int, cachedOnly: Boolean): Drawable? {
        if (contact.isSelf()) {
            return get(contact.getAccount(), size, cachedOnly)
        }
        val key = key(contact, size)
        var avatar = mXmppConnectionService.getDrawableCache().get(key)
        if (avatar != null || cachedOnly) {
            return avatar
        }
        if (contact.getAvatarFilename() != null && AbstractContactListSyncService.isQuicksy()) {
            avatar = FileBackends.get().getAvatar(contact.getAvatarFilename(), size)
        }
        if (avatar == null && contact.getProfilePhoto() != null) {
            avatar =
                    BitmapDrawable(
                            FileBackends.get()
                                    .cropCenterSquare(
                                            Uri.parse(contact.getProfilePhoto()), size))
        }
        if (avatar == null && contact.getAvatarFilename() != null) {
            avatar = FileBackends.get().getAvatar(contact.getAvatarFilename(), size)
        }
        if (avatar == null) {
            avatar =
                    get(
                            contact.getDisplayName(),
                            contact.getJid().asBareJid().toString(),
                            size,
                            cachedOnly)
        }
        if (avatar != null) {
            mXmppConnectionService.getDrawableCache().put(key, avatar)
        }
        return avatar
    }

    fun getRoundedShortcut(mucOptions: MucOptions): Bitmap {
        val metrics: DisplayMetrics =
                mXmppConnectionService.getResources().getDisplayMetrics()
        val size = Math.round(metrics.density * 48)
        val bitmap = FileBackend.drawDrawable(get(mucOptions, size, false)) ?: throw NullPointerException()
        val output =
                Bitmap.createBitmap(
                        bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        drawAvatar(bitmap, canvas, paint)
        return output
    }

    fun getRoundedShortcut(contact: Contact): Bitmap = getRoundedShortcut(contact, false)

    fun getRoundedShortcutWithIcon(contact: Contact): Bitmap = getRoundedShortcut(contact, true)

    private fun getRoundedShortcut(contact: Contact, withIcon: Boolean): Bitmap {
        val metrics: DisplayMetrics =
                mXmppConnectionService.getResources().getDisplayMetrics()
        val size = Math.round(metrics.density * 48)
        val bitmap = FileBackend.drawDrawable(get(contact, size)) ?: throw NullPointerException()
        val output =
                Bitmap.createBitmap(
                        bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()

        drawAvatar(bitmap, canvas, paint)
        if (withIcon) {
            drawIcon(canvas, paint)
        }
        return output
    }

    private fun drawIcon(canvas: Canvas, paint: Paint) {
        val resources: Resources = mXmppConnectionService.getResources()
        val icon = getRoundLauncherIcon(resources) ?: return
        paint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_OVER))

        val iconSize = Math.round(canvas.getHeight() / 2.6f)

        val left = canvas.getWidth() - iconSize
        val top = canvas.getHeight() - iconSize
        val rect = Rect(left, top, left + iconSize, top + iconSize)
        canvas.drawBitmap(icon, null, rect, paint)
    }

    fun get(user: MucOptions.User, size: Int, cachedOnly: Boolean): Drawable? {
        val c = user.getContact()
        return if (c != null &&
                (c.getProfilePhoto() != null ||
                        c.getAvatarFilename() != null ||
                        user.getAvatar() == null)) {
            get(c, size, cachedOnly)
        } else {
            getImpl(user, size, cachedOnly)
        }
    }

    private fun getImpl(user: MucOptions.User, size: Int, cachedOnly: Boolean): Drawable? {
        val key = key(user, size)
        var avatar = mXmppConnectionService.getDrawableCache().get(key)
        if (avatar != null || cachedOnly) {
            return avatar
        }
        if (user.getAvatar() != null) {
            avatar = FileBackends.get().getAvatar(user.getAvatar(), size)
        }
        if (avatar == null) {
            val contact = user.getContact()
            if (contact != null) {
                avatar = get(contact, size, false)
            } else {
                val realJid = user.getRealJid()
                val seed = if (realJid != null) realJid.asBareJid().toString() else null
                avatar = get(user.getNick(), seed, size, false)
            }
        }
        mXmppConnectionService.getDrawableCache().put(key, avatar)
        return avatar
    }

    override fun clear(contact: Contact) {
        synchronized(this.sizes) {
            for (size in sizes) {
                mXmppConnectionService.getDrawableCache().remove(key(contact, size))
            }
        }
        for (conversation in
                mXmppConnectionService.findAllConferencesWith(contact) as List<Conversation>) {
            val user =
                    conversation
                            .getMucOptions()
                            .findUserByRealJid(contact.getJid().asBareJid())
            if (user != null) {
                clear(user)
            }
            clear(conversation)
        }
    }

    private fun key(contact: Contact, size: Int): String {
        synchronized(this.sizes) {
            this.sizes.add(size)
        }
        return PREFIX_CONTACT +
                '\u0000' +
                contact.getAccount().getJid().asBareJid() +
                '\u0000' +
                emptyOnNull(contact.getJid()) +
                '\u0000' +
                size
    }

    private fun key(user: MucOptions.User, size: Int): String {
        synchronized(this.sizes) {
            this.sizes.add(size)
        }
        return PREFIX_CONTACT +
                '\u0000' +
                user.getAccount().getJid().asBareJid() +
                '\u0000' +
                user.getMuc() +
                '\u0000' +
                (if (user.getOccupantId() == null) {
                    emptyOnNull(user.getFullJid())
                } else {
                    user.getOccupantId()
                }) +
                '\u0000' +
                emptyOnNull(user.getRealJid()) +
                '\u0000' +
                size
    }

    fun get(item: ListItem, size: Int): Drawable? = get(item, size, false)

    fun get(item: ListItem, size: Int, cachedOnly: Boolean): Drawable? {
        if (item is RawBlockable) {
            return get(item.getDisplayName(), item.getJid().toString(), size, cachedOnly)
        } else if (item is Contact) {
            return get(item, size, cachedOnly)
        } else if (item is Bookmark) {
            val bookmark = item
            val conversation = bookmark.getConversation()
            if (conversation != null) {
                return get(conversation, size, cachedOnly)
            } else {
                val jid = bookmark.getJid()
                val account = bookmark.getAccount()
                val contact =
                        if (jid == null) null else account.getRoster().getContact(jid)
                if (contact != null && contact.getAvatarFilename() != null) {
                    return get(contact, size, cachedOnly)
                }
                val seed = if (jid != null) jid.asBareJid().toString() else null
                return get(bookmark.getDisplayName(), seed, size, cachedOnly)
            }
        } else {
            val seed =
                    if (item.getJid() != null) item.getJid().asBareJid().toString() else null
            return get(item.getDisplayName(), seed, size, cachedOnly)
        }
    }

    fun get(conversation: Conversation, size: Int): Drawable? = get(conversation, size, false)

    fun get(conversation: Conversation, size: Int, cachedOnly: Boolean): Drawable? {
        return if (conversation.getMode() == Conversation.MODE_SINGLE) {
            get(conversation.getContact(), size, cachedOnly)
        } else {
            get(conversation.getMucOptions(), size, cachedOnly)
        }
    }

    override fun clear(conversation: Conversation) {
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            clear(conversation.getContact())
        } else {
            clear(conversation.getMucOptions())
            synchronized(this.conversationDependentKeys) {
                val keys: MutableSet<String> =
                        this.conversationDependentKeys[conversation.getUuid()] ?: return
                val cache: LruCache<String, Drawable> =
                        mXmppConnectionService.getDrawableCache()
                for (key in keys) {
                    cache.remove(key)
                }
                keys.clear()
            }
        }
    }

    private fun get(mucOptions: MucOptions, size: Int, cachedOnly: Boolean): Drawable? {
        val key = key(mucOptions, size)
        var bitmap = mXmppConnectionService.getDrawableCache().get(key)
        if (bitmap != null || cachedOnly) {
            return bitmap
        }

        bitmap = FileBackends.get().getAvatar(mucOptions.getAvatar(), size)

        if (bitmap == null) {
            val c = mucOptions.getConversation()
            if (mucOptions.isPrivateAndNonAnonymous()) {
                val users = mucOptions.getUsersRelevantForNameAndAvatar()
                bitmap =
                        if (users.size == 0) {
                            getImpl(
                                    c.getName().toString(),
                                    c.getJid()!!.asBareJid().toString(),
                                    size)
                        } else {
                            getImpl(users, size)
                        }
            } else {
                bitmap = getImpl(CHANNEL_SYMBOL, c.getJid()!!.asBareJid().toString(), size)
            }
        }

        mXmppConnectionService.getDrawableCache().put(key, bitmap)

        return bitmap
    }

    private fun get(users: List<MucOptions.User>, size: Int, cachedOnly: Boolean): Drawable? {
        val key = key(users, size)
        var bitmap = mXmppConnectionService.getDrawableCache().get(key)
        if (bitmap != null || cachedOnly) {
            return bitmap
        }
        bitmap = getImpl(users, size)
        mXmppConnectionService.getDrawableCache().put(key, bitmap)
        return bitmap
    }

    private fun getImpl(users: List<MucOptions.User>, size: Int): Drawable {
        val count = users.size
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        bitmap.eraseColor(TRANSPARENT)
        if (count == 0) {
            throw AssertionError("Unable to draw tiles for 0 users")
        } else if (count == 1) {
            drawTile(canvas, users[0], 0, 0, size / 2 - 1, size)
            drawTile(canvas, users[0].getAccount(), size / 2 + 1, 0, size, size)
        } else if (count == 2) {
            drawTile(canvas, users[0], 0, 0, size / 2 - 1, size)
            drawTile(canvas, users[1], size / 2 + 1, 0, size, size)
        } else if (count == 3) {
            drawTile(canvas, users[0], 0, 0, size / 2 - 1, size)
            drawTile(canvas, users[1], size / 2 + 1, 0, size, size / 2 - 1)
            drawTile(canvas, users[2], size / 2 + 1, size / 2 + 1, size, size)
        } else if (count == 4) {
            drawTile(canvas, users[0], 0, 0, size / 2 - 1, size / 2 - 1)
            drawTile(canvas, users[1], 0, size / 2 + 1, size / 2 - 1, size)
            drawTile(canvas, users[2], size / 2 + 1, 0, size, size / 2 - 1)
            drawTile(canvas, users[3], size / 2 + 1, size / 2 + 1, size, size)
        } else {
            drawTile(canvas, users[0], 0, 0, size / 2 - 1, size / 2 - 1)
            drawTile(canvas, users[1], 0, size / 2 + 1, size / 2 - 1, size)
            drawTile(canvas, users[2], size / 2 + 1, 0, size, size / 2 - 1)
            drawTile(canvas, "\u2026", PLACEHOLDER_COLOR, size / 2 + 1, size / 2 + 1, size, size)
        }
        return BitmapDrawable(bitmap)
    }

    fun clear(options: MucOptions?) {
        if (options == null) {
            return
        }
        synchronized(this.sizes) {
            for (size in sizes) {
                mXmppConnectionService.getDrawableCache().remove(key(options, size))
            }
        }
    }

    private fun key(options: MucOptions, size: Int): String {
        synchronized(this.sizes) {
            this.sizes.add(size)
        }
        return PREFIX_CONVERSATION + "_" + options.getConversation().getUuid() + "_" + size
    }

    private fun key(users: List<MucOptions.User>, size: Int): String {
        val conversation = users[0].getConversation()
        val builder = StringBuilder("TILE_")
        builder.append(conversation.getUuid())

        for (user in users) {
            builder.append("\u0000")
            builder.append(emptyOnNull(user.getRealJid()))
            builder.append("\u0000")
            builder.append(emptyOnNull(user.getFullJid()))
        }
        builder.append('\u0000')
        builder.append(size)
        val key = builder.toString()
        synchronized(this.conversationDependentKeys) {
            val keys: MutableSet<String> =
                    this.conversationDependentKeys.getOrPut(conversation.getUuid()) { HashSet() }
            keys.add(key)
        }
        return key
    }

    override fun get(account: Account, size: Int): Drawable? = get(account, size, false)

    fun get(account: Account, size: Int, cachedOnly: Boolean): Drawable? {
        val key = key(account, size)
        var avatar = mXmppConnectionService.getDrawableCache().get(key)
        if (avatar != null || cachedOnly) {
            return avatar
        }
        avatar = FileBackends.get().getAvatar(account.getAvatar(), size)
        if (avatar == null) {
            val displayName = account.getDisplayName()
            val jid = account.getJid().asBareJid().toString()
            if (AbstractContactListSyncService.isQuicksy() && !TextUtils.isEmpty(displayName)) {
                avatar = get(displayName, jid, size, false)
            } else {
                avatar = get(jid, null, size, false)
            }
        }
        mXmppConnectionService.getDrawableCache().put(key, avatar)
        return avatar
    }

    fun get(message: Message, size: Int, cachedOnly: Boolean): Drawable? {
        val conversation = message.getConversation()
        // Tulkki: a call cannot be smart-cast, so the nullable `getCounterparts()` is read once.
        val counterparts = message.getCounterparts()
        if (message.getType() == Message.TYPE_STATUS &&
                counterparts != null &&
                counterparts.size > 1) {
            return get(counterparts, size, cachedOnly)
        }
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            var c: Contact? = message.getContact()
            if (message.getModerated() != null) {
                c = null
            }
            if (c != null && (c.getProfilePhoto() != null || c.getAvatarFilename() != null)) {
                return get(c, size, cachedOnly)
            } else if (conversation is Conversation &&
                    conversation.getMode() == Conversation.MODE_MULTI) {
                val trueCounterpart = message.getTrueCounterpart()
                val mucOptions = conversation.getMucOptions()
                var user: MucOptions.User? =
                        if (trueCounterpart != null) {
                            mucOptions.findOrCreateUserByRealJid(
                                    trueCounterpart,
                                    message.getCounterpart(),
                                    message.getOccupantId())
                        } else if (message.getOccupantId() != null) {
                            mucOptions.findUserByOccupantId(
                                    message.getOccupantId(), message.getCounterpart())
                        } else {
                            mucOptions.findUserByFullJid(message.getCounterpart())
                        }
                if (message.getModerated() != null) {
                    user = null
                }
                if (user != null) {
                    return getImpl(user, size, cachedOnly)
                }
            } else if (c != null) {
                return get(c, size, cachedOnly)
            }
            val tcp = message.getTrueCounterpart()
            val seed = if (tcp != null) tcp.asBareJid().toString() else null
            return get(UIHelper.getMessageDisplayName(message), seed, size, cachedOnly)
        }
        // Tulkki: no conversation, no account to key the avatar on - the Java dereferenced it
        // unchecked, so the lookup is skipped and the caller draws its fallback.
        val account = conversation?.getAccount() ?: return null
        return get(account, size, cachedOnly)
    }

    fun clear(account: Account) {
        synchronized(this.sizes) {
            for (size in sizes) {
                mXmppConnectionService.getDrawableCache().remove(key(account, size))
            }
        }
    }

    override fun clear(user: MucOptions.User) {
        synchronized(this.sizes) {
            for (size in sizes) {
                mXmppConnectionService.getDrawableCache().remove(key(user, size))
            }
        }
    }

    private fun key(account: Account, size: Int): String {
        synchronized(this.sizes) {
            this.sizes.add(size)
        }
        return PREFIX_ACCOUNT + "_" + account.getUuid() + "_" + size
    }

    fun get(name: String?, seed: String?, size: Int, cachedOnly: Boolean): Drawable =
            getImpl(name, seed, size)

    private fun key(name: String, size: Int): String {
        synchronized(this.sizes) {
            this.sizes.add(size)
        }
        return PREFIX_GENERIC + "_" + name + "_" + size
    }

    private fun drawTile(
            canvas: Canvas,
            user: MucOptions.User,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
    ): Boolean {
        val contact = user.getContact()
        if (contact != null) {
            var uri: Uri? = null
            if (contact.getAvatarFilename() != null &&
                    AbstractContactListSyncService.isQuicksy()) {
                uri = FileBackends.get().getAvatarUri(contact.getAvatarFilename())
            } else if (contact.getProfilePhoto() != null) {
                uri = Uri.parse(contact.getProfilePhoto())
            } else if (contact.getAvatarFilename() != null) {
                uri = FileBackends.get().getAvatarUri(contact.getAvatarFilename())
            }
            if (drawTile(canvas, uri, left, top, right, bottom)) {
                return true
            }
        } else if (user.getAvatar() != null) {
            val uri = FileBackends.get().getAvatarUri(user.getAvatar())
            if (drawTile(canvas, uri, left, top, right, bottom)) {
                return true
            }
        }
        if (contact != null) {
            val seed = contact.getJid().asBareJid().toString()
            drawTile(canvas, contact.getDisplayName(), seed, left, top, right, bottom)
        } else {
            val realJid = user.getRealJid()
            val seed = if (realJid == null) null else realJid.asBareJid().toString()
            drawTile(canvas, user.getName(), seed, left, top, right, bottom)
        }
        return true
    }

    private fun drawTile(
            canvas: Canvas,
            account: Account,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
    ): Boolean {
        val avatar = account.getAvatar()
        if (avatar != null) {
            val uri = FileBackends.get().getAvatarUri(avatar)
            if (uri != null) {
                if (drawTile(canvas, uri, left, top, right, bottom)) {
                    return true
                }
            }
        }
        val name = account.getJid().asBareJid().toString()
        return drawTile(canvas, name, name, left, top, right, bottom)
    }

    private fun drawTile(
            canvas: Canvas,
            uri: Uri?,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
    ): Boolean {
        if (uri != null) {
            val bitmap = FileBackends.get().cropCenter(uri, bottom - top, right - left)
            if (bitmap != null) {
                drawTile(canvas, bitmap, left, top, right, bottom)
                return true
            }
        }
        return false
    }

    private fun drawTile(
            canvas: Canvas,
            bm: Bitmap,
            dstleft: Int,
            dsttop: Int,
            dstright: Int,
            dstbottom: Int
    ): Boolean {
        val dst = Rect(dstleft, dsttop, dstright, dstbottom)
        canvas.drawBitmap(bm, null, dst, null)
        return true
    }

    override fun onAdvancedStreamFeaturesAvailable(accountRef: AccountRef) {
        val account = accountRef as Account
        val connection = account.getXmppConnection() ?: return
        val features: XmppConnection.Features = connection.getFeatures()
        if (features.pep() && !features.pepPersistent()) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": has pep but is not persistent")
            if (account.getAvatar() != null) {
                mXmppConnectionService.republishAvatarIfNeeded(account)
            }
        }
    }

    class TextDrawable(
            private val name: String?,
            private val seed: String?,
            private val size: Int
    ) : Drawable(), TextAvatar {

        override fun draw(canvas: Canvas) {
            val r = bounds
            drawTile(canvas, name, seed, r.left, r.top, r.right, r.bottom)
        }

        override fun setAlpha(alpha: Int) {
            // TODO?
        }

        override fun setColorFilter(cf: ColorFilter?) {
            // TODO?
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = size

        override fun getIntrinsicHeight(): Int = size
    }

    companion object {

        private val FG_COLOR = 0xFFFAFAFA.toInt()
        private val TRANSPARENT = 0x00000000
        private val PLACEHOLDER_COLOR = 0xFF202020.toInt()

        const val SYSTEM_UI_AVATAR_SIZE = 48

        private const val PREFIX_CONTACT = "contact"
        private const val PREFIX_CONVERSATION = "conversation"
        private const val PREFIX_ACCOUNT = "account"
        private const val PREFIX_GENERIC = "generic"

        private const val CHANNEL_SYMBOL = "#"

        @JvmStatic
        fun getSystemUiAvatarSize(context: Context): Int =
                (SYSTEM_UI_AVATAR_SIZE * context.getResources().getDisplayMetrics().density)
                        .toInt()

        @JvmStatic
        fun get(jid: Jid, size: Int): Drawable = getImpl(jid.asBareJid().toString(), null, size)

        private fun getImpl(name: String?, seed: String?, size: Int): Drawable {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val trimmedName = if (name == null) "" else name.javaTrim()
            return TextDrawable(name, seed, size)
        }

        private fun drawAvatar(bitmap: Bitmap, canvas: Canvas, paint: Paint) {
            val rect = Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())
            paint.setAntiAlias(true)
            canvas.drawARGB(0, 0, 0, 0)
            canvas.drawCircle(
                    (bitmap.getWidth() / 2).toFloat(),
                    (bitmap.getHeight() / 2).toFloat(),
                    (bitmap.getWidth() / 2).toFloat(),
                    paint)
            paint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))
            canvas.drawBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, false), rect, rect, paint)
        }

        private fun getRoundLauncherIcon(resources: Resources): Bitmap? {
            val drawable =
                    ResourcesCompat.getDrawable(resources, R.mipmap.ic_launcher_round, null)
                            ?: return null

            if (drawable is BitmapDrawable) {
                return drawable.getBitmap()
            }

            val bitmap =
                    Bitmap.createBitmap(
                            drawable.getIntrinsicWidth(),
                            drawable.getIntrinsicHeight(),
                            Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight())
            drawable.draw(canvas)

            return bitmap
        }

        private fun drawTile(
                canvas: Canvas,
                letter: String,
                tileColor: Int,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int
        ): Boolean {
            val upper = letter.uppercase(Locale.getDefault())
            val tilePaint = Paint()
            val textPaint = Paint()
            tilePaint.setColor(tileColor)
            textPaint.setFlags(Paint.ANTI_ALIAS_FLAG)
            textPaint.setColor(FG_COLOR)
            textPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
            textPaint.setTextSize(((right - left) * 0.8).toFloat())
            val rect = Rect()

            canvas.drawRect(Rect(left, top, right, bottom), tilePaint)
            textPaint.getTextBounds(upper, 0, 1, rect)
            val width = textPaint.measureText(upper)
            canvas.drawText(
                    upper,
                    ((right + left) / 2 - width / 2).toFloat(),
                    ((top + bottom) / 2 + rect.height() / 2).toFloat(),
                    textPaint)
            return true
        }

        private fun drawTile(
                canvas: Canvas,
                name: String?,
                seed: String?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int
        ): Boolean {
            if (name != null) {
                val letter = if (name == CHANNEL_SYMBOL) name else getFirstLetter(name)
                val color = UIHelper.getColorForName(if (seed == null) name else seed)
                drawTile(canvas, letter, color, left, top, right, bottom)
                return true
            }
            return false
        }

        private fun getFirstLetter(name: String): String {
            for (c in name.toCharArray()) {
                if (Character.isLetterOrDigit(c)) {
                    return c.toString()
                }
            }
            return "X"
        }

        private fun emptyOnNull(@Nullable value: Jid?): String =
                if (value == null) "" else value.toString()
    }
}

// Java's `String.trim()`: only the characters up to U+0020. Kotlin's `trim()` is the Unicode set and
// would also strip a non-breaking space (U+00A0) from an avatar's letter-tile name, which the Java
// never did.
private fun String.javaTrim(): String = trim { it <= ' ' }
