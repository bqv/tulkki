package uk.xa0.tulkki.xmpp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Pair
import android.util.SparseArray
import com.google.common.base.Optional
import com.google.common.collect.Iterables
import com.google.common.primitives.Ints
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParserException
import uk.xa0.tulkki.crypto.sasl.ChannelBinding
import uk.xa0.tulkki.crypto.sasl.ChannelBindingMechanism
import uk.xa0.tulkki.crypto.sasl.DowngradeProtection
import uk.xa0.tulkki.crypto.sasl.HashedToken
import uk.xa0.tulkki.crypto.sasl.SaslMechanism
import uk.xa0.tulkki.crypto.sasl.ScramMechanism
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.libs.AppSettingsRef
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.app.generator.IqGenerator
import uk.xa0.tulkki.app.http.HttpConnectionManager
import uk.xa0.tulkki.parser.IqParser
import uk.xa0.tulkki.parser.MessageParser
import uk.xa0.tulkki.parser.PresenceParser
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.services.MemorizingTrustManager
import uk.xa0.tulkki.xmpp.services.MessageArchiveService
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.CryptoHelper
import uk.xa0.tulkki.xmpp.utils.Random
import uk.xa0.tulkki.xmpp.utils.Resolver
import uk.xa0.tulkki.xmpp.utils.SSLSockets
import uk.xa0.tulkki.xmpp.utils.SocksSocketFactory
import uk.xa0.tulkki.xmpp.utils.XmlHelper
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.LocalizedContent
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xml.Tag
import uk.xa0.tulkki.xml.TagWriter
import uk.xa0.tulkki.xml.XmlReader
import uk.xa0.tulkki.xmpp.bind.Bind2
import uk.xa0.tulkki.xmpp.forms.Data
import uk.xa0.tulkki.xmpp.jingle.OnJinglePacketReceived
import uk.xa0.tulkki.xmpp.models.AuthenticationFailure
import uk.xa0.tulkki.xmpp.models.AuthenticationRequest
import uk.xa0.tulkki.xmpp.models.AuthenticationStreamFeature
import uk.xa0.tulkki.xmpp.models.StreamElement
import uk.xa0.tulkki.xmpp.models.bind2.Bind
import uk.xa0.tulkki.xmpp.models.bind2.Bound
import uk.xa0.tulkki.xmpp.models.cb.SaslChannelBinding
import uk.xa0.tulkki.xmpp.models.csi.Active
import uk.xa0.tulkki.xmpp.models.csi.Inactive
import uk.xa0.tulkki.xmpp.models.error.Condition
import uk.xa0.tulkki.xmpp.models.fast.Fast
import uk.xa0.tulkki.xmpp.models.fast.RequestToken
import uk.xa0.tulkki.xmpp.models.jingle.Jingle
import uk.xa0.tulkki.xmpp.models.processor.BindProcessor
import uk.xa0.tulkki.xmpp.models.sasl.Auth
import uk.xa0.tulkki.xmpp.models.sasl.Failure
import uk.xa0.tulkki.xmpp.models.sasl.Mechanisms
import uk.xa0.tulkki.xmpp.models.sasl.Response
import uk.xa0.tulkki.xmpp.models.sasl.SaslError
import uk.xa0.tulkki.xmpp.models.sasl.Success
import uk.xa0.tulkki.xmpp.models.sasl2.Authenticate
import uk.xa0.tulkki.xmpp.models.sasl2.Authentication
import uk.xa0.tulkki.xmpp.models.sasl2.UserAgent
import uk.xa0.tulkki.xmpp.models.sasl2.Failure as Sasl2Failure
import uk.xa0.tulkki.xmpp.models.sasl2.Response as Sasl2Response
import uk.xa0.tulkki.xmpp.models.sasl2.Success as Sasl2Success
import uk.xa0.tulkki.xmpp.models.sm.Ack
import uk.xa0.tulkki.xmpp.models.sm.Enable
import uk.xa0.tulkki.xmpp.models.sm.Enabled
import uk.xa0.tulkki.xmpp.models.sm.Failed
import uk.xa0.tulkki.xmpp.models.sm.Request
import uk.xa0.tulkki.xmpp.models.sm.Resume
import uk.xa0.tulkki.xmpp.models.sm.Resumed
import uk.xa0.tulkki.xmpp.models.sm.StreamManagement
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.models.stanza.Message
import uk.xa0.tulkki.xmpp.models.stanza.Presence
import uk.xa0.tulkki.xmpp.models.stanza.Stanza
import uk.xa0.tulkki.xmpp.models.streams.Features as StreamFeatures
import uk.xa0.tulkki.xmpp.models.streams.StreamError
import uk.xa0.tulkki.xmpp.models.tls.Proceed
import uk.xa0.tulkki.xmpp.models.tls.StartTls
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Hashtable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.regex.Matcher
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * Tulkki: the connection engine.
 *
 * Ported from `XmppConnection.java`. The
 * file is converted whole, because a Java class is the unit of a Kotlin conversion: no method of it
 * can be Kotlin while the class is Java, and every cluster the Java already had shares the same
 * private state. The four seams that genuinely leave are its nested types, taken first, one commit
 * each: `StateChangingException`, `StateChangingError`, `LoginInfo`, `StreamId` and `MyKeyManager`
 * are top-level in this package now. `Features` is the one nested type that stays, and it stays
 * because two outside files spell it `XmppConnection.Features` (`:xmpp`'s `app/http/Method.kt` and
 * `:app`'s `AvatarService.kt`), which is the case the brief says to stop on: a nested type cannot
 * leave its outer class while files elsewhere spell it that way, and both spellings are in another
 * lane's file set.
 *
 * Interop decisions, each read off the Java callers:
 *
 * * `account` stays a protected `@JvmField` (the Java field was `protected final`), every getter
 *   keeps its Java name (`getFeatures`, `getMucServers`, ...) because Kotlin and Java callers write
 *   it that way, and the two `synchronized` methods keep `@Synchronized`.
 * * A Java `Set`/`List`/`Map` return is `MutableSet`/`MutableList`/`MutableMap` (`getMucServers`,
 *   `getMucServersWithholdAccount`, `getAccountFeatures`, `findDiscoItemsByFeature`) so a Kotlin
 *   caller that needs to mutate still compiles.
 * * The Java's `&&`-short-circuit dereferences of the nullable `streamFeatures` field keep the
 *   `?: throw NullPointerException(...)` where the Java dereferenced it - never `!!`.
 * * `Features`'s three flags are `internal var` because Java's nested-class private access has no
 *   Kotlin spelling at all; the outer class is the only other reader, and `internal` keeps the
 *   accessors invisible to Java (they are name-mangled).
 * * Multi-catch clauses have no Kotlin spelling, so each becomes two clauses with the same body;
 *   the order of the catches is the Java's.
 */
class XmppConnection(
    @JvmField protected val account: AccountRef,
    private val mXmppConnectionService: XmppConnectionService,
) : Runnable {

    private val features: Features = Features(this)
    private val disco: HashMap<Jid, ServiceDiscoveryResultRef> = HashMap()
    private val commands: HashMap<String, Jid> = HashMap()
    private val mStanzaQueue: SparseArray<Stanza> = SparseArray()
    private val packetCallbacks: Hashtable<
        String,
        Pair<Iq, Pair<Consumer<Iq>, ScheduledFuture<*>?>>,
    > = Hashtable()
    private val advancedStreamFeaturesLoadedListeners: MutableSet<OnAdvancedStreamFeaturesLoaded> =
        HashSet()
    private val appSettings: AppSettingsRef = mXmppConnectionService.getAppSettings()
    private var socket: Socket? = null
    private var tagReader: XmlReader? = null
    private var tagWriter: TagWriter = TagWriter()
    private var shouldAuthenticate = true
    private var inSmacksSession = false
    private var quickStartInProgress = false
    private var isBound = false
    private var offlineMessagesRetrieved = false
    private var streamFeatures: StreamFeatures? = null
    private var boundStreamFeatures: StreamFeatures? = null
    private var streamId: StreamId? = null
    private var stanzasReceived = 0
    private var stanzasSent = 0
    private var stanzasSentBeforeAuthentication = 0
    private var lastPacketReceived = 0L
    private var lastPingSent = 0L
    private var lastConnectionStarted = 0L
    private var lastSessionStarted = 0L
    private var lastDiscoStarted = 0L
    private var isMamPreferenceAlways = false
    private val mPendingServiceDiscoveries = AtomicInteger(0)
    private val mWaitForDisco = AtomicBoolean(true)
    private val mWaitingForSmCatchup = AtomicBoolean(false)
    private val mSmCatchupMessageCounter = AtomicInteger(0)
    private var mInteractive = false
    private var attempt = 0
    private var jingleListener: OnJinglePacketReceived? = null

    private val presenceListener: Consumer<Presence> = PresenceParser(mXmppConnectionService, account)
    private val unregisteredIqListener: Consumer<Iq> = IqParser(mXmppConnectionService, account)
    private val messageListener: Consumer<Message> = MessageParser(mXmppConnectionService, account)
    private var statusListener: OnStatusChanged? = null
    private val bindListener: Runnable = BindProcessor(mXmppConnectionService, account)
    private var acknowledgedListener: OnMessageAcknowledged? = null

    // Pair 11 (D4): this one-slot holder was `uk.xa0.tulkki.ui.util.PendingItem`, a `:ui` class the island
    // may not name. Nothing about it is view work - it was a synchronized one-slot box - so the
    // island keeps the box and takes it from the JDK: `getAndSet(null)` is `pop()`, `set(x)` is
    // `push(x)` and `set(null)` is `clear()`, with the same happens-before edges the synchronized
    // methods gave.
    private val pendingResumeId = AtomicReference<String?>()
    private var loginInfo: LoginInfo? = null
    private var hashTokenRequest: HashedToken.Mechanism? = null
    private var redirectionUrl: HttpUrl? = null
    private var verifiedHostname: String? = null
    private var currentResolverResult: Resolver.Result? = null
    private var seeOtherHostResolverResult: Resolver.Result? = null
    @Volatile private var mThread: Thread? = null
    private var mStreamCountDownLatch: CountDownLatch? = null
    private var dane = false

    fun daneVerified(): Boolean = dane

    fun resolverAuthenticated(): Boolean {
        val currentResolverResult = this.currentResolverResult ?: return false
        return currentResolverResult.isAuthenticated()
    }

    private fun changeStatus(nextStatus: AccountRef.StateRef) {
        synchronized(this) {
            if (Thread.currentThread().isInterrupted) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: not changing status to " +
                        "$nextStatus because thread was interrupted",
                )
                return
            }
            if (account.getStatusRef() != nextStatus) {
                if (nextStatus == AccountRef.StateRef.OFFLINE &&
                    account.getStatusRef() != AccountRef.StateRef.CONNECTING &&
                    account.getStatusRef() != AccountRef.StateRef.ONLINE &&
                    account.getStatusRef() != AccountRef.StateRef.DISABLED &&
                    account.getStatusRef() != AccountRef.StateRef.LOGGED_OUT
                ) {
                    return
                }
                if (nextStatus == AccountRef.StateRef.ONLINE) {
                    this.attempt = 0
                }
                account.setStatusRef(nextStatus)
            } else {
                return
            }
        }
        statusListener?.onStatusChanged(account)
    }

    fun getJidForCommand(node: String): Jid? = synchronized(this.commands) { this.commands[node] }

    fun prepareNewConnection() {
        this.lastConnectionStarted = SystemClock.elapsedRealtime()
        this.lastPingSent = SystemClock.elapsedRealtime()
        this.lastDiscoStarted = Long.MAX_VALUE
        this.mWaitingForSmCatchup.set(false)
        this.changeStatus(AccountRef.StateRef.CONNECTING)
    }

    fun isWaitingForSmCatchup(): Boolean = mWaitingForSmCatchup.get()

    fun incrementSmCatchupMessageCounter() {
        this.mSmCatchupMessageCounter.incrementAndGet()
    }

    protected fun connect() {
        if (mXmppConnectionService.areMessagesInitialized()) {
            mXmppConnectionService.resetSendingToWaiting(account)
        }
        Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: connecting")
        this.streamFeatures = null
        this.pendingResumeId.set(null)
        this.loginInfo = null
        this.features.encryptionEnabled = false
        this.inSmacksSession = false
        this.quickStartInProgress = false
        this.isBound = false
        this.attempt++
        this.dane = false
        this.currentResolverResult = null
        // will be set if user entered hostname is being used or hostname was verified with dnssec
        this.verifiedHostname = null
        try {
            var localSocket: Socket
            shouldAuthenticate = !account.isOptionSet(AccountRef.OPTION_REGISTER)
            this.changeStatus(AccountRef.StateRef.CONNECTING)
            val useTorSetting = appSettings.isUseTor()
            val extended = appSettings.isExtendedConnectionOptions()
            val useTor = useTorSetting || account.isOnion()
            val useI2P = mXmppConnectionService.useI2PToConnect() || account.isI2P()
            // TODO collapse Tor usage into normal connection code path
            if (useTor && !useI2P) {
                val seeOtherHost = this.seeOtherHostResolverResult
                val hostname = account.getHostname().trim()
                val port = account.getPort()
                val resume = streamId?.location
                val viaTor: Resolver.Result
                if (resume != null) {
                    viaTor = resume
                } else if (seeOtherHost != null) {
                    viaTor = seeOtherHost
                } else if (hostname.isEmpty() || port < 0) {
                    viaTor =
                        Iterables.getOnlyElement(
                            Resolver.fromHardCoded(
                                account.getServer(),
                                Resolver.XMPP_PORT_STARTTLS,
                            ),
                        )
                } else {
                    if (useTorSetting || extended) {
                        // if the hostname configuration is showing we can take it
                        viaTor = Iterables.getOnlyElement(Resolver.fromHardCoded(hostname, port))
                    } else {
                        viaTor =
                            Iterables.getOnlyElement(
                                Resolver.fromHardCoded(
                                    account.getServer(),
                                    Resolver.XMPP_PORT_STARTTLS,
                                ),
                            )
                    }
                    this.verifiedHostname = hostname
                }

                Log.d(Config.LOGTAG, "${account.getJid().asBareJid()} via Tor: $viaTor")

                localSocket =
                    SocksSocketFactory.createSocketOverTor(
                        viaTor.asDestination(),
                        viaTor.getPort(),
                    )

                if (viaTor.isDirectTls()) {
                    localSocket = upgradeSocketToTls(localSocket)
                    features.encryptionEnabled = true
                }

                try {
                    if (startXmpp(localSocket)) {
                        this.currentResolverResult = viaTor
                        this.seeOtherHostResolverResult = null
                    }
                } catch (e: InterruptedException) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: thread was interrupted before " +
                            "beginning stream",
                    )
                    return
                } catch (e: Exception) {
                    throw IOException("Could not start stream", e)
                }
            } else if (useI2P) {
                val destination: String
                if (account.getHostname().isEmpty() || account.isI2P()) {
                    destination = account.getServer()
                } else {
                    destination = account.getHostname()
                    this.verifiedHostname = destination
                }

                val port = account.getPort()
                val directTls = Resolver.useDirectTls(port)

                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: connect to $destination via I2P. " +
                        "directTls=$directTls",
                )
                localSocket = SocksSocketFactory.createSocketOverI2P(destination, port)

                if (directTls) {
                    localSocket = upgradeSocketToTls(localSocket)
                    features.encryptionEnabled = true
                }

                try {
                    startXmpp(localSocket)
                } catch (e: InterruptedException) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: thread was interrupted before " +
                            "beginning stream",
                    )
                    return
                } catch (e: Exception) {
                    throw IOException(e.message)
                }
            } else {
                val hostname = account.getHostname().trim()
                val domain = account.getServer()
                val results = ArrayList<Resolver.Result>()
                val hardcoded = extended && hostname.isNotEmpty()
                if (hardcoded) {
                    results.addAll(Resolver.fromHardCoded(hostname, account.getPort()))
                } else {
                    results.addAll(Resolver.resolve(domain))
                }
                if (Thread.currentThread().isInterrupted) {
                    Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: Thread was interrupted")
                    return
                }
                if (results.isEmpty()) {
                    Log.e(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: Resolver results were empty",
                    )
                    return
                }
                val storedBackupResult: Resolver.Result?
                if (hardcoded) {
                    storedBackupResult = null
                } else {
                    storedBackupResult = (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).findResolverResult(domain)
                    if (storedBackupResult != null && !results.contains(storedBackupResult)) {
                        results.add(storedBackupResult)
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: loaded backup resolver result " +
                                "from db: $storedBackupResult",
                        )
                    }
                }
                val resumeLocation = streamId?.location
                if (resumeLocation != null) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: injected resume location on position 0",
                    )
                    results.add(0, resumeLocation)
                }
                val seeOtherHost = this.seeOtherHostResolverResult
                if (seeOtherHost != null) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: injected see-other-host on position 0",
                    )
                    results.add(0, seeOtherHost)
                }
                val iterator = results.iterator()
                while (iterator.hasNext()) {
                    val result = iterator.next()
                    if (Thread.currentThread().isInterrupted) {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: Thread was interrupted",
                        )
                        return
                    }
                    try {
                        // if tls is true, encryption is implied and must not be started
                        features.encryptionEnabled = result.isDirectTls()
                        verifiedHostname =
                            if (result.isAuthenticated()) result.getHostname().toString() else null
                        val addr: InetSocketAddress
                        val resolvedIp = result.getIp()
                        if (resolvedIp != null) {
                            addr = InetSocketAddress(resolvedIp, result.getPort())
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: using values from resolver " +
                                    (if (result.getHostname() == null) {
                                        ""
                                    } else {
                                        result.getHostname().toString() + "/"
                                    }) +
                                    resolvedIp.getHostAddress() +
                                    ":" +
                                    result.getPort() +
                                    " tls: " +
                                    features.encryptionEnabled,
                            )
                        } else {
                            addr =
                                InetSocketAddress(
                                    IDN.toASCII(result.getHostname().toString()),
                                    result.getPort(),
                                )
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: using values from resolver " +
                                    result.getHostname().toString() +
                                    ":" +
                                    result.getPort() +
                                    " tls: " +
                                    features.encryptionEnabled,
                            )
                        }

                        localSocket = Socket()
                        localSocket.connect(addr, Config.SOCKET_TIMEOUT * 1000)
                        localSocket.setSoTimeout(Config.SOCKET_TIMEOUT * 1000)
                        if (features.encryptionEnabled) {
                            localSocket = upgradeSocketToTls(localSocket)
                        }
                        if (startXmpp(localSocket)) {
                            // reset to 0; once the connection is established we don't want this
                            localSocket.setSoTimeout(0)
                            if (!hardcoded && result != storedBackupResult) {
                                (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).saveResolverResult(
                                    domain,
                                    result,
                                )
                            }
                            this.currentResolverResult = result
                            this.seeOtherHostResolverResult = null
                            break // successfully connected to server that speaks xmpp
                        } else {
                            XmppConnectionService.dataStatics().close(localSocket)
                            throw StateChangingException(
                                AccountRef.StateRef.STREAM_OPENING_ERROR,
                            )
                        }
                    } catch (e: StateChangingException) {
                        if (!iterator.hasNext()) {
                            throw e
                        }
                    } catch (e: InterruptedException) {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: thread was interrupted before " +
                                "beginning stream",
                        )
                        return
                    } catch (e: Throwable) {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: ${e.message}(${e.javaClass.name})",
                        )
                        if (!iterator.hasNext()) {
                            throw UnknownHostException()
                        }
                    }
                }
            }
            processStream()
        } catch (e: SecurityException) {
            this.changeStatus(AccountRef.StateRef.MISSING_INTERNET_PERMISSION)
        } catch (e: StateChangingException) {
            this.changeStatus(e.state)
        } catch (e: UnknownHostException) {
            this.changeStatus(AccountRef.StateRef.SERVER_NOT_FOUND)
        } catch (e: ConnectException) {
            this.changeStatus(AccountRef.StateRef.SERVER_NOT_FOUND)
        } catch (e: SocksSocketFactory.HostNotFoundException) {
            this.changeStatus(AccountRef.StateRef.SERVER_NOT_FOUND)
        } catch (e: SocksSocketFactory.SocksProxyNotFoundException) {
            if (account.isI2P() ||
                mXmppConnectionService.getBooleanPreference("use_i2p", R.bool.use_i2p)
            ) {
                this.changeStatus(AccountRef.StateRef.I2P_NOT_AVAILABLE)
            } else {
                this.changeStatus(AccountRef.StateRef.TOR_NOT_AVAILABLE)
            }
        } catch (e: XmlReader.XmlMaxDepthReachedException) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: elements in XML stream reached maximum depth",
            )
            this.changeStatus(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: error reading XML stream", e)
            this.changeStatus(AccountRef.StateRef.OFFLINE)
            this.attempt = Math.max(0, this.attempt - 1)
        } catch (e: XmlPullParserException) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: error reading XML stream", e)
            this.changeStatus(AccountRef.StateRef.OFFLINE)
            this.attempt = Math.max(0, this.attempt - 1)
        } finally {
            if (!Thread.currentThread().isInterrupted) {
                forceCloseSocket()
            } else {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: not force closing socket because thread " +
                        "was interrupted",
                )
            }
        }
    }

    /**
     * Starts xmpp protocol, call after connecting to socket
     *
     * @return true if server returns with valid xmpp, false otherwise
     */
    private fun startXmpp(socket: Socket): Boolean {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException()
        }
        // this means we have at least found a socket to connect to. give the connection another 90s
        this.lastConnectionStarted = SystemClock.elapsedRealtime()
        this.socket = socket
        val reader = XmlReader()
        this.tagReader = reader
        tagWriter.forceClose()
        this.tagWriter = TagWriter()
        this.tagWriter.setOutputStream(socket.getOutputStream())
        reader.setInputStream(socket.getInputStream())
        this.tagWriter.beginDocument()
        val quickStart: Boolean
        if (socket is SSLSocket) {
            SSLSockets.log(account, socket)
            quickStart = establishStream(SSLSockets.version(socket))
        } else {
            quickStart = establishStream(SSLSockets.Version.NONE)
        }
        val tag = reader.readTag()
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException()
        }
        if (tag == null) {
            return false
        }
        val success = tag.isStart("stream", Namespace.STREAMS)
        if (success) {
            val from = tag.getAttribute("from")
            if (from == null || from != account.getServer()) {
                throw StateChangingException(AccountRef.StateRef.HOST_UNKNOWN)
            }
        }
        if (success && quickStart) {
            this.quickStartInProgress = true
        }
        return success
    }

    private fun getSSLSocketFactory(port: Int, daneCb: Consumer<Boolean>): SSLSocketFactory {
        val sc = SSLSockets.getSSLContext()
        val trustManager = this.mXmppConnectionService.getMemorizingTrustManager()
        val keyManager: Array<KeyManager>?
        if (account.getPrivateKeyAlias() != null) {
            keyManager = arrayOf<KeyManager>(MyKeyManager(account, mXmppConnectionService))
        } else {
            keyManager = null
        }
        val domain = account.getServer()
        val enforceDane = isDANEnforced()
        sc.init(
            keyManager,
            arrayOf<TrustManager>(
                if (mInteractive) {
                    trustManager.getInteractive(domain, verifiedHostname, port, daneCb, enforceDane)
                } else {
                    trustManager.getNonInteractive(
                        domain,
                        verifiedHostname,
                        port,
                        daneCb,
                        enforceDane,
                    )
                },
            ),
            Random.SECURE_RANDOM,
        )
        return sc.getSocketFactory()
    }

    override fun run() {
        synchronized(this) {
            val thread = Thread.currentThread()
            this.mThread = thread
            if (thread.isInterrupted) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: aborting connect because thread was " +
                        "interrupted",
                )
                return
            }
            forceCloseSocket()
        }
        connect()
    }

    private fun processStream() {
        val streamCountDownLatch = CountDownLatch(1)
        this.mStreamCountDownLatch = streamCountDownLatch
        val reader = tagReader ?: throw NullPointerException("tagReader")
        var nextTag = reader.readTag()
        while (nextTag != null && !nextTag.isEnd("stream")) {
            if (nextTag.isStart("error", Namespace.STREAMS)) {
                processStreamError(reader.readElement(nextTag, StreamError::class.java))
            } else if (nextTag.isStart("features", Namespace.STREAMS)) {
                processStreamFeatures(nextTag)
            } else if (nextTag.isStart("proceed", Namespace.TLS)) {
                if (this.socket is SSLSocket) {
                    throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
                }
                switchOverToTls(nextTag)
            } else if (nextTag.isStart("failure", Namespace.TLS)) {
                throw StateChangingException(AccountRef.StateRef.TLS_ERROR)
            } else if (!isSecure()) {
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
            } else if (account.isOptionSet(AccountRef.OPTION_REGISTER) &&
                nextTag.isStart("iq", Namespace.JABBER_CLIENT)
            ) {
                processIq(nextTag)
            } else if (this.loginInfo == null) {
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
            } else if (nextTag.isStart("success", Namespace.SASL)) {
                processSuccess(reader.readElement(nextTag, Success::class.java))
                break
            } else if (nextTag.isStart("success", Namespace.SASL_2)) {
                processSuccess(reader.readElement(nextTag, Sasl2Success::class.java))
            } else if (nextTag.isStart("failure", Namespace.SASL)) {
                processFailure(reader.readElement(nextTag, Failure::class.java))
            } else if (nextTag.isStart("failure", Namespace.SASL_2)) {
                processFailure(reader.readElement(nextTag, Sasl2Failure::class.java))
            } else if (nextTag.isStart("continue", Namespace.SASL_2)) {
                // two step sasl2 - we don’t support this yet
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_CLIENT)
            } else if (nextTag.isStart("challenge")) {
                processChallenge(reader.readElement(nextTag))
            } else if (!LoginInfo.isSuccess(this.loginInfo)) {
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
            } else if (this.streamId != null &&
                nextTag.isStart("resumed", Namespace.STREAM_MANAGEMENT)
            ) {
                processResumed(reader.readElement(nextTag, Resumed::class.java))
            } else if (nextTag.isStart("failed", Namespace.STREAM_MANAGEMENT)) {
                processFailed(reader.readElement(nextTag, Failed::class.java), true)
            } else if (nextTag.isStart("iq", Namespace.JABBER_CLIENT)) {
                processIq(nextTag)
            } else if (!isBound) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: server sent unexpected" + nextTag.identifier(),
                )
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
            } else if (nextTag.isStart("message", Namespace.JABBER_CLIENT)) {
                processMessage(nextTag)
            } else if (nextTag.isStart("presence", Namespace.JABBER_CLIENT)) {
                processPresence(nextTag)
            } else if (nextTag.isStart("enabled", Namespace.STREAM_MANAGEMENT)) {
                processEnabled(reader.readElement(nextTag, Enabled::class.java))
            } else if (nextTag.isStart("r", Namespace.STREAM_MANAGEMENT)) {
                reader.readElement(nextTag)
                if (Config.EXTENDED_SM_LOGGING) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: acknowledging stanza #" +
                            this.stanzasReceived,
                    )
                }
                val ack = Ack(this.stanzasReceived)
                tagWriter.writeStanzaAsync(ack)
            } else if (nextTag.isStart("a", Namespace.STREAM_MANAGEMENT)) {
                var accountUiNeedsRefresh = false
                synchronized(mXmppConnectionService.getNotificationService().catchupLock()) {
                    if (mWaitingForSmCatchup.compareAndSet(true, false)) {
                        val messageCount = mSmCatchupMessageCounter.get()
                        val pendingIQs = packetCallbacks.size
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: SM catchup complete " +
                                "(messages=$messageCount, pending IQs=$pendingIQs)",
                        )
                        accountUiNeedsRefresh = true
                        if (messageCount > 0) {
                            mXmppConnectionService
                                .getNotificationService()
                                .finishBacklog(true, account)
                        }
                    }
                }
                if (accountUiNeedsRefresh) {
                    mXmppConnectionService.updateAccountUi()
                }
                val ack = reader.readElement(nextTag, Ack::class.java)
                lastPacketReceived = SystemClock.elapsedRealtime()
                val acknowledgedMessages: Boolean
                synchronized(this.mStanzaQueue) {
                    val serverSequence = ack.getHandled()
                    if (serverSequence.isPresent) {
                        acknowledgedMessages = acknowledgeStanzaUpTo(serverSequence.get())
                    } else {
                        acknowledgedMessages = false
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: server send ack without sequence " +
                                "number",
                        )
                    }
                }
                if (acknowledgedMessages) {
                    mXmppConnectionService.updateConversationUi()
                }
            } else {
                Log.e(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: Encountered unknown stream element" +
                        nextTag.identifier(),
                )
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
            }
            nextTag = reader.readTag()
        }
        if (nextTag != null && nextTag.isEnd("stream")) {
            streamCountDownLatch.countDown()
        }
    }

    private fun processChallenge(challenge: Element) {
        val version: SaslMechanism.Version
        try {
            version = SaslMechanism.Version.of(challenge)
        } catch (e: IllegalArgumentException) {
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        val response: StreamElement
        if (version == SaslMechanism.Version.SASL) {
            response = Response()
        } else if (version == SaslMechanism.Version.SASL_2) {
            response = Sasl2Response()
        } else {
            throw AssertionError("Missing implementation for $version")
        }
        val currentLoginInfo = this.loginInfo
        if (currentLoginInfo == null || LoginInfo.isSuccess(currentLoginInfo)) {
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        try {
            response.setContent(
                currentLoginInfo.saslMechanism.getResponse(
                    challenge.getContent(),
                    sslSocketOrNull(socket),
                ),
            )
        } catch (e: SaslMechanism.AuthenticationException) {
            // TODO: Send auth abort tag.
            Log.e(Config.LOGTAG, e.toString())
            throw StateChangingException(AccountRef.StateRef.UNAUTHORIZED)
        }
        tagWriter.writeElement(response)
    }

    private fun processSuccess(element: StreamElement) {
        val currentLoginInfo = this.loginInfo
        val currentSaslMechanism = LoginInfo.mechanism(currentLoginInfo)
        if (currentLoginInfo == null ||
            LoginInfo.isSuccess(currentLoginInfo) ||
            currentSaslMechanism == null
        ) {
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        val version: SaslMechanism.Version
        val challenge: String?
        if (element is Success) {
            challenge = element.getContent()
            version = SaslMechanism.Version.SASL
        } else if (element is Sasl2Success) {
            challenge = element.findChildContent("additional-data")
            version = SaslMechanism.Version.SASL_2
        } else {
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        try {
            currentLoginInfo.success(challenge, sslSocketOrNull(socket))
        } catch (e: SaslMechanism.AuthenticationException) {
            Log.e(Config.LOGTAG, "${account.getJid().asBareJid()}: authentication failure ", e)
            throw StateChangingException(AccountRef.StateRef.UNAUTHORIZED)
        }
        Log.d(
            Config.LOGTAG,
            "${account.getJid().asBareJid()}: logged in (using $version)",
        )
        if (SaslMechanism.pin(currentSaslMechanism)) {
            account.setPinnedMechanism(currentSaslMechanism)
        }
        if (element is Sasl2Success) {
            val authorizationJid = element.getAuthorizationIdentifier()
            checkAssignedDomainOrThrow(authorizationJid)
            val assignedJid = authorizationJid ?: throw NullPointerException("authorizationJid")
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: SASL 2.0 authorization identifier was " +
                    "$assignedJid",
            )
            // TODO this should only happen when we used Bind 2
            if (assignedJid.isFullJid() && account.setJid(assignedJid)) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: jid changed during SASL 2.0. updating " +
                        "database",
                )
            }
            val bound = element.getExtension(Bound::class.java)
            val resumed = element.getExtension(Resumed::class.java)
            val failed = element.getExtension(Failed::class.java)
            val tokenWrapper = element.findChild("token", Namespace.FAST)
            val token = tokenWrapper?.getAttribute("token")
            if (bound != null && resumed != null) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: server sent bound and resumed in SASL2 " +
                        "success",
                )
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
            }
            if (resumed != null && streamId != null) {
                if (this.boundStreamFeatures != null) {
                    this.streamFeatures = this.boundStreamFeatures
                    Log.d(
                        Config.LOGTAG,
                        "putting previous stream features back in place: " +
                            XmlHelper.printElementNames(this.boundStreamFeatures),
                    )
                }
                processResumed(resumed)
            } else if (failed != null) {
                processFailed(failed, false) // wait for new stream features
            }
            if (bound != null) {
                clearIqCallbacks()
                this.isBound = true
                processNopStreamFeatures()
                this.boundStreamFeatures = this.streamFeatures
                val streamManagementEnabled = bound.getExtension(Enabled::class.java)
                val carbonsEnabled = bound.findChild("enabled", Namespace.CARBONS)
                val waitForDisco: Boolean
                if (streamManagementEnabled != null) {
                    resetOutboundStanzaQueue()
                    processEnabled(streamManagementEnabled)
                    waitForDisco = true
                } else {
                    // if we did not enable stream management in bind do it now
                    waitForDisco = enableStreamManagement()
                }
                val negotiatedCarbons: Boolean
                if (carbonsEnabled != null) {
                    negotiatedCarbons = true
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: successfully enabled carbons " +
                            "(via Bind 2.0)",
                    )
                    features.carbonsEnabled = true
                } else if (currentLoginInfo.inlineBindFeatures.contains(Namespace.CARBONS)) {
                    negotiatedCarbons = true
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: successfully enabled carbons " +
                            "(via Bind 2.0/implicit)",
                    )
                    features.carbonsEnabled = true
                } else {
                    negotiatedCarbons = false
                }
                sendPostBindInitialization(waitForDisco, negotiatedCarbons)
            }
            val tokenMechanism: HashedToken.Mechanism?
            if (SaslMechanism.hashedToken(currentSaslMechanism)) {
                tokenMechanism = (currentSaslMechanism as HashedToken).getTokenMechanism()
            } else if (this.hashTokenRequest != null) {
                tokenMechanism = this.hashTokenRequest
            } else {
                tokenMechanism = null
            }
            if (tokenMechanism != null && !token.isNullOrEmpty()) {
                if (ChannelBinding.priority(tokenMechanism.channelBinding) >=
                    ChannelBindingMechanism.getPriority(currentSaslMechanism)
                ) {
                    this.account.setFastToken(tokenMechanism, token)
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: storing hashed token $tokenMechanism",
                    )
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: not accepting hashed token " +
                            "${tokenMechanism.name()} for log in mechanism " +
                            currentSaslMechanism.getMechanism(),
                    )
                    this.account.resetFastToken()
                }
            } else if (this.hashTokenRequest != null) {
                Log.w(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: no response to our hashed token request " +
                        this.hashTokenRequest,
                )
            }
        }
        (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
        this.quickStartInProgress = false
        if (version == SaslMechanism.Version.SASL) {
            val reader = tagReader ?: throw NullPointerException("tagReader")
            reader.reset()
            sendStartStream(false, true)
            val tag = reader.readTag()
            if (tag != null && tag.isStart("stream", Namespace.STREAMS)) {
                processStream()
            } else {
                throw StateChangingException(AccountRef.StateRef.STREAM_OPENING_ERROR)
            }
        }
    }

    private fun resetOutboundStanzaQueue() {
        synchronized(this.mStanzaQueue) {
            val intermediateStanzas = ArrayList<Stanza>()
            if (Config.EXTENDED_SM_LOGGING) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: stanzas sent before auth: " +
                        this.stanzasSentBeforeAuthentication,
                )
            }
            for (i in this.stanzasSentBeforeAuthentication + 1..this.stanzasSent) {
                val stanza = this.mStanzaQueue.get(i)
                if (stanza != null) {
                    intermediateStanzas.add(stanza)
                }
            }
            this.mStanzaQueue.clear()
            for (i in intermediateStanzas.indices) {
                this.mStanzaQueue.append(i + 1, intermediateStanzas[i])
            }
            this.stanzasSent = intermediateStanzas.size
            if (Config.EXTENDED_SM_LOGGING) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: resetting outbound stanza queue to " +
                        this.stanzasSent,
                )
            }
        }
    }

    private fun processNopStreamFeatures() {
        val reader = tagReader ?: throw NullPointerException("tagReader")
        val tag = reader.readTag()
        if (tag != null && tag.isStart("features", Namespace.STREAMS)) {
            this.streamFeatures = reader.readElement(tag, StreamFeatures::class.java)
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: processed NOP stream features after success: " +
                    XmlHelper.printElementNames(this.streamFeatures),
            )
        } else {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: received $tag")
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: server did not send stream features after " +
                    "SASL2 success",
            )
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
    }

    private fun processFailure(failure: AuthenticationFailure) {
        val version: SaslMechanism.Version
        try {
            version = SaslMechanism.Version.of(failure)
        } catch (e: IllegalArgumentException) {
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }

        val currentLoginInfo = this.loginInfo
        if (currentLoginInfo == null || LoginInfo.isSuccess(currentLoginInfo)) {
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }

        Log.d(Config.LOGTAG, failure.toString())
        Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: login failure $version")
        val resetTokenMechanism = LoginInfo.mechanism(currentLoginInfo)
        if (resetTokenMechanism != null && SaslMechanism.hashedToken(resetTokenMechanism)) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: resetting token")
            account.resetFastToken()
            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
        }
        val errorCondition = failure.getErrorCondition()
        if (errorCondition is SaslError.InvalidMechanism ||
            errorCondition is SaslError.MechanismTooWeak
        ) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: invalid or too weak mechanism. resetting " +
                    "quick start",
            )
            if (account.setOption(AccountRef.OPTION_QUICKSTART_AVAILABLE, false)) {
                (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
            }
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        } else if (errorCondition is SaslError.TemporaryAuthFailure) {
            throw StateChangingException(AccountRef.StateRef.TEMPORARY_AUTH_FAILURE)
        } else if (errorCondition is SaslError.AccountDisabled) {
            val text = failure.getText()
            if (text.isNullOrEmpty()) {
                throw StateChangingException(AccountRef.StateRef.UNAUTHORIZED)
            }
            val matcher = XmppConnectionService.dataStatics().autolinkWebUrl().matcher(text)
            if (matcher.find()) {
                val url: HttpUrl
                try {
                    url = text.substring(matcher.start(), matcher.end()).toHttpUrl()
                } catch (e: IllegalArgumentException) {
                    throw StateChangingException(AccountRef.StateRef.UNAUTHORIZED)
                }
                if (url.isHttps) {
                    this.redirectionUrl = url
                    throw StateChangingException(AccountRef.StateRef.PAYMENT_REQUIRED)
                }
            }
        }
        val fallbackMechanism = LoginInfo.mechanism(currentLoginInfo)
        if (fallbackMechanism != null && SaslMechanism.hashedToken(fallbackMechanism)) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: fast authentication failed. falling back to " +
                    "regular authentication",
            )
            this.loginInfo = null
            authenticate()
        } else {
            throw StateChangingException(AccountRef.StateRef.UNAUTHORIZED)
        }
    }

    private fun processEnabled(enabled: Enabled) {
        val streamId = getStreamId(enabled)
        if (streamId == null) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: stream management enabled")
        } else {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: stream management enabled. resume at: " +
                    streamId.location,
            )
        }
        this.streamId = streamId
        this.stanzasReceived = 0
        this.inSmacksSession = true
        val r = Request()
        tagWriter.writeStanzaAsync(r)
    }

    private fun getStreamId(enabled: Enabled): StreamId? {
        val id = enabled.getResumeId()
        val locationAttribute = enabled.getLocation()
        val currentResolverResult = this.currentResolverResult
        val location: Resolver.Result?
        if (locationAttribute.isNullOrEmpty() || currentResolverResult == null) {
            location = null
        } else {
            location = currentResolverResult.seeOtherHost(locationAttribute)
        }
        return if (id.isPresent) StreamId(id.get(), location) else null
    }

    private fun processResumed(resumed: Resumed) {
        val pendingResumeId = this.pendingResumeId.getAndSet(null)
        val prevId = resumed.getPrevId()
        if (prevId == null || prevId != pendingResumeId) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: server tried resume with unknown id $prevId",
            )
            resetStreamId()
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        this.inSmacksSession = true
        this.isBound = true
        this.tagWriter.writeStanzaAsync(Request())
        lastPacketReceived = SystemClock.elapsedRealtime()
        val h = resumed.getHandled()
        val serverCount: Int
        if (h.isPresent) {
            serverCount = h.get()
        } else {
            resetStreamId()
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        val failedStanzas = ArrayList<Stanza>()
        val acknowledgedMessages: Boolean
        synchronized(this.mStanzaQueue) {
            if (serverCount < stanzasSent) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: session resumed with lost packages",
                )
                stanzasSent = serverCount
            } else {
                Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: session resumed")
            }
            acknowledgedMessages = acknowledgeStanzaUpTo(serverCount)
            for (i in 0 until this.mStanzaQueue.size()) {
                failedStanzas.add(mStanzaQueue.valueAt(i))
            }
            mStanzaQueue.clear()
        }
        if (acknowledgedMessages) {
            mXmppConnectionService.updateConversationUi()
        }
        Log.d(
            Config.LOGTAG,
            "${account.getJid().asBareJid()}: resending ${failedStanzas.size} stanzas",
        )
        for (packet in failedStanzas) {
            if (packet is Message) {
                mXmppConnectionService.markMessage(
                    account,
                    (packet.getTo() ?: throw NullPointerException()).asBareJid(),
                    packet.getId(),
                    MessageRef.STATUS_UNSEND,
                )
            }
            sendPacket(packet)
        }
        if (mWaitForDisco.get()) {
            this.lastDiscoStarted = SystemClock.elapsedRealtime()
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: awaiting disco results after resume",
            )
            changeStatus(AccountRef.StateRef.CONNECTING)
        } else {
            changeStatusToOnline()
        }
    }

    private fun changeStatusToOnline() {
        if (isDANEnforced()) {
            if (daneVerified()) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: online with enforced DANE with resource " +
                        account.getResource(),
                )
                changeStatus(AccountRef.StateRef.ONLINE)
            } else {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: offline with enforced DANE with resource " +
                        account.getResource(),
                )
                changeStatus(AccountRef.StateRef.DANE_FAILED)
                disconnect(false)
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: online with enforced DANE disabled with " +
                    "resource ${account.getResource()}",
            )
            changeStatus(AccountRef.StateRef.ONLINE)
        }
    }

    private fun processFailed(failed: Failed, sendBindRequest: Boolean) {
        val serverCount = failed.getHandled()
        if (serverCount.isPresent) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: resumption failed but server acknowledged " +
                    "stanza #${serverCount.get()}",
            )
            val acknowledgedMessages: Boolean
            synchronized(this.mStanzaQueue) {
                acknowledgedMessages = acknowledgeStanzaUpTo(serverCount.get())
            }
            if (acknowledgedMessages) {
                mXmppConnectionService.updateConversationUi()
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: resumption failed " +
                    "(${XmlHelper.print(failed.getChildren())})",
            )
        }
        resetStreamId()
        if (sendBindRequest) {
            sendBindRequest()
        }
    }

    private fun acknowledgeStanzaUpTo(serverCount: Int): Boolean {
        if (serverCount > stanzasSent) {
            Log.e(
                Config.LOGTAG,
                "server acknowledged more stanzas than we sent. serverCount=$serverCount, " +
                    "ourCount=$stanzasSent",
            )
        }
        var acknowledgedMessages = false
        var i = 0
        while (i < mStanzaQueue.size()) {
            if (serverCount >= mStanzaQueue.keyAt(i)) {
                if (Config.EXTENDED_SM_LOGGING) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: server acknowledged stanza #" +
                            mStanzaQueue.keyAt(i),
                    )
                }
                val stanza = mStanzaQueue.valueAt(i)
                val listener = acknowledgedListener
                if (stanza is Message && listener != null) {
                    val id = stanza.getId()
                    val to = stanza.getTo()
                    if (id != null && to != null) {
                        acknowledgedMessages =
                            acknowledgedMessages or listener.onMessageAcknowledged(account, to, id)
                    }
                }
                mStanzaQueue.removeAt(i)
                i--
            }
            i++
        }
        return acknowledgedMessages
    }

    private fun <S : Stanza> processPacket(currentTag: Tag, clazz: Class<S>): S {
        val reader = tagReader ?: throw NullPointerException("tagReader")
        val stanza = reader.readElement(currentTag, clazz)
        if (stanzasReceived == Int.MAX_VALUE) {
            resetStreamId()
            throw IOException("time to restart the session. cant handle >2 billion pcks")
        }
        if (inSmacksSession) {
            ++stanzasReceived
        } else if (features.sm()) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: not counting stanza(" +
                    "${stanza.javaClass.simpleName}). Not in smacks session.",
            )
        }
        lastPacketReceived = SystemClock.elapsedRealtime()
        if (Config.BACKGROUND_STANZA_LOGGING && mXmppConnectionService.checkListeners()) {
            Log.d(Config.LOGTAG, "[background stanza] $stanza")
        }
        return stanza
    }

    private fun processIq(currentTag: Tag) {
        val packet = processPacket(currentTag, Iq::class.java)
        if (packet.isInvalid()) {
            Log.e(
                Config.LOGTAG,
                "encountered invalid iq from='${packet.getFrom()}' to='${packet.getTo()}'",
            )
            return
        }
        if (Thread.currentThread().isInterrupted) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}Not processing iq. Thread was interrupted",
            )
            return
        }
        if (packet.hasExtension(Jingle::class.java) &&
            packet.getType() == Iq.Type.SET &&
            isBound &&
            LoginInfo.isSuccess(this.loginInfo)
        ) {
            jingleListener?.onJinglePacketReceived(account, packet)
        } else {
            val callback = getIqPacketReceivedCallback(packet)
            if (callback == null) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: no callback registered for IQ from " +
                        packet.getFrom(),
                )
                return
            }
            val timeoutFuture = callback.second
            try {
                if (timeoutFuture == null || timeoutFuture.cancel(false)) {
                    callback.first.accept(packet)
                }
            } catch (error: StateChangingError) {
                throw StateChangingException(error.state)
            }
        }
    }

    private fun getIqPacketReceivedCallback(stanza: Iq): Pair<Consumer<Iq>, ScheduledFuture<*>?>? {
        val isRequest = stanza.getType() == Iq.Type.GET || stanza.getType() == Iq.Type.SET
        if (isRequest) {
            if (isBound && LoginInfo.isSuccess(this.loginInfo)) {
                return Pair(this.unregisteredIqListener, null)
            } else {
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
            }
        } else {
            synchronized(this.packetCallbacks) {
                val pair = packetCallbacks[stanza.getId()]
                if (pair == null) {
                    return null
                }
                if (pair.first.toServer(account)) {
                    if (stanza.fromServer(account)) {
                        packetCallbacks.remove(stanza.getId())
                        return pair.second
                    } else {
                        Log.e(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: ignoring spoofed iq packet",
                        )
                    }
                } else {
                    if (stanza.getFrom() != null && stanza.getFrom() == pair.first.getTo()) {
                        packetCallbacks.remove(stanza.getId())
                        return pair.second
                    } else {
                        Log.e(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: ignoring spoofed iq packet",
                        )
                    }
                }
            }
        }
        return null
    }

    private fun processMessage(currentTag: Tag) {
        val packet = processPacket(currentTag, Message::class.java)
        if (packet.isInvalid()) {
            Log.e(
                Config.LOGTAG,
                "encountered invalid message from='${packet.getFrom()}' to='${packet.getTo()}'",
            )
            return
        }
        if (Thread.currentThread().isInterrupted) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}Not processing message. Thread was interrupted",
            )
            return
        }
        this.messageListener.accept(packet)
    }

    private fun processPresence(currentTag: Tag) {
        val packet = processPacket(currentTag, Presence::class.java)
        if (packet.isInvalid()) {
            Log.e(
                Config.LOGTAG,
                "encountered invalid presence from='${packet.getFrom()}' to='${packet.getTo()}'",
            )
            return
        }
        if (Thread.currentThread().isInterrupted) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}Not processing presence. Thread was interrupted",
            )
            return
        }
        this.presenceListener.accept(packet)
    }

    private fun sendStartTLS() {
        tagWriter.writeElement(StartTls())
    }

    private fun switchOverToTls(currentTag: Tag) {
        val reader = tagReader ?: throw NullPointerException("tagReader")
        reader.readElement(currentTag, Proceed::class.java)
        val socket = this.socket
        val sslSocket = upgradeSocketToTls(socket ?: throw NullPointerException("socket"))
        this.socket = sslSocket
        reader.setInputStream(sslSocket.getInputStream())
        this.tagWriter.setOutputStream(sslSocket.getOutputStream())
        Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: TLS connection established")
        val quickStart: Boolean
        try {
            quickStart = establishStream(SSLSockets.version(sslSocket))
        } catch (e: InterruptedException) {
            return
        }
        if (quickStart) {
            this.quickStartInProgress = true
        }
        features.encryptionEnabled = true
        val tag = reader.readTag()
        if (tag != null && tag.isStart("stream", Namespace.STREAMS)) {
            SSLSockets.log(account, sslSocket)
            processStream()
        } else {
            throw StateChangingException(AccountRef.StateRef.STREAM_OPENING_ERROR)
        }
        sslSocket.close()
    }

    private fun certificates(session: SSLSession): Array<X509Certificate> {
        val certs = ArrayList<X509Certificate>()
        for (certificate in session.getPeerCertificates()) {
            if (certificate is X509Certificate) {
                certs.add(certificate)
            }
        }
        return certs.toTypedArray()
    }

    private fun upgradeSocketToTls(socket: Socket): SSLSocket {
        this.dane = false
        val sslSocketFactory: SSLSocketFactory
        try {
            sslSocketFactory = getSSLSocketFactory(socket.getPort()) { d -> this.dane = d }
        } catch (e: NoSuchAlgorithmException) {
            throw StateChangingException(AccountRef.StateRef.TLS_ERROR)
        } catch (e: KeyManagementException) {
            throw StateChangingException(AccountRef.StateRef.TLS_ERROR)
        }
        val address = socket.getInetAddress()
        val sslSocket: SSLSocket
        try {
            sslSocket =
                sslSocketFactory.createSocket(
                    socket,
                    address.getHostAddress(),
                    socket.getPort(),
                    true,
                ) as SSLSocket
        } catch (e: IOException) {
            var cause = e.cause
            while (cause != null) {
                if (cause.javaClass.name.endsWith("DaneEnforcementException")) {
                    throw StateChangingException(AccountRef.StateRef.DANE_FAILED, cause)
                }
                cause = cause.cause
            }
            throw e
        }
        SSLSockets.setSecurity(sslSocket, isRequireTlsV13())
        SSLSockets.setHostname(sslSocket, IDN.toASCII(account.getServer()))
        SSLSockets.setApplicationProtocol(sslSocket, "xmpp-client")
        try {
            if (!dane &&
                !XmppConnectionService
                    .trustPort()
                    .verify(
                        account.getServer(),
                        this.verifiedHostname,
                        sslSocket.getSession(),
                    )
            ) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: TLS certificate domain verification failed",
                )
                XmppConnectionService.dataStatics().close(sslSocket)
                throw StateChangingException(AccountRef.StateRef.TLS_ERROR_DOMAIN)
            }
        } catch (e: SSLPeerUnverifiedException) {
            XmppConnectionService.dataStatics().close(sslSocket)
            throw StateChangingException(AccountRef.StateRef.TLS_ERROR)
        }
        return sslSocket
    }

    private fun processStreamFeatures(currentTag: Tag) {
        val reader = tagReader ?: throw NullPointerException("tagReader")
        val streamFeatures = reader.readElement(currentTag, StreamFeatures::class.java)
        val isSecure = isSecure()
        if (streamFeatures.hasExtension(StartTls::class.java) && !features.encryptionEnabled) {
            sendStartTLS()
            return
        }
        if (isSecure) {
            processSecureStreamFeatures(streamFeatures)
        } else {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: STARTTLS not available " +
                    XmlHelper.printElementNames(streamFeatures),
            )
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
    }

    private fun processSecureStreamFeatures(streamFeatures: StreamFeatures) {
        this.streamFeatures = streamFeatures
        val needsBinding = !isBound && !account.isOptionSet(AccountRef.OPTION_REGISTER)
        if (this.quickStartInProgress) {
            if (streamFeatures.hasStreamFeature(Authentication::class.java)) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: quick start in progress. ignoring " +
                        "features: " +
                        XmlHelper.printElementNames(this.streamFeatures),
                )
                val quickStartMechanism = LoginInfo.mechanism(this.loginInfo)
                if (quickStartMechanism != null && SaslMechanism.hashedToken(quickStartMechanism)) {
                    return
                }
                if (isFastTokenAvailable(streamFeatures.getExtension(Authentication::class.java))) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: fast token available; resetting quick " +
                            "start",
                    )
                    account.setOption(AccountRef.OPTION_QUICKSTART_AVAILABLE, false)
                    (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                }
                return
            }
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: server lost support for SASL 2. quick start " +
                    "not possible",
            )
            this.account.setOption(AccountRef.OPTION_QUICKSTART_AVAILABLE, false)
            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        if (streamFeatures.hasChild("register", Namespace.REGISTER_STREAM_FEATURE) &&
            account.isOptionSet(AccountRef.OPTION_REGISTER)
        ) {
            register()
        } else if (!streamFeatures.hasChild("register", Namespace.REGISTER_STREAM_FEATURE) &&
            account.isOptionSet(AccountRef.OPTION_REGISTER)
        ) {
            throw StateChangingException(AccountRef.StateRef.REGISTRATION_NOT_SUPPORTED)
        } else if (streamFeatures.hasStreamFeature(Authentication::class.java) &&
            shouldAuthenticate &&
            this.loginInfo == null
        ) {
            authenticate(SaslMechanism.Version.SASL_2)
        } else if (streamFeatures.hasStreamFeature(Mechanisms::class.java) &&
            shouldAuthenticate &&
            this.loginInfo == null
        ) {
            authenticate(SaslMechanism.Version.SASL)
        } else if (streamFeatures.streamManagement() &&
            LoginInfo.isSuccess(loginInfo) &&
            streamId != null &&
            !inSmacksSession
        ) {
            if (Config.EXTENDED_SM_LOGGING) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: resuming after stanza #$stanzasReceived",
                )
            }
            val streamId = (this.streamId ?: throw NullPointerException("streamId")).id
            val resume = Resume(streamId, stanzasReceived)
            prepareForResume(streamId)
            this.tagWriter.writeStanzaAsync(resume)
        } else if (needsBinding) {
            val features =
                this.streamFeatures ?: throw NullPointerException("streamFeatures")
            if (features.hasChild("bind", Namespace.BIND) && LoginInfo.isSuccess(loginInfo)) {
                sendBindRequest()
            } else {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: unable to find bind feature " +
                        XmlHelper.printElementNames(this.streamFeatures),
                )
                throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: received NOP stream features: " +
                    XmlHelper.printElementNames(this.streamFeatures),
            )
        }
    }

    private fun authenticate() {
        val isSecure = isSecure()
        if (isSecure &&
            (streamFeatures ?: throw NullPointerException("streamFeatures"))
                .hasStreamFeature(Authentication::class.java)
        ) {
            authenticate(SaslMechanism.Version.SASL_2)
        } else if (isSecure &&
            (streamFeatures ?: throw NullPointerException("streamFeatures"))
                .hasStreamFeature(Mechanisms::class.java)
        ) {
            authenticate(SaslMechanism.Version.SASL)
        } else {
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
    }

    private fun isSecure(): Boolean =
        (features.encryptionEnabled && this.socket is SSLSocket) ||
            Config.ALLOW_NON_TLS_CONNECTIONS ||
            account.isDirectToOnion() ||
            account.isI2P()

    private fun authenticate(version: SaslMechanism.Version) {
        if (this.loginInfo != null) {
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        val streamFeatures = this.streamFeatures ?: throw NullPointerException("streamFeatures")
        val authElement: AuthenticationStreamFeature?
        if (version == SaslMechanism.Version.SASL) {
            authElement = streamFeatures.getExtension(Mechanisms::class.java)
        } else {
            authElement = streamFeatures.getExtension(Authentication::class.java)
        }
        val mechanisms = (authElement ?: throw NullPointerException("authElement")).getMechanismNames()
        val cbExtension = streamFeatures.getExtension(SaslChannelBinding::class.java)
        val channelBindings = ChannelBinding.of(cbExtension)
        val factory = SaslMechanism.Factory(account)
        val currentSocket = this.socket ?: throw NullPointerException("socket")
        val saslMechanism =
            factory.of(mechanisms, channelBindings, version, SSLSockets.version(currentSocket))
        validate(saslMechanism, mechanisms)
        val mechanism = saslMechanism ?: throw NullPointerException("saslMechanism")
        val downgradeProtection: DowngradeProtection
        if (cbExtension != null) {
            downgradeProtection =
                DowngradeProtection(mechanisms, cbExtension.getChannelBindingTypes())
        } else {
            downgradeProtection = DowngradeProtection(mechanisms)
        }
        if (mechanism is ScramMechanism) {
            mechanism.setDowngradeProtection(downgradeProtection)
        }
        val quickStartAvailable: Boolean
        val firstMessage = mechanism.getClientFirstMessage(sslSocketOrNull(this.socket))
        val usingFast = SaslMechanism.hashedToken(mechanism)
        val authenticate: AuthenticationRequest
        val loginInfo: LoginInfo
        if (version == SaslMechanism.Version.SASL) {
            val auth = Auth()
            authenticate = auth
            if (!firstMessage.isNullOrEmpty()) {
                authenticate.setContent(firstMessage)
            }
            quickStartAvailable = false
            loginInfo = LoginInfo(mechanism, version, emptyList())
        } else if (version == SaslMechanism.Version.SASL_2) {
            val authentication = authElement as Authentication
            val inline = authentication.getInline()
            val sm = inline != null && inline.hasExtension(StreamManagement::class.java)
            val hashTokenRequest: HashedToken.Mechanism?
            if (usingFast) {
                hashTokenRequest = null
            } else if (inline != null) {
                hashTokenRequest =
                    HashedToken.Mechanism.best(
                        inline.getFastMechanisms(),
                        SSLSockets.version(currentSocket),
                    )
                // TODO warn or fail early if channel binding priority isn’t high enough compared to
                // login mechanism
                // ChannelBinding.priority(hashTokenRequest.channelBinding)
                //                        <
                // ChannelBindingMechanism.getPriority(saslMechanism)
            } else {
                hashTokenRequest = null
            }
            val bindFeatures = Bind2.features(inline)
            quickStartAvailable =
                sm && bindFeatures != null && bindFeatures.containsAll(Bind2.QUICKSTART_FEATURES)
            if (bindFeatures != null) {
                try {
                    mXmppConnectionService.restoredFromDatabaseLatch.await()
                } catch (e: InterruptedException) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: interrupted while waiting for DB " +
                            "restore during SASL2 bind",
                    )
                    return
                }
            }
            loginInfo = LoginInfo(mechanism, version, bindFeatures)
            this.hashTokenRequest = hashTokenRequest
            authenticate =
                generateAuthenticationRequest(
                    firstMessage,
                    usingFast,
                    hashTokenRequest,
                    bindFeatures,
                    sm,
                )
        } else {
            throw AssertionError("Missing implementation for $version")
        }
        this.loginInfo = loginInfo
        if (account.setOption(AccountRef.OPTION_QUICKSTART_AVAILABLE, quickStartAvailable)) {
            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
        }

        Log.d(
            Config.LOGTAG,
            "${account.getJid()}: Authenticating with $version/" +
                loginInfo.saslMechanism.getMechanism(),
        )
        authenticate.setMechanism(loginInfo.saslMechanism)
        synchronized(this.mStanzaQueue) {
            this.stanzasSentBeforeAuthentication = this.stanzasSent
            tagWriter.writeElement(authenticate)
        }
    }

    private fun validate(saslMechanism: SaslMechanism?, mechanisms: Collection<String>) {
        if (saslMechanism == null) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: unable to find supported SASL mechanism in " +
                    mechanisms,
            )
            throw StateChangingException(AccountRef.StateRef.INCOMPATIBLE_SERVER)
        }
        checkRequireChannelBinding(saslMechanism)
        if (SaslMechanism.hashedToken(saslMechanism)) {
            return
        }
        val pinnedMechanism = account.getPinnedMechanismPriority()
        if (pinnedMechanism > saslMechanism.getPriority()) {
            Log.e(
                Config.LOGTAG,
                "Auth failed. Authentication mechanism ${saslMechanism.getMechanism()} has lower " +
                    "priority (${saslMechanism.getPriority()}) than pinned priority " +
                    "($pinnedMechanism). Possible downgrade attack?",
            )
            throw StateChangingException(AccountRef.StateRef.DOWNGRADE_ATTACK)
        }
    }

    private fun checkRequireChannelBinding(mechanism: SaslMechanism) {
        if (isRequireChannelBinding()) {
            if (mechanism is ChannelBindingMechanism) {
                return
            }
            Log.d(Config.LOGTAG, "${account.getJid()}: server did not offer channel binding")
            throw StateChangingException(AccountRef.StateRef.CHANNEL_BINDING)
        }
    }

    private fun checkAssignedDomainOrThrow(jid: Jid?) {
        if (jid == null) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: bind response is missing jid")
            throw StateChangingException(AccountRef.StateRef.BIND_FAILURE)
        }
        val current = this.account.getJid().getDomain()
        if (jid.getDomain() == current) {
            return
        }
        Log.d(
            Config.LOGTAG,
            "${account.getJid().asBareJid()}: server tried to re-assign domain to " + jid.getDomain(),
        )
        throw StateChangingException(AccountRef.StateRef.BIND_FAILURE)
    }

    private fun checkAssignedDomain(jid: Jid?) {
        try {
            checkAssignedDomainOrThrow(jid)
        } catch (e: StateChangingException) {
            throw StateChangingError(e.state)
        }
    }

    private fun generateAuthenticationRequest(
        firstMessage: String,
        usingFast: Boolean,
    ): AuthenticationRequest =
        generateAuthenticationRequest(firstMessage, usingFast, null, Bind2.QUICKSTART_FEATURES, true)

    private fun generateAuthenticationRequest(
        firstMessage: String?,
        usingFast: Boolean,
        hashedTokenRequest: HashedToken.Mechanism?,
        bind: Collection<String>?,
        inlineStreamManagement: Boolean,
    ): AuthenticationRequest {
        val authenticate = Authenticate()
        if (!firstMessage.isNullOrEmpty()) {
            authenticate.addChild("initial-response").setContent(firstMessage)
        }
        val userAgent =
            authenticate.addExtension(
                UserAgent(
                    AccountUtils.publicDeviceId(account, appSettings.getInstallationId()),
                ),
            )
        userAgent.setSoftware("${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME}")
        if (!mXmppConnectionService.phoneHelper().isEmulator()) {
            userAgent.setDevice("${Build.MANUFACTURER} ${Build.MODEL}")
        }
        // do not include bind if 'inlineStreamManagement' is missing and we have a streamId
        // (because we would rather just do a normal SM/resume)
        val mayAttemptBind = streamId == null || inlineStreamManagement
        if (bind != null && mayAttemptBind) {
            authenticate.addChild(generateBindRequest(bind))
        }
        if (inlineStreamManagement && streamId != null) {
            val streamId = (this.streamId ?: throw NullPointerException("streamId")).id
            val resume = Resume(streamId, stanzasReceived)
            prepareForResume(streamId)
            authenticate.addExtension(resume)
        }
        if (hashedTokenRequest != null) {
            authenticate.addExtension(RequestToken(hashedTokenRequest))
        }
        if (usingFast) {
            authenticate.addExtension(Fast())
        }
        return authenticate
    }

    private fun prepareForResume(streamId: String) {
        this.mSmCatchupMessageCounter.set(0)
        this.mWaitingForSmCatchup.set(true)
        this.pendingResumeId.set(streamId)
    }

    private fun generateBindRequest(bindFeatures: Collection<String>): Bind {
        Log.d(Config.LOGTAG, "inline bind features: $bindFeatures")
        val bind = Bind()
        bind.setTag(getEffectiveClientName(appSettings))
        if (bindFeatures.contains(Namespace.CARBONS)) {
            bind.addExtension(uk.xa0.tulkki.xmpp.models.carbons.Enable())
        }
        if (bindFeatures.contains(Namespace.STREAM_MANAGEMENT)) {
            bind.addExtension(Enable())
        }
        return bind
    }

    private fun register() {
        val preAuth = account.getPreAuthRegistrationToken()
        if (preAuth != null && features.invite()) {
            val preAuthRequest = Iq(Iq.Type.SET)
            preAuthRequest.addChild("preauth", Namespace.PARS).setAttribute("token", preAuth)
            sendUnmodifiedIqPacket(
                preAuthRequest,
                Consumer { response ->
                    if (response.getType() == Iq.Type.RESULT) {
                        sendRegistryRequest()
                    } else {
                        val error = response.getErrorCondition()
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: failed to pre auth. $error",
                        )
                        throw StateChangingError(AccountRef.StateRef.REGISTRATION_INVALID_TOKEN)
                    }
                },
                true,
            )
        } else {
            sendRegistryRequest()
        }
    }

    private fun sendRegistryRequest() {
        val register = Iq(Iq.Type.GET)
        register.query(Namespace.REGISTER)
        register.setTo(account.getDomain())
        sendUnmodifiedIqPacket(
            register,
            Consumer { packet ->
                if (packet.getType() == Iq.Type.TIMEOUT) {
                    return@Consumer
                }
                if (packet.getType() == Iq.Type.ERROR) {
                    throw StateChangingError(AccountRef.StateRef.REGISTRATION_FAILED)
                }
                val query = packet.query(Namespace.REGISTER)
                if (query.hasChild("username") && (query.hasChild("password"))) {
                    val register1 = Iq(Iq.Type.SET)
                    val username = Element("username").setContent(account.getUsername())
                    val password = Element("password").setContent(account.getPassword())
                    register1.query(Namespace.REGISTER).addChild(username)
                    register1.query().addChild(password)
                    register1.setFrom(account.getJid().asBareJid())
                    sendUnmodifiedIqPacket(
                        register1,
                        Consumer { this.processRegistrationResponse(it) },
                        true,
                    )
                } else if (query.hasChild("x", Namespace.DATA)) {
                    val data = Data.parse(query.findChild("x", Namespace.DATA))
                    val blob = query.findChild("data", "urn:xmpp:bob")
                    val id = packet.getId()
                    var inputStream: InputStream?
                    if (blob != null) {
                        try {
                            val base64Blob = blob.getContent()
                            val strBlob = Base64.decode(base64Blob, Base64.DEFAULT)
                            inputStream = ByteArrayInputStream(strBlob)
                        } catch (e: Exception) {
                            inputStream = null
                        }
                    } else {
                        val useTor = this.appSettings.isUseTor() || account.isOnion()
                        val useI2P =
                            mXmppConnectionService.useI2PToConnect() || account.isI2P()

                        try {
                            val url = data?.getValue("url")
                            val fallbackUrl = data?.getValue("captcha-fallback-url")
                            if (url != null) {
                                inputStream = HttpConnectionManager.open(url, useTor, useI2P)
                            } else if (fallbackUrl != null) {
                                inputStream = HttpConnectionManager.open(fallbackUrl, useTor, useI2P)
                            } else {
                                inputStream = null
                            }
                        } catch (e: IOException) {
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: unable to fetch captcha",
                                e,
                            )
                            inputStream = null
                        }
                    }

                    if (inputStream != null) {
                        val captcha = BitmapFactory.decodeStream(inputStream)
                        try {
                            if (mXmppConnectionService.displayCaptchaRequest(
                                    account,
                                    id,
                                    data,
                                    captcha,
                                )
                            ) {
                                return@Consumer
                            }
                        } catch (e: Exception) {
                            throw StateChangingError(AccountRef.StateRef.REGISTRATION_FAILED)
                        }
                    }
                    throw StateChangingError(AccountRef.StateRef.REGISTRATION_FAILED)
                } else if (query.hasChild("instructions") ||
                    query.hasChild("x", Namespace.OOB)
                ) {
                    val instructions = query.findChildContent("instructions")
                    val oob = query.findChild("x", Namespace.OOB)
                    val url = oob?.findChildContent("url")
                    if (url != null) {
                        setAccountCreationFailed(url)
                    } else if (instructions != null) {
                        val matcher =
                            XmppConnectionService.dataStatics()
                                .autolinkWebUrl()
                                .matcher(instructions)
                        if (matcher.find()) {
                            setAccountCreationFailed(
                                instructions.substring(matcher.start(), matcher.end()),
                            )
                        }
                    }
                    throw StateChangingError(AccountRef.StateRef.REGISTRATION_FAILED)
                }
            },
            true,
        )
    }

    fun sendCreateAccountWithCaptchaPacket(id: String?, data: Data?) {
        val request = IqGenerator.generateCreateAccountWithCaptcha(account, id, data)
        this.sendUnmodifiedIqPacket(
            request,
            Consumer { this.processRegistrationResponse(it) },
            true,
        )
    }

    private fun processRegistrationResponse(response: Iq) {
        if (response.getType() == Iq.Type.RESULT) {
            account.setOption(AccountRef.OPTION_REGISTER, false)
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: successfully registered new account on server",
            )
            throw StateChangingError(AccountRef.StateRef.REGISTRATION_SUCCESSFUL)
        } else {
            val state = getRegistrationFailedState(response)
            throw StateChangingError(state)
        }
    }

    private fun setAccountCreationFailed(url: String?) {
        val httpUrl = url?.toHttpUrlOrNull()
        if (httpUrl != null && httpUrl.isHttps) {
            this.redirectionUrl = httpUrl
            throw StateChangingError(AccountRef.StateRef.REGISTRATION_WEB)
        }
        throw StateChangingError(AccountRef.StateRef.REGISTRATION_FAILED)
    }

    fun getRedirectionUrl(): HttpUrl? = this.redirectionUrl

    fun resetEverything() {
        resetAttemptCount(true)
        resetStreamId()
        clearIqCallbacks()
        synchronized(this.mStanzaQueue) {
            this.stanzasSent = 0
            this.mStanzaQueue.clear()
        }
        this.redirectionUrl = null
        synchronized(this.disco) {
            disco.clear()
        }
        synchronized(this.commands) {
            this.commands.clear()
        }
        this.loginInfo = null
    }

    private fun sendBindRequest() {
        try {
            mXmppConnectionService.restoredFromDatabaseLatch.await()
        } catch (e: InterruptedException) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: interrupted while waiting for DB restore " +
                    "during bind",
            )
            return
        }
        clearIqCallbacks()
        if (!account.getJid().isBareJid()) {
            fixResource(mXmppConnectionService, account)
        }
        if (account.getJid().isBareJid()) {
            account.setResource(createNewResource())
        }
        val iq = Iq(Iq.Type.SET)
        val resource =
            if (Config.USE_RANDOM_RESOURCE_ON_EVERY_BIND) {
                CryptoHelper.random(9)
            } else {
                // Tulkki: `AccountRef.getResource()` is nullable in the contract while the bind
                // below needs the resource the account was just given; the Java dereferenced it
                // bare, so a missing one is still the Java's NPE.
                account.getResource() ?: throw NullPointerException("account has no resource")
            }
        iq.addExtension(uk.xa0.tulkki.xmpp.models.bind.Bind()).setResource(resource)
        this.sendUnmodifiedIqPacket(
            iq,
            Consumer { packet ->
                if (packet.getType() == Iq.Type.TIMEOUT) {
                    return@Consumer
                }
                val bind = packet.getExtension(uk.xa0.tulkki.xmpp.models.bind.Bind::class.java)
                if (bind != null && packet.getType() == Iq.Type.RESULT) {
                    isBound = true
                    val assignedJid = bind.getJid()
                    checkAssignedDomain(assignedJid)
                    if (account.setJid(assignedJid)) {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: jid changed during bind. updating " +
                                "database",
                        )
                        (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).updateAccount(account)
                    }
                    val features = streamFeatures ?: throw NullPointerException("streamFeatures")
                    val session = features.findChild("session")
                    if (session != null && !session.hasChild("optional")) {
                        sendStartSession()
                    } else {
                        val waitForDisco = enableStreamManagement()
                        sendPostBindInitialization(waitForDisco, false)
                    }
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid()}: disconnecting because of bind failure ($packet)",
                    )
                    val error = packet.getError()
                    // TODO error.is(Condition)
                    if (packet.getType() == Iq.Type.ERROR &&
                        error != null &&
                        error.hasChild("conflict")
                    ) {
                        account.setResource(createNewResource())
                    }
                    throw StateChangingError(AccountRef.StateRef.BIND_FAILURE)
                }
            },
            true,
        )
    }

    private fun clearIqCallbacks() {
        val failurePacket = Iq(Iq.Type.TIMEOUT)
        val callbacks = ArrayList<Consumer<Iq>>()
        synchronized(this.packetCallbacks) {
            if (this.packetCallbacks.isEmpty()) {
                return
            }
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: clearing ${this.packetCallbacks.size} iq " +
                    "callbacks",
            )
            val iterator = this.packetCallbacks.values.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val timeoutFuture = entry.second.second
                if (timeoutFuture == null || timeoutFuture.cancel(false)) {
                    callbacks.add(entry.second.first)
                }
                iterator.remove()
            }
        }
        for (callback in callbacks) {
            try {
                callback.accept(failurePacket)
            } catch (error: StateChangingError) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: caught StateChangingError(" +
                        "${error.state}) while clearing callbacks",
                )
                // ignore
            }
        }
        Log.d(
            Config.LOGTAG,
            "${account.getJid().asBareJid()}: done clearing iq callbacks. " +
                "${this.packetCallbacks.size} left",
        )
    }

    fun sendDiscoTimeout() {
        if (mWaitForDisco.compareAndSet(true, false)) {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: finalizing bind after disco timeout",
            )
            finalizeBind()
        }
    }

    private fun sendStartSession() {
        Log.d(
            Config.LOGTAG,
            "${account.getJid().asBareJid()}: sending legacy session to outdated server",
        )
        val startSession = Iq(Iq.Type.SET)
        startSession.addChild("session", "urn:ietf:params:xml:ns:xmpp-session")
        this.sendUnmodifiedIqPacket(
            startSession,
            Consumer { packet ->
                if (packet.getType() == Iq.Type.RESULT) {
                    val waitForDisco = enableStreamManagement()
                    sendPostBindInitialization(waitForDisco, false)
                } else if (packet.getType() != Iq.Type.TIMEOUT) {
                    throw StateChangingError(AccountRef.StateRef.SESSION_FAILURE)
                }
            },
            true,
        )
    }

    private fun enableStreamManagement(): Boolean {
        val streamManagement = (streamFeatures ?: throw NullPointerException("streamFeatures"))
            .streamManagement()
        if (streamManagement) {
            synchronized(this.mStanzaQueue) {
                val enable = Enable()
                tagWriter.writeStanzaAsync(enable)
                stanzasSent = 0
                mStanzaQueue.clear()
            }
            return true
        } else {
            return false
        }
    }

    private fun sendPostBindInitialization(waitForDisco: Boolean, carbonsEnabled: Boolean) {
        features.carbonsEnabled = carbonsEnabled
        features.blockListRequested = false
        synchronized(this.disco) {
            this.disco.clear()
        }
        Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: starting service discovery")
        mPendingServiceDiscoveries.set(0)
        mWaitForDisco.set(waitForDisco)
        this.lastDiscoStarted = SystemClock.elapsedRealtime()
        mXmppConnectionService.scheduleWakeUpCall(
            Config.CONNECT_DISCO_TIMEOUT * 1000L,
            account.getUuid().hashCode(),
        )
        val caps = (streamFeatures ?: throw NullPointerException("streamFeatures")).findChild("c")
        val hash = caps?.getAttribute("hash")
        val ver = caps?.getAttribute("ver")
        var discoveryResult: ServiceDiscoveryResultRef? = null
        if (hash != null && ver != null) {
            // Tulkki: 3.7 C5-B - the cast part 14 left here is gone with the map's model type: the
            // accessor, the map and this local are all `ServiceDiscoveryResultRef` now, and every
            // value the accessor hands back really is a `ServiceDiscoveryResult` (C5-A's invariant,
            // see the construction below).
            discoveryResult =
                mXmppConnectionService.getCachedServiceDiscoveryResult(Pair(hash, ver))
        }
        val requestDiscoItemsFirst =
            !account.isOptionSet(AccountRef.OPTION_LOGGED_IN_SUCCESSFULLY)
        if (requestDiscoItemsFirst) {
            sendServiceDiscoveryItems(account.getDomain())
        }
        if (discoveryResult == null) {
            sendServiceDiscoveryInfo(account.getDomain())
        } else {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: server caps came from cache")
            disco[account.getDomain()] = discoveryResult
        }
        val features = getFeatures()
        if (!features.bind2()) {
            discoverMamPreferences()
        }
        sendServiceDiscoveryInfo(account.getJid().asBareJid())
        if (!requestDiscoItemsFirst) {
            sendServiceDiscoveryItems(account.getDomain())
        }

        if (!mWaitForDisco.get()) {
            finalizeBind()
        }
        this.lastSessionStarted = SystemClock.elapsedRealtime()
    }

    private fun sendServiceDiscoveryInfo(jid: Jid) {
        mPendingServiceDiscoveries.incrementAndGet()
        val iq = Iq(Iq.Type.GET)
        iq.setTo(jid)
        iq.query("http://jabber.org/protocol/disco#info")
        this.sendIqPacket(
            iq,
            Consumer { packet ->
                if (packet.getType() == Iq.Type.RESULT) {
                    val advancedStreamFeaturesLoaded: Boolean
                    synchronized(this@XmppConnection.disco) {
                        // Tulkki: 3.7 C5-B - `new ServiceDiscoveryResult(Iq)` through the port
                        // that already existed (`DataStatics.newServiceDiscoveryResult`); the
                        // object really is a `ServiceDiscoveryResult`, so the ref-typed
                        // `DatabaseBackend.insertDiscoveryResult(ServiceDiscoveryResultRef)`
                        // below is the same call it always was.
                        val result =
                            XmppConnectionService.dataStatics()
                                .newServiceDiscoveryResult(packet)
                        if (jid == account.getDomain()) {
                            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).insertDiscoveryResult(result)
                        }
                        disco[jid] = result
                        advancedStreamFeaturesLoaded =
                            disco.containsKey(account.getDomain()) &&
                                disco.containsKey(account.getJid().asBareJid())
                    }
                    if (advancedStreamFeaturesLoaded &&
                        (jid == account.getDomain() ||
                            jid == account.getJid().asBareJid())
                    ) {
                        enableAdvancedStreamFeatures()
                    }
                } else if (packet.getType() == Iq.Type.ERROR) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: could not query disco info for " +
                            jid.toString(),
                    )
                    val serverOrAccount =
                        jid == account.getDomain() || jid == account.getJid().asBareJid()
                    val advancedStreamFeaturesLoaded: Boolean
                    if (serverOrAccount) {
                        synchronized(this@XmppConnection.disco) {
                            // Tulkki: 3.7 C5-B - the placeholder for "disco was attempted and
                            // failed". `ServiceDiscoveryResult.empty()` is a `:data` *static*,
                            // and the brief's device for a static is a `DataStatics` member -
                            // but `DataStatics` is declared inside `XmppConnectionService.java`,
                            // which this task may not edit, so the existing factory is used on an
                            // empty `Iq` instead. THE EQUIVALENCE, since no compiler checks it:
                            // `Iq.query()` creates the `query` child when it is absent, so this
                            // result holds no identities, no features and no forms - exactly what
                            // `empty()` holds. The only field that differs is `ver`, and nothing
                            // reads `getVer()` on a `disco` value (the only readers are
                            // `getFeatures`, `hasIdentity` and `getExtendedDiscoInformation`, and
                            // the map is private, never cast back to the model and never inserted
                            // into the database - the insert is on the success path above). The
                            // C5-A invariant is therefore intact: every value this map holds is a
                            // real `ServiceDiscoveryResult`. An island-side implementation of the
                            // ref was rejected for breaking exactly that.
                            disco[jid] =
                                XmppConnectionService.dataStatics()
                                    .newServiceDiscoveryResult(Iq(Iq.Type.RESULT))
                            advancedStreamFeaturesLoaded =
                                disco.containsKey(account.getDomain()) &&
                                    disco.containsKey(account.getJid().asBareJid())
                        }
                    } else {
                        advancedStreamFeaturesLoaded = false
                    }
                    if (advancedStreamFeaturesLoaded) {
                        enableAdvancedStreamFeatures()
                    }
                }
                if (packet.getType() != Iq.Type.TIMEOUT) {
                    if (mPendingServiceDiscoveries.decrementAndGet() == 0 &&
                        mWaitForDisco.compareAndSet(true, false)
                    ) {
                        finalizeBind()
                    }
                }
            },
        )
    }

    private fun discoverMamPreferences() {
        val request = Iq(Iq.Type.GET)
        request.addChild("prefs", MessageArchiveService.Version.MAM_2.namespace)
        sendIqPacket(
            request,
            Consumer { response ->
                if (response.getType() == Iq.Type.RESULT) {
                    val prefs =
                        response.findChild(
                            "prefs",
                            MessageArchiveService.Version.MAM_2.namespace,
                        )
                    isMamPreferenceAlways =
                        "always" == (prefs?.getAttribute("default"))
                }
            },
        )
    }

    private fun discoverCommands() {
        val request = Iq(Iq.Type.GET)
        request.setTo(account.getDomain())
        request.addChild("query", Namespace.DISCO_ITEMS).setAttribute("node", Namespace.COMMANDS)
        sendIqPacket(
            request,
            Consumer { response ->
                if (response.getType() == Iq.Type.RESULT) {
                    val query = response.findChild("query", Namespace.DISCO_ITEMS)
                    if (query == null) {
                        return@Consumer
                    }
                    val commands = HashMap<String, Jid>()
                    for (child in query.getChildren()) {
                        if ("item" == child.getName()) {
                            val node = child.getAttribute("node")
                            val jid = child.getAttributeAsJid("jid")
                            if (node != null && jid != null) {
                                commands[node] = jid
                            }
                        }
                    }
                    synchronized(this.commands) {
                        this.commands.clear()
                        this.commands.putAll(commands)
                    }
                }
            },
        )
    }

    fun isMamPreferenceAlways(): Boolean = isMamPreferenceAlways

    private fun finalizeBind() {
        this.offlineMessagesRetrieved = false
        this.bindListener.run()
        this.changeStatusToOnline()
    }

    private fun enableAdvancedStreamFeatures() {
        if (getFeatures().blocking() && !features.blockListRequested) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: Requesting block list")
            this.sendIqPacket(getIqGenerator().generateGetBlockList(), unregisteredIqListener)
        }
        for (listener in advancedStreamFeaturesLoadedListeners) {
            listener.onAdvancedStreamFeaturesAvailable(account)
        }
        if (getFeatures().carbons() && !features.carbonsEnabled) {
            sendEnableCarbons()
        }
        if (getFeatures().commands()) {
            discoverCommands()
        }
    }

    private fun sendServiceDiscoveryItems(server: Jid) {
        mPendingServiceDiscoveries.incrementAndGet()
        val iq = Iq(Iq.Type.GET)
        iq.setTo(server.getDomain())
        iq.query("http://jabber.org/protocol/disco#items")
        this.sendIqPacket(
            iq,
            Consumer { packet ->
                if (packet.getType() == Iq.Type.RESULT) {
                    val items = HashSet<Jid>()
                    val elements = packet.query().getChildren()
                    for (element in elements) {
                        if (element.getName() == "item") {
                            val jid =
                                Jid.Invalid.getNullForInvalid(element.getAttributeAsJid("jid"))
                            if (jid != null && jid != account.getDomain()) {
                                items.add(jid)
                            }
                        }
                    }
                    for (jid in items) {
                        sendServiceDiscoveryInfo(jid)
                    }
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: could not query disco items of $server",
                    )
                }
                if (packet.getType() != Iq.Type.TIMEOUT) {
                    if (mPendingServiceDiscoveries.decrementAndGet() == 0 &&
                        mWaitForDisco.compareAndSet(true, false)
                    ) {
                        finalizeBind()
                    }
                }
            },
        )
    }

    private fun sendEnableCarbons() {
        val iq = Iq(Iq.Type.SET)
        iq.addChild("enable", Namespace.CARBONS)
        this.sendIqPacket(
            iq,
            Consumer { packet ->
                if (packet.getType() == Iq.Type.RESULT) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: successfully enabled carbons",
                    )
                    features.carbonsEnabled = true
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: could not enable carbons $packet",
                    )
                }
            },
        )
    }

    private fun processStreamError(streamError: StreamError) {
        val loginInfo = this.loginInfo
        val isSecureLoggedIn = isSecure() && LoginInfo.isSuccess(loginInfo)
        if (isSecureLoggedIn && streamError.hasChild("conflict")) {
            if (loginInfo != null && loginInfo.saslVersion == SaslMechanism.Version.SASL_2) {
                this.appSettings.resetInstallationId()
            }
            account.setResource(createNewResource())
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: switching resource due to conflict " +
                    "(${account.getResource()})",
            )
            throw IOException("Closed stream due to resource conflict")
        } else if (streamError.hasChild("host-unknown")) {
            throw StateChangingException(AccountRef.StateRef.HOST_UNKNOWN)
        } else if (streamError.hasChild("policy-violation")) {
            this.lastConnectionStarted = SystemClock.elapsedRealtime()
            val text = streamError.findChildContent("text")
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: policy violation. $text")
            if (isSecureLoggedIn) {
                failPendingMessages(text)
            }
            throw StateChangingException(AccountRef.StateRef.POLICY_VIOLATION)
        } else if (streamError.hasChild("see-other-host")) {
            val seeOtherHost = streamError.findChildContent("see-other-host")
            val currentResolverResult = this.currentResolverResult
            if (seeOtherHost.isNullOrEmpty() || currentResolverResult == null) {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()}: stream error $streamError",
                )
                throw StateChangingException(AccountRef.StateRef.STREAM_ERROR)
            }
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: see other host: $seeOtherHost " +
                    currentResolverResult,
            )
            val seeOtherResult = currentResolverResult.seeOtherHost(seeOtherHost)
            if (seeOtherResult != null) {
                this.seeOtherHostResolverResult = seeOtherResult
                throw StateChangingException(AccountRef.StateRef.SEE_OTHER_HOST)
            } else {
                throw StateChangingException(AccountRef.StateRef.STREAM_ERROR)
            }
        } else {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: stream error $streamError")
            throw StateChangingException(AccountRef.StateRef.STREAM_ERROR)
        }
    }

    private fun failPendingMessages(error: String?) {
        synchronized(this.mStanzaQueue) {
            for (i in 0 until mStanzaQueue.size()) {
                val stanza = mStanzaQueue.valueAt(i)
                if (stanza is Message) {
                    val id = stanza.getId()
                    val to = stanza.getTo() ?: throw NullPointerException()
                    mXmppConnectionService.markMessage(
                        account,
                        to.asBareJid(),
                        id,
                        MessageRef.STATUS_SEND_FAILED,
                        error,
                    )
                }
            }
        }
    }

    private fun establishStream(sslVersion: SSLSockets.Version): Boolean {
        val secureConnection = sslVersion != SSLSockets.Version.NONE
        val quickStartMechanism: SaslMechanism?
        if (secureConnection) {
            quickStartMechanism =
                SaslMechanism.ensureAvailable(
                    account.getQuickStartMechanism(),
                    sslVersion,
                    isRequireChannelBinding(),
                )
        } else {
            quickStartMechanism = null
        }
        if (secureConnection &&
            Config.QUICKSTART_ENABLED &&
            quickStartMechanism != null &&
            account.isOptionSet(AccountRef.OPTION_QUICKSTART_AVAILABLE)
        ) {
            mXmppConnectionService.restoredFromDatabaseLatch.await()
            this.loginInfo =
                LoginInfo(
                    quickStartMechanism,
                    SaslMechanism.Version.SASL_2,
                    Bind2.QUICKSTART_FEATURES,
                )
            val usingFast = quickStartMechanism is HashedToken
            val authenticate =
                generateAuthenticationRequest(
                    quickStartMechanism.getClientFirstMessage(sslSocketOrNull(this.socket)),
                    usingFast,
                )
            authenticate.setMechanism(quickStartMechanism)
            sendStartStream(true, false)
            synchronized(this.mStanzaQueue) {
                this.stanzasSentBeforeAuthentication = this.stanzasSent
                tagWriter.writeElement(authenticate)
            }
            Log.d(
                Config.LOGTAG,
                "${account.getJid()}: quick start with ${quickStartMechanism.getMechanism()}",
            )
            return true
        } else {
            sendStartStream(secureConnection, true)
            return false
        }
    }

    private fun isRequireChannelBinding(): Boolean =
        XmppConnectionService.dataStatics().secureDomains().contains(account.getDomain()) ||
            appSettings.isRequireChannelBinding()

    private fun isRequireTlsV13(): Boolean =
        XmppConnectionService.dataStatics().secureDomains().contains(account.getDomain()) ||
            appSettings.isRequireTlsV13()

    private fun isDANEnforced(): Boolean {
        // Honour the user preference only
        return appSettings.isDANEnforced()
    }

    private fun sendStartStream(from: Boolean, flush: Boolean) {
        val stream = Tag.start("stream:stream")
        stream.setAttribute("to", account.getServer())
        if (from) {
            stream.setAttribute("from", account.getJid().asBareJid().toString())
        }
        stream.setAttribute("version", "1.0")
        stream.setAttribute("xml:lang", LocalizedContent.STREAM_LANGUAGE)
        stream.setAttribute("xmlns", Namespace.JABBER_CLIENT)
        stream.setAttribute("xmlns:stream", Namespace.STREAMS)
        tagWriter.writeTag(stream, flush)
    }

    private fun createNewResource(): String =
        getEffectiveClientName(appSettings) + "." + CryptoHelper.random(3)

    fun sendIqPacket(packet: Iq, callback: Consumer<Iq>?): String? =
        sendIqPacket(packet, callback, null)

    fun sendIqPacket(packet: Iq, callback: Consumer<Iq>?, timeout: Long?): String? {
        packet.setFrom(account.getJid())
        return this.sendUnmodifiedIqPacket(packet, callback, false, timeout)
    }

    fun sendUnmodifiedIqPacket(
        packet: Iq,
        callback: Consumer<Iq>?,
        force: Boolean,
    ): String? = sendUnmodifiedIqPacket(packet, callback, force, null)

    @Synchronized
    fun sendUnmodifiedIqPacket(
        packet: Iq,
        callback: Consumer<Iq>?,
        force: Boolean,
        timeout: Long?,
    ): String? {
        // TODO if callback != null verify that type is get or set
        if (packet.getId() == null) {
            packet.setId(CryptoHelper.random(9))
        }
        if (callback != null) {
            synchronized(this.packetCallbacks) {
                var timeoutFuture: ScheduledFuture<*>? = null
                if (timeout != null) {
                    timeoutFuture =
                        SCHEDULER.schedule(
                            Runnable {
                                synchronized(this.packetCallbacks) {
                                    val failurePacket = Iq(Iq.Type.TIMEOUT)
                                    val removedCallback =
                                        packetCallbacks.remove(packet.getId())
                                    if (removedCallback != null) {
                                        removedCallback.second.first.accept(failurePacket)
                                    }
                                }
                            },
                            timeout,
                            TimeUnit.SECONDS,
                        )
                }
                packetCallbacks[packet.getId()] =
                    Pair(packet, Pair(callback, timeoutFuture))
            }
        }
        this.sendPacket(packet, force)
        return packet.getId()
    }

    fun sendMessagePacket(packet: Message) {
        this.sendPacket(packet)
    }

    fun sendPresencePacket(packet: Presence) {
        this.sendPacket(packet)
    }

    @Synchronized
    private fun sendPacket(packet: StreamElement) {
        sendPacket(packet, false)
    }

    @Synchronized
    private fun sendPacket(packet: StreamElement, force: Boolean) {
        if (stanzasSent == Int.MAX_VALUE) {
            resetStreamId()
            disconnect(true)
            return
        }
        synchronized(this.mStanzaQueue) {
            if (force || isBound) {
                tagWriter.writeStanzaAsync(packet)
            } else {
                Log.d(
                    Config.LOGTAG,
                    "${account.getJid().asBareJid()} do not write stanza to unbound stream " +
                        packet.toString(),
                )
            }
            if (packet is Stanza) {
                if (this.mStanzaQueue.size() != 0) {
                    val currentHighestKey = this.mStanzaQueue.keyAt(this.mStanzaQueue.size() - 1)
                    if (currentHighestKey != stanzasSent) {
                        throw AssertionError("Stanza count messed up")
                    }
                }

                ++stanzasSent
                if (Config.EXTENDED_SM_LOGGING) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: counting outbound " +
                            "${packet.name} as #$stanzasSent",
                    )
                }
                this.mStanzaQueue.append(stanzasSent, packet)
                if (packet is Message && packet.getId() != null && inSmacksSession) {
                    if (Config.EXTENDED_SM_LOGGING) {
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: requesting ack for message " +
                                "stanza #$stanzasSent",
                        )
                    }
                    tagWriter.writeStanzaAsync(Request())
                }
            }
        }
    }

    fun sendPing() {
        if (!r()) {
            val iq = Iq(Iq.Type.GET)
            iq.setFrom(account.getJid())
            iq.addChild("ping", Namespace.PING)
            this.sendIqPacket(iq, null)
        }
        this.lastPingSent = SystemClock.elapsedRealtime()
    }

    fun setOnJinglePacketReceivedListener(listener: OnJinglePacketReceived?) {
        this.jingleListener = listener
    }

    fun setOnStatusChangedListener(listener: OnStatusChanged?) {
        this.statusListener = listener
    }

    fun setOnMessageAcknowledgeListener(listener: OnMessageAcknowledged?) {
        this.acknowledgedListener = listener
    }

    fun addOnAdvancedStreamFeaturesAvailableListener(listener: OnAdvancedStreamFeaturesLoaded) {
        this.advancedStreamFeaturesLoadedListeners.add(listener)
    }

    private fun forceCloseSocket() {
        XmppConnectionService.dataStatics().close(this.socket)
        XmppConnectionService.dataStatics().close(this.tagReader)
    }

    fun interrupt() {
        mThread?.interrupt()
    }

    fun disconnect(force: Boolean) {
        interrupt()
        Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: disconnecting force=$force")
        if (force) {
            forceCloseSocket()
        } else {
            val currentTagWriter = this.tagWriter
            if (currentTagWriter.isActive()) {
                currentTagWriter.finish()
                val currentSocket = this.socket
                val streamCountDownLatch = this.mStreamCountDownLatch
                try {
                    currentTagWriter.await(1L, TimeUnit.SECONDS)
                    Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: closing stream")
                    currentTagWriter.writeTag(Tag.end("stream:stream"))
                    if (streamCountDownLatch != null) {
                        if (streamCountDownLatch.await(1L, TimeUnit.SECONDS)) {
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: remote ended stream",
                            )
                        } else {
                            Log.d(
                                Config.LOGTAG,
                                "${account.getJid().asBareJid()}: remote has not closed socket. " +
                                    "force closing",
                            )
                        }
                    }
                } catch (e: InterruptedException) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: interrupted while gracefully closing " +
                            "stream",
                    )
                } catch (e: IOException) {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: io exception during disconnect " +
                            "(${e.message})",
                    )
                } finally {
                    XmppConnectionService.dataStatics().close(currentSocket)
                }
            } else {
                forceCloseSocket()
            }
        }
    }

    private fun resetStreamId() {
        this.pendingResumeId.set(null)
        this.streamId = null
        this.boundStreamFeatures = null
    }

    private fun findDiscoItemsByFeature(
        feature: String,
    ): MutableList<MutableMap.MutableEntry<Jid, ServiceDiscoveryResultRef>> {
        synchronized(this.disco) {
            val items = ArrayList<MutableMap.MutableEntry<Jid, ServiceDiscoveryResultRef>>()
            for (cursor in this.disco.entries) {
                if (cursor.value.getFeatures().contains(feature)) {
                    items.add(cursor)
                }
            }
            return items
        }
    }

    fun findDiscoItemByFeature(feature: String): Jid? {
        val items = findDiscoItemsByFeature(feature)
        if (items.isEmpty()) {
            return null
        }
        return items[0].key
    }

    fun r(): Boolean {
        if (getFeatures().sm()) {
            this.tagWriter.writeStanzaAsync(Request())
            return true
        } else {
            return false
        }
    }

    fun getMucServersWithholdAccount(): MutableList<String> {
        val servers = getMucServers()
        servers.remove(account.getDomain().toString())
        return servers
    }

    fun getMucServers(): MutableList<String> {
        val servers = ArrayList<String>()
        synchronized(this.disco) {
            for (cursor in disco.entries) {
                val value = cursor.value
                if (value.getFeatures().contains("http://jabber.org/protocol/muc") &&
                    value.hasIdentity("conference", "text") &&
                    !value.getFeatures().contains("jabber:iq:gateway") &&
                    !value.hasIdentity("conference", "irc")
                ) {
                    servers.add(cursor.key.toString())
                }
            }
        }
        return servers
    }

    fun getMucServer(): String? = Iterables.getFirst(getMucServers(), null)

    fun getTimeToNextAttempt(aggressive: Boolean): Int {
        val interval: Int
        if (aggressive) {
            interval = Math.min((3 * Math.pow(1.3, attempt.toDouble())).toInt(), 60)
        } else {
            val additionalTime =
                if (account.getLastErrorStatusRef() == AccountRef.StateRef.POLICY_VIOLATION) 3
                else 0
            interval =
                Math.min((25 * Math.pow(1.3, (additionalTime + attempt).toDouble())).toInt(), 300)
        }
        val connectionDuration = Ints.saturatedCast(getConnectionDuration() / 1000)
        return interval - connectionDuration
    }

    fun getAttempt(): Int = this.attempt

    fun getFeatures(): Features = this.features

    fun getLastSessionEstablished(): Long {
        val diff = SystemClock.elapsedRealtime() - this.lastSessionStarted
        return System.currentTimeMillis() - diff
    }

    fun getConnectionDuration(): Long = SystemClock.elapsedRealtime() - this.lastConnectionStarted

    fun getDiscoDuration(): Long = SystemClock.elapsedRealtime() - this.lastDiscoStarted

    fun getLastPingSent(): Long = this.lastPingSent

    fun getLastPacketReceived(): Long = this.lastPacketReceived

    fun sendActive() {
        this.sendPacket(Active())
    }

    fun sendInactive() {
        this.sendPacket(Inactive())
    }

    fun resetAttemptCount(resetConnectTime: Boolean) {
        this.attempt = 0
        if (resetConnectTime) {
            this.lastConnectionStarted = 0
        }
    }

    fun setInteractive(interactive: Boolean) {
        this.mInteractive = interactive
    }

    private fun getIqGenerator(): IqGenerator = mXmppConnectionService.getIqGenerator()

    fun trackOfflineMessageRetrieval(trackOfflineMessageRetrieval: Boolean) {
        if (trackOfflineMessageRetrieval) {
            val iqPing = Iq(Iq.Type.GET)
            iqPing.addChild("ping", Namespace.PING)
            this.sendIqPacket(
                iqPing,
                Consumer {
                    Log.d(
                        Config.LOGTAG,
                        "${account.getJid().asBareJid()}: got ping response after sending " +
                            "initial presence",
                    )
                    this.offlineMessagesRetrieved = true
                },
            )
        } else {
            this.offlineMessagesRetrieved = true
        }
    }

    fun isOfflineMessagesRetrieved(): Boolean = this.offlineMessagesRetrieved

    fun fetchRoster() {
        val iqPacket = Iq(Iq.Type.GET)
        val version = account.getRosterVersion()
        if (account.getRosterVersion().isNullOrEmpty()) {
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: fetching roster")
        } else {
            Log.d(
                Config.LOGTAG,
                "${account.getJid().asBareJid()}: fetching roster version $version",
            )
        }
        iqPacket.query(Namespace.ROSTER).setAttribute("ver", version)
        sendIqPacket(iqPacket, unregisteredIqListener)
    }

    fun triggerConnectionTimeout() {
        val duration = getConnectionDuration()
        Log.d(
            Config.LOGTAG,
            "${account.getJid().asBareJid()}: connection timeout after ${duration}ms",
        )

        // last connection time gets reset so time to next attempt is calculated correctly
        this.lastConnectionStarted = SystemClock.elapsedRealtime()

        // interrupt needs to be called before status change; otherwise we interrupt the newly
        // created thread
        this.interrupt()
        this.forceCloseSocket()
        this.changeStatus(AccountRef.StateRef.CONNECTION_TIMEOUT)
    }

    /**
     * Tulkki: the `Features` view over the connection, whose fields are `internal` because Java's
     * nested-class private access has no Kotlin spelling: the outer class writes
     * `encryptionEnabled`, `carbonsEnabled` and `blockListRequested` and Kotlin forbids reading a
     * nested class's privates from its outer. `internal` keeps the accessors module-visible only -
     * they are name-mangled, so Java cannot see them.
     */
    inner class Features(private val connection: XmppConnection) {
        internal var carbonsEnabled = false
        internal var encryptionEnabled = false
        internal var blockListRequested = false

        private fun hasDiscoFeature(server: Jid, feature: String): Boolean {
            synchronized(this@XmppConnection.disco) {
                val sdr = connection.disco[server]
                return sdr != null && sdr.getFeatures().contains(feature)
            }
        }

        fun carbons(): Boolean = hasDiscoFeature(account.getDomain(), Namespace.CARBONS)

        fun commands(): Boolean = hasDiscoFeature(account.getDomain(), Namespace.COMMANDS)

        fun easyOnboardingInvites(): Boolean {
            synchronized(this@XmppConnection.commands) {
                return this@XmppConnection.commands.containsKey(Namespace.EASY_ONBOARDING_INVITE)
            }
        }

        fun bookmarksConversion(): Boolean =
            hasDiscoFeature(account.getJid().asBareJid(), Namespace.BOOKMARKS_CONVERSION) &&
                pepPublishOptions()

        fun blocking(): Boolean = hasDiscoFeature(account.getDomain(), Namespace.BLOCKING)

        fun spamReporting(): Boolean = hasDiscoFeature(account.getDomain(), Namespace.REPORTING)

        fun flexibleOfflineMessageRetrieval(): Boolean =
            hasDiscoFeature(account.getDomain(), Namespace.FLEXIBLE_OFFLINE_MESSAGE_RETRIEVAL)

        fun register(): Boolean = hasDiscoFeature(account.getDomain(), Namespace.REGISTER)

        fun invite(): Boolean {
            val features = connection.streamFeatures ?: return false
            return features.hasChild("register", Namespace.INVITE)
        }

        fun sm(): Boolean {
            val features = connection.streamFeatures
            return this@XmppConnection.streamId != null ||
                (features != null && features.streamManagement())
        }

        fun csi(): Boolean {
            val features = connection.streamFeatures ?: return false
            return features.clientStateIndication()
        }

        fun pep(): Boolean {
            synchronized(this@XmppConnection.disco) {
                val info = this@XmppConnection.disco[account.getJid().asBareJid()]
                return info != null && info.hasIdentity("pubsub", "pep")
            }
        }

        fun pepPersistent(): Boolean {
            synchronized(this@XmppConnection.disco) {
                val info = this@XmppConnection.disco[account.getJid().asBareJid()]
                return info != null &&
                    info.getFeatures()
                        .contains("http://jabber.org/protocol/pubsub#persistent-items")
            }
        }

        fun bind2(): Boolean {
            val loginInfo = this@XmppConnection.loginInfo
            return loginInfo != null && loginInfo.inlineBindFeatures.isNotEmpty()
        }

        fun sasl2(): Boolean {
            val loginInfo = this@XmppConnection.loginInfo
            return loginInfo != null && loginInfo.saslVersion == SaslMechanism.Version.SASL_2
        }

        fun loginMechanism(): String? {
            val loginInfo = this@XmppConnection.loginInfo
            return loginInfo?.saslMechanism?.getMechanism()
        }

        fun pepPublishOptions(): Boolean =
            hasDiscoFeature(account.getJid().asBareJid(), Namespace.PUBSUB_PUBLISH_OPTIONS)

        fun pepConfigNodeMax(): Boolean =
            hasDiscoFeature(account.getJid().asBareJid(), Namespace.PUBSUB_CONFIG_NODE_MAX)

        fun pepOmemoWhitelisted(): Boolean =
            hasDiscoFeature(account.getJid().asBareJid(), OmemoSessionPort.PEP_OMEMO_WHITELISTED)

        fun mam(): Boolean = MessageArchiveService.Version.has(getAccountFeatures())

        fun getAccountFeatures(): List<String> {
            val result = connection.disco[account.getJid().asBareJid()]
            return if (result == null) ArrayList() else result.getFeatures()
        }

        fun push(): Boolean =
            hasDiscoFeature(account.getJid().asBareJid(), Namespace.PUSH) ||
                hasDiscoFeature(account.getDomain(), Namespace.PUSH)

        fun rosterVersioning(): Boolean {
            val features = connection.streamFeatures ?: return false
            return features.hasChild("ver")
        }

        fun setBlockListRequested(value: Boolean) {
            this.blockListRequested = value
        }

        fun httpUpload(filesize: Long): Boolean {
            if (Config.DISABLE_HTTP_UPLOAD) {
                return false
            } else {
                for (namespace in arrayOf(Namespace.HTTP_UPLOAD, Namespace.HTTP_UPLOAD_LEGACY)) {
                    val items = findDiscoItemsByFeature(namespace)
                    if (items.isNotEmpty()) {
                        try {
                            val rawSize =
                                items[0]
                                    .value
                                    .getExtendedDiscoInformation(namespace, "max-file-size")
                            val maxsize =
                                (rawSize ?: throw NumberFormatException("null")).toLong()
                            if (filesize <= maxsize) {
                                return true
                            } else {
                                Log.d(
                                    Config.LOGTAG,
                                    "${account.getJid().asBareJid()}: http upload is not " +
                                        "available for files with size $filesize (max is " +
                                        "$maxsize)",
                                )
                                return false
                            }
                        } catch (e: Exception) {
                            return true
                        }
                    }
                }
                return false
            }
        }

        fun useLegacyHttpUpload(): Boolean =
            findDiscoItemByFeature(Namespace.HTTP_UPLOAD) == null &&
                findDiscoItemByFeature(Namespace.HTTP_UPLOAD_LEGACY) != null

        fun getMaxHttpUploadSize(): Long {
            for (namespace in arrayOf(Namespace.HTTP_UPLOAD, Namespace.HTTP_UPLOAD_LEGACY)) {
                val items = findDiscoItemsByFeature(namespace)
                if (items.isNotEmpty()) {
                    try {
                        val rawSize =
                            items[0]
                                .value
                                .getExtendedDiscoInformation(namespace, "max-file-size")
                        return (rawSize ?: throw NumberFormatException("null")).toLong()
                    } catch (e: Exception) {
                        // ignored
                    }
                }
            }
            return -1
        }

        fun stanzaIds(): Boolean =
            hasDiscoFeature(account.getJid().asBareJid(), Namespace.STANZA_IDS)

        fun bookmarks2(): Boolean =
            pepPublishOptions() &&
                pepConfigNodeMax() &&
                hasDiscoFeature(account.getJid().asBareJid(), Namespace.BOOKMARKS2_COMPAT)

        fun externalServiceDiscovery(): Boolean =
            hasDiscoFeature(account.getDomain(), Namespace.EXTERNAL_SERVICE_DISCOVERY)

        fun mds(): Boolean =
            pepPublishOptions() &&
                pepConfigNodeMax() &&
                Config.MESSAGE_DISPLAYED_SYNCHRONIZATION

        fun mdsServerAssist(): Boolean =
            hasDiscoFeature(account.getJid().asBareJid(), Namespace.MDS_DISPLAYED)
    }

    companion object {

        private val SCHEDULER: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

        private fun fixResource(context: Context, account: AccountRef) {
            val resource = account.getResource()
            val clientName =
                getEffectiveClientName(XmppConnectionService.dataStatics().settings(context))
            val expectedPrefix = "$clientName."
            val fixedPartLength = expectedPrefix.length
            val randomPartLength = 4 // 3 bytes base64-encoded
            if (resource == null || !resource.startsWith(expectedPrefix)) {
                // Resource doesn't match our format (e.g. server-assigned or accumulated
                // conflicts); strip the resource so bind() generates a fresh one.
                account.setJid(account.getJid().asBareJid())
                return
            }
            if (resource.length > fixedPartLength + randomPartLength) {
                if (validBase64(
                        resource.substring(fixedPartLength, fixedPartLength + randomPartLength),
                    )
                ) {
                    account.setResource(resource.substring(0, fixedPartLength + randomPartLength))
                } else {
                    account.setJid(account.getJid().asBareJid())
                }
            }
        }

        private fun validBase64(input: String): Boolean =
            try {
                Base64.decode(input, Base64.URL_SAFE).size == 3
            } catch (throwable: Throwable) {
                false
            }

        private fun sslSocketOrNull(socket: Socket?): SSLSocket? =
            if (socket is SSLSocket) socket else null

        private fun isFastTokenAvailable(authentication: Authentication?): Boolean {
            val inline = authentication?.getInline()
            return inline != null && inline.hasExtension(Fast::class.java)
        }

        private fun getRegistrationFailedState(response: Iq): AccountRef.StateRef {
            val passwordTooWeakMessages =
                listOf("The password is too weak", "Please use a longer password.")
            val error = response.getError()
            val condition = error?.getCondition()
            val state: AccountRef.StateRef
            if (condition is Condition.Conflict) {
                state = AccountRef.StateRef.REGISTRATION_CONFLICT
            } else if (condition is Condition.ResourceConstraint) {
                state = AccountRef.StateRef.REGISTRATION_PLEASE_WAIT
            } else if (condition is Condition.NotAcceptable &&
                error != null &&
                passwordTooWeakMessages.contains(error.getTextAsString())
            ) {
                state = AccountRef.StateRef.REGISTRATION_PASSWORD_TOO_WEAK
            } else {
                state = AccountRef.StateRef.REGISTRATION_FAILED
            }
            return state
        }

        private fun getEffectiveClientName(settings: AppSettingsRef): String {
            val custom = settings.getCustomResourceName()
            return if (custom.isEmpty()) BuildConfig.APP_NAME else custom
        }
    }
}
