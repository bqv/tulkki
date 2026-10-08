package uk.xa0.tulkki.xmpp

import android.graphics.Bitmap
import android.net.Uri
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.chatstate.ChatState
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import java.util.Arrays
import java.util.Collections
import java.util.Locale

/**
 * Tulkki: the build's compile-time switches and bounds.
 *
 * Ported from `Config.java`. The class was a `final`
 * class with a private constructor and no instance, so it becomes a Kotlin `object`: every member
 * Java reads is kept Java-visible - the primitives and `String` literals as `const val`, the
 * computed and enum-typed values as `@JvmField val`, the methods as `@JvmStatic` - so
 * `Config.LOGTAG`, `Config.supportOmemo()` and `Config.Map.INITIAL_ZOOM_LEVEL` keep their
 * Java spellings and a `switch` over any of them still sees a compile-time constant. `ChatState`
 * is already Kotlin, so `DEFAULT_CHAT_STATE` moves to the Kotlin enum's `ACTIVE` value unchanged.
 */
object Config {
    private const val UNENCRYPTED = 1
    private const val OPENPGP = 2
    private const val OTR = 4
    private const val OMEMO = 8

    private const val ENCRYPTION_MASK = UNENCRYPTED or OPENPGP or OTR or OMEMO

    @JvmStatic
    fun supportUnencrypted(): Boolean = (ENCRYPTION_MASK and UNENCRYPTED) != 0

    @JvmStatic
    fun supportOpenPgp(): Boolean = (ENCRYPTION_MASK and OPENPGP) != 0

    @JvmStatic
    fun supportOmemo(): Boolean = (ENCRYPTION_MASK and OMEMO) != 0

    @JvmStatic
    fun supportOtr(): Boolean = (ENCRYPTION_MASK and OTR) != 0

    @JvmStatic
    fun omemoOnly(): Boolean = !multipleEncryptionChoices() && supportOmemo()

    @JvmStatic
    fun multipleEncryptionChoices(): Boolean = (ENCRYPTION_MASK and (ENCRYPTION_MASK - 1)) != 0

    @JvmField val LOGTAG: String = BuildConfig.APP_NAME.lowercase(Locale.US)

    const val QUICK_LOG = false

    // Tulkki: this build belongs to nobody else, so it names no project's support address or
    // documentation. Null is the designed "there is nothing to open" - ExceptionHelper and
    // RtpSessionActivity already test for it - so the entries simply disappear.
    @JvmField val BUG_REPORTS: Jid? = null
    @JvmField val HELP: Uri? = null

    // Tulkki: the two monocles signup domains - `MAGIC_CREATE_DOMAIN` ("conversations.im") and
    // `ONBOARDING_DOMAIN` ("onboarding.cheogram.com") - are deleted. A zero-account install adds an
    // account through EditAccountActivity instead of being advertised somebody's service. Labelled
    // island divergence; see the commit body and docs/MIGRATION.md "The deletions" C5.
    @JvmField val QUICKSY_DOMAIN: Jid = Jid.of("cheogram.com")

    const val CHANNEL_DISCOVERY = "https://search.jabber.network"

    const val DISALLOW_REGISTRATION_IN_UI = false // hide the register checkbox

    const val USE_RANDOM_RESOURCE_ON_EVERY_BIND = false

    const val MESSAGE_DISPLAYED_SYNCHRONIZATION = true

    const val ALLOW_NON_TLS_CONNECTIONS = false // very dangerous. you should have a good reason

    const val CONTACT_SYNC_RETRY_INTERVAL = 1000L * 60 * 5

    const val QUICKSTART_ENABLED = true

    // Notification settings
    const val HIDE_MESSAGE_TEXT_IN_NOTIFICATION = false
    const val ALWAYS_NOTIFY_BY_DEFAULT = false
    const val SUPPRESS_ERROR_NOTIFICATION = false

    const val DISABLE_BAN = false // disables the ability to ban users from rooms

    const val PING_MAX_INTERVAL = 300
    const val IDLE_PING_INTERVAL = 600 // 540 is minimum according to docs;
    const val PING_MIN_INTERVAL = 30
    const val LOW_PING_TIMEOUT = 1 // used after push received
    const val PING_TIMEOUT = 15
    const val SOCKET_TIMEOUT = 15
    const val CONNECT_TIMEOUT = 90
    const val POST_CONNECTIVITY_CHANGE_PING_INTERVAL = 30
    const val CONNECT_DISCO_TIMEOUT = 20
    const val MINI_GRACE_PERIOD = 750

    // media file formats. Homogenous Android or Conversations only deployments can switch to opus
    // and webp
    const val AVATAR_SIZE = 480

    @JvmField val AVATAR_FORMAT: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG
    const val AVATAR_CHAR_LIMIT = 9400

    const val IMAGE_SIZE = 1920

    @JvmField val IMAGE_FORMAT: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG
    const val IMAGE_QUALITY = 75

    const val USE_OPUS_VOICE_MESSAGES = false

    const val MESSAGE_MERGE_WINDOW = 90_000

    const val PAGE_SIZE = 50
    const val MAX_NUM_PAGES = 3
    const val MAX_SEARCH_RESULTS = 300

    const val REFRESH_UI_INTERVAL = 500

    const val MAX_DISPLAY_MESSAGE_CHARS = 10000
    const val MAX_STORAGE_MESSAGE_CHARS = 1 * 1024 * 1024 // 1MB

    const val MILLISECONDS_IN_DAY = 24 * 60 * 60 * 1000L

    const val REMOVE_BROKEN_DEVICES = false
    const val OMEMO_PADDING = false
    const val PUT_AUTH_TAG_INTO_KEY = true
    const val AUTOMATICALLY_COMPLETE_SESSIONS = true
    const val DISABLE_PROXY_LOOKUP = false // disables STUN/TURN and Proxy65 look up (IBB fallback)
    const val USE_DIRECT_JINGLE_CANDIDATES = true
    const val USE_JINGLE_MESSAGE_INIT = true

    const val DISABLE_HTTP_UPLOAD = false
    const val EXTENDED_SM_LOGGING = true // log stanza counts
    const val BACKGROUND_STANZA_LOGGING = false // log all stanzas received while in background
    const val RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE = true // true may increase power consumption

    const val ENCRYPT_ON_HTTP_UPLOADED = false

    const val X509_VERIFICATION = false // use x509 certificates to verify OMEMO keys
    const val REQUIRE_RTP_VERIFICATION = false // require a/v calls to be verified with OMEMO
    const val JINGLE_MESSAGE_INIT_STRICT_OFFLINE_CHECK = false
    const val JINGLE_MESSAGE_INIT_STRICT_DEVICE_TIMEOUT = false
    const val DEVICE_DISCOVERY_TIMEOUT = 6000L // in milliseconds

    const val ONLY_INTERNAL_STORAGE = false // internal storage instead of sdcard for attachments

    const val IGNORE_ID_REWRITE_IN_MUC = true
    const val MUC_LEAVE_BEFORE_JOIN = false

    const val USE_LMC_VERSION_1_1 = true

    const val MAM_MIN_CATCHUP = MILLISECONDS_IN_DAY * 2
    const val MAM_MAX_CATCHUP = MILLISECONDS_IN_DAY * 5
    const val MAM_MAX_MESSAGES = 750

    @JvmField val DEFAULT_CHAT_STATE: ChatState = ChatState.ACTIVE
    const val TYPING_TIMEOUT = 8

    const val EXPIRY_INTERVAL = 30 * 60 * 1000 // 30 minutes

    object OMEMO_EXCEPTIONS {
        // if the own account matches one of the following domains OMEMO won’t be turned on
        // automatically
        @JvmField val ACCOUNT_DOMAINS: List<String> = Collections.singletonList("s.ms")

        // if the contacts domain matches one of the following domains OMEMO won’t be turned on
        // automatically, can be used for well known, widely used gateways
        // Tulkki: upstream's own three gateway hosts were listed here too. They are that project's
        // bridges, so they are dropped with the rest of its services: a contact on one of them now
        // gets automatic OMEMO like any other contact, and the owner turns it off by hand for that
        // conversation if the bridge cannot do it.
        private val CONTACT_DOMAINS: List<String> = Arrays.asList(
            "sip.cheogram.com",
            "irc.cheogram.com",
            "smtp.cheogram.com",
            "cheogram.com",
            "aria-net.org",
            "matrix.org",
            "*.covid.monal.im"
        )

        @JvmStatic
        fun matchesContactDomain(
            trustPort: uk.xa0.tulkki.xmpp.services.TrustPort, domain: String
        ): Boolean = trustPort.matchDomain(domain, CONTACT_DOMAINS)
    }

    object Map {
        const val INITIAL_ZOOM_LEVEL = 4.0
        const val FINAL_ZOOM_LEVEL = 15.0
        const val MY_LOCATION_INDICATOR_SIZE = 10
        const val MY_LOCATION_INDICATOR_OUTLINE_SIZE = 3
        const val LOCATION_FIX_TIME_DELTA = 1000 * 10L // ms
        const val LOCATION_FIX_SPACE_DELTA = 10f // m
        const val LOCATION_FIX_SIGNIFICANT_TIME_DELTA = 1000 * 60 * 2 // ms
    }

    // How deep nested quotes should be displayed. '2' means one quote nested in another.
    const val QUOTE_MAX_DEPTH = 7

    // How deep nested quotes should be created on quoting a message.
    const val QUOTING_MAX_DEPTH = 2
}
