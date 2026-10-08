package uk.xa0.tulkki.xmpp.jingle.transports

import android.util.Log
import com.google.common.base.Joiner
import com.google.common.base.MoreObjects
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Ordering
import com.google.common.hash.Hashing
import com.google.common.io.ByteStreams
import com.google.common.primitives.Ints
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.ArrayList
import java.util.Comparator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.DirectConnectionUtils
import uk.xa0.tulkki.xmpp.jingle.stanzas.SocksByteStreamsTransportInfo
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.utils.SocksSocketFactory
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The SOCKS5 bytestream transport (XEP-0260).
 *
 * Ported from Java by lane B. Decisions taken rather than inherited:
 *
 * 1. **The private primary constructor carries the seven fields and the two Java-**public**
 *    constructors are secondary.** A Kotlin primary constructor's parameter type cannot name the
 *    class's *own* nested class (verified with the compiler: `Collection<Candidate>` is unresolved in
 *    the class header), and Java's eight-argument constructor takes `Collection<Candidate>`;
 *    `private class`-body secondary constructors can, and they keep the two public JVM signatures
 *    exactly.
 * 2. **The eight-argument constructor's collection parameter is named `peerCandidates`**, because the
 *    property `theirCandidates` is assigned from it in `init` and the two names would otherwise
 *    collide. New Kotlin callers see the new name; the JVM signature is unchanged.
 * 3. **`Owner` and `ConnectionWithOwner` are `internal`.** Java exposed a `public final Owner owner`
 *    field on a public nested class while `Owner` itself was private, which Kotlin refuses ("public
 *    property exposes its private type"); nothing outside this file names either, so both narrowed to
 *    the module rather than widening `Owner` to the world.
 * 4. **`CandidateErrorException`'s constructor is `internal`.** Java had it private and both the outer
 *    class and the sibling `ConnectionFinder` construct it; Kotlin forbids one nested class reaching
 *    another's private member.
 * 5. **`ConnectionProvider.candidates`, `ConnectionProvider.peerConnections` and
 *    `ConnectionFinder.connectionFuture` are not private.** The outer class reads all three, and Kotlin
 *    forbids an outer class reaching a nested class's private member (the
 *    `IceUdpTransportInfo.Fingerprint` precedent). `ConnectionFinder.destination`/`useTor`/`useI2P`
 *    stay private.
 * 6. **`closeSocket`/`closeConnections`/`closeServerSocket` are file-private top-level functions**, the
 *    shape this tree uses for Java's private statics, because nested classes call them too.
 * 7. **`setCandidateUsed`'s `cid` is `String?`** — the caller is `JingleFileTransferConnection:729`
 *    handing it `CandidateUsed.cid`, which `SocksByteStreamsTransportInfo` declares nullable; Java's
 *    body only compares and logs it. `setProxyActivated`'s `cid` stays non-null because Java's body
 *    stores it in a `SettableFuture<String>` and Kotlin's `set(null)` would not compile there.
 * 8. **`cid`, `host`, `jid`, `port`, `priority` and `type` on `Candidate` are `@JvmField`s**, as Java
 *    declared them, because `JingleFileTransferConnection:1115`/`:1145` read `candidate.cid` as a
 *    field. `cid`/`host` are non-null: `Candidate.of` checks both and the other two constructions pass
 *    a `UUID`.
 * 9. **`Ordering.from(...).immutableSortedCopy(...)`, `Iterables.tryFind`, Guava's `Optional` and
 *    `Strings.nullToEmpty` are kept**, so the sort order and the "absent" answers are Java's; the
 *    `Optional.isPresent` reads use Kotlin's property form, which this tree already does.
 * 10. **`ServerSocket(this.port).use { … }` stands for Java's try-with-resources**, so the socket is
 *     still closed on the `return` paths.
 */
class SocksByteStreamsTransport private constructor(
    private val xmppConnection: XmppConnection,
    private val id: AbstractJingleConnection.Id,
    private val initiator: Boolean,
    private val useTor: Boolean,
    private val useI2P: Boolean,
    private val useRelays: Boolean,
    private val streamId: String,
) : Transport {

    private var theirCandidates: ImmutableList<Candidate> = ImmutableList.of<Candidate>()
    private val theirDestination: String
    private val selectedByThemCandidate: SettableFuture<Connection> = SettableFuture.create()
    private val theirProxyActivation: SettableFuture<String> = SettableFuture.create()

    private val terminationLatch = CountDownLatch(1)

    private val connectionProvider: ConnectionProvider
    private val ourProxyConnection: ListenableFuture<Connection>

    private var connection: Connection? = null

    private var transportCallback: Transport.Callback? = null

    init {
        this.theirDestination =
            Hashing.sha1()
                .hashString(
                    Joiner.on("")
                        .join(
                            listOf(streamId, id.with.toString(), id.account.getJid().toString())
                        ),
                    StandardCharsets.UTF_8,
                )
                .toString()
        val ourDestination =
            Hashing.sha1()
                .hashString(
                    Joiner.on("")
                        .join(
                            listOf(streamId, id.account.getJid().toString(), id.with.toString())
                        ),
                    StandardCharsets.UTF_8,
                )
                .toString()
        this.connectionProvider =
            ConnectionProvider(id.account.getJid(), ourDestination, useTor, useI2P, useRelays)
        Thread(connectionProvider).start()
        this.ourProxyConnection = getOurProxyConnection(ourDestination)
    }

    constructor(
        xmppConnection: XmppConnection,
        id: AbstractJingleConnection.Id,
        initiator: Boolean,
        useTor: Boolean,
        useI2P: Boolean,
        useRelays: Boolean,
        streamId: String,
        peerCandidates: Collection<Candidate>,
    ) : this(xmppConnection, id, initiator, useTor, useI2P, useRelays, streamId) {
        setTheirCandidates(peerCandidates)
    }

    constructor(
        xmppConnection: XmppConnection,
        id: AbstractJingleConnection.Id,
        initiator: Boolean,
        useTor: Boolean,
        useI2P: Boolean,
        useRelays: Boolean,
    ) : this(
        xmppConnection,
        id,
        initiator,
        useTor,
        useI2P,
        useRelays,
        UUID.randomUUID().toString(),
        emptyList(),
    )
    fun connectTheirCandidates() {
        Preconditions.checkState(
            this.transportCallback != null,
            "transport callback needs to be set",
        )
        // TODO this needs to go into a variable so we can cancel it
        val future: ListenableFuture<Connection>
        if (useRelays) {
            future =
                Futures.immediateFailedFuture(
                    IllegalStateException("Connecting to their candidates is disabled by setting")
                )
        } else {
            val connectionFinder =
                ConnectionFinder(
                    theirCandidates,
                    theirDestination,
                    selectedByThemCandidate,
                    useTor,
                    useI2P,
                )
            Thread(connectionFinder).start()
            future = connectionFinder.connectionFuture
        }
        Futures.addCallback(
            future,
            object : FutureCallback<Connection> {
                override fun onSuccess(connection: Connection) {
                    val candidate = connection.candidate
                    (transportCallback ?: throw NullPointerException())
                        .onCandidateUsed(streamId, candidate)
                    establishTransport(connection)
                }

                override fun onFailure(throwable: Throwable) {
                    if (throwable is CandidateErrorException) {
                        (transportCallback ?: throw NullPointerException())
                            .onCandidateError(streamId)
                    }
                    establishTransport(null)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun establishTransport(selectedByUs: Connection?) {
        Futures.addCallback(
            selectedByThemCandidate,
            object : FutureCallback<Connection> {
                override fun onSuccess(result: Connection) {
                    establishTransport(selectedByUs, result)
                }

                override fun onFailure(throwable: Throwable) {
                    establishTransport(selectedByUs, null)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun establishTransport(selectedByUs: Connection?, selectedByThem: Connection?) {
        val selection = selectConnection(selectedByUs, selectedByThem)
        if (selection == null) {
            (transportCallback ?: throw NullPointerException()).onTransportSetupFailed()
            return
        }
        if (selection.connection.candidate.type == CandidateType.DIRECT) {
            Log.d(Config.LOGTAG, "final selection " + selection.connection.candidate)
            this.connection = selection.connection
            (this.transportCallback ?: throw NullPointerException()).onTransportEstablished()
        } else {
            val proxyActivation: ListenableFuture<String>
            if (selection.owner == Owner.THEIRS) {
                proxyActivation = this.theirProxyActivation
            } else {
                proxyActivation = activateProxy(selection.connection.candidate)
            }
            Log.d(Config.LOGTAG, "waiting for proxy activation")
            Futures.addCallback(
                proxyActivation,
                object : FutureCallback<String> {
                    override fun onSuccess(cid: String) {
                        // TODO compare cid to selection.connection.candidate
                        connection = selection.connection
                        (transportCallback ?: throw NullPointerException())
                            .onTransportEstablished()
                    }

                    override fun onFailure(throwable: Throwable) {
                        Log.d(Config.LOGTAG, "failed to activate proxy")
                    }
                },
                MoreExecutors.directExecutor(),
            )
        }
    }

    private fun selectConnection(
        selectedByUs: Connection?,
        selectedByThem: Connection?,
    ): ConnectionWithOwner? {
        if (selectedByUs != null && selectedByThem != null) {
            if (selectedByUs.candidate.priority == selectedByThem.candidate.priority) {
                return if (initiator) {
                    ConnectionWithOwner(selectedByUs, Owner.THEIRS)
                } else {
                    ConnectionWithOwner(selectedByThem, Owner.OURS)
                }
            } else if (selectedByUs.candidate.priority > selectedByThem.candidate.priority) {
                return ConnectionWithOwner(selectedByUs, Owner.THEIRS)
            } else {
                return ConnectionWithOwner(selectedByThem, Owner.OURS)
            }
        }
        if (selectedByUs != null) {
            return ConnectionWithOwner(selectedByUs, Owner.THEIRS)
        }
        if (selectedByThem != null) {
            return ConnectionWithOwner(selectedByThem, Owner.OURS)
        }
        return null
    }

    private fun activateProxy(candidate: Candidate): ListenableFuture<String> {
        Log.d(Config.LOGTAG, "trying to activate our proxy " + candidate)
        val iqFuture = SettableFuture.create<String>()
        val proxyActivation = Iq(Iq.Type.SET)
        proxyActivation.setTo(candidate.jid)
        val query = proxyActivation.addChild("query", Namespace.BYTE_STREAMS)
        query.setAttribute("sid", this.streamId)
        val activate = query.addChild("activate")
        activate.setContent(id.with.toString())
        xmppConnection.sendIqPacket(proxyActivation) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, "our proxy has been activated")
                (transportCallback ?: throw NullPointerException())
                    .onProxyActivated(this.streamId, candidate)
                iqFuture.set(candidate.cid)
            } else if (response.getType() == Iq.Type.TIMEOUT) {
                iqFuture.setException(TimeoutException())
            } else {
                val account = id.account
                Log.d(
                    Config.LOGTAG,
                    "" +
                        account.getJid().asBareJid() +
                        ": failed to activate proxy on " +
                        candidate.jid,
                )
                iqFuture.setException(IllegalStateException("Proxy activation failed"))
            }
        }
        return iqFuture
    }

    private fun getOurProxyConnection(ourDestination: String): ListenableFuture<Connection> {
        val proxyFuture = getProxyCandidate()
        return Futures.transformAsync(
            proxyFuture,
            { proxy ->
                val connectionFinder =
                    ConnectionFinder(
                        ImmutableList.of(proxy),
                        ourDestination,
                        null,
                        useTor,
                        useI2P,
                    )
                Thread(connectionFinder).start()
                Futures.transform(
                    connectionFinder.connectionFuture,
                    { c ->
                        try {
                            c.socket.setKeepAlive(true)
                            Log.d(
                                Config.LOGTAG,
                                "set keep alive on our own proxy connection",
                            )
                        } catch (e: SocketException) {
                            throw RuntimeException(e)
                        }
                        c
                    },
                    MoreExecutors.directExecutor(),
                )
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun getProxyCandidate(): ListenableFuture<Candidate> {
        if (Config.DISABLE_PROXY_LOOKUP) {
            return Futures.immediateFailedFuture(
                IllegalStateException("Proxy look up is disabled")
            )
        }
        val streamer = xmppConnection.findDiscoItemByFeature(Namespace.BYTE_STREAMS)
        if (streamer == null) {
            return Futures.immediateFailedFuture(IllegalStateException("No proxy/streamer found"))
        }
        val iqRequest = Iq(Iq.Type.GET)
        iqRequest.setTo(streamer)
        iqRequest.query(Namespace.BYTE_STREAMS)
        val candidateFuture = SettableFuture.create<Candidate>()
        xmppConnection.sendIqPacket(iqRequest) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                val query = response.findChild("query", Namespace.BYTE_STREAMS)
                val streamHost =
                    query?.findChild("streamhost", Namespace.BYTE_STREAMS)
                val host = streamHost?.getAttribute("host")
                val port =
                    Ints.tryParse(
                        Strings.nullToEmpty(streamHost?.getAttribute("port"))
                    )
                if (host.isNullOrEmpty() || port == null) {
                    candidateFuture.setException(
                        IOException("Proxy response is missing attributes")
                    )
                    return@sendIqPacket
                }
                candidateFuture.set(
                    Candidate(
                        UUID.randomUUID().toString(),
                        host,
                        streamer,
                        port,
                        655360 + (if (initiator) 0 else 15),
                        CandidateType.PROXY,
                    )
                )
            } else if (response.getType() == Iq.Type.TIMEOUT) {
                candidateFuture.setException(TimeoutException())
            } else {
                candidateFuture.setException(
                    IOException("received iq error in response to proxy discovery")
                )
            }
        }
        return candidateFuture
    }

    @Throws(IOException::class)
    override fun getOutputStream(): OutputStream {
        val connection = this.connection
        if (connection == null) {
            throw IOException("No candidate has been selected yet")
        }
        return connection.socket.getOutputStream()
    }

    @Throws(IOException::class)
    override fun getInputStream(): InputStream {
        val connection = this.connection
        if (connection == null) {
            throw IOException("No candidate has been selected yet")
        }
        return connection.socket.getInputStream()
    }

    override fun asTransportInfo(): ListenableFuture<Transport.TransportInfo> {
        val proxyConnections = getOurProxyConnectionsFuture()
        return Futures.transform(
            proxyConnections,
            { proxies ->
                val candidateBuilder = ImmutableList.builder<Candidate>()
                candidateBuilder.addAll(this.connectionProvider.candidates)
                candidateBuilder.addAll(proxies.map { p -> p.candidate })
                val transportInfo =
                    SocksByteStreamsTransportInfo(this.streamId, candidateBuilder.build())
                Transport.TransportInfo(transportInfo, null)
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun asInitialTransportInfo(): ListenableFuture<Transport.InitialTransportInfo> =
        Futures.transform(
            asTransportInfo(),
            { ti ->
                Transport.InitialTransportInfo(
                    UUID.randomUUID().toString(),
                    ti.transportInfo,
                    ti.group,
                )
            },
            MoreExecutors.directExecutor(),
        )

    private fun getOurProxyConnectionsFuture(): ListenableFuture<Collection<Connection>> =
        Futures.catching<Collection<Connection>, Exception>(
            Futures.transform(
                this.ourProxyConnection,
                { connection -> listOf(connection) },
                MoreExecutors.directExecutor(),
            ),
            Exception::class.java,
            { ex ->
                Log.d(Config.LOGTAG, "could not find a proxy of our own", ex)
                emptyList()
            },
            MoreExecutors.directExecutor(),
        )

    private fun getOurProxyConnections(): Collection<Connection> {
        val future = getOurProxyConnectionsFuture()
        if (future.isDone()) {
            try {
                return future.get()
            } catch (e: Exception) {
                return emptyList()
            }
        } else {
            return emptyList()
        }
    }

    override fun terminate() {
        Log.d(Config.LOGTAG, "terminating socks transport")
        this.terminationLatch.countDown()
        val connection = this.connection
        if (connection != null) {
            closeSocket(connection.socket)
        }
        this.connectionProvider.close()
    }

    override fun setTransportCallback(callback: Transport.Callback) {
        this.transportCallback = callback
    }

    override fun connect() {
        this.connectTheirCandidates()
    }

    override fun getTerminationLatch(): CountDownLatch = this.terminationLatch

    fun setCandidateUsed(cid: String?): Boolean {
        val ourProxyConnections = getOurProxyConnections()
        val proxyConnection = Iterables.tryFind(ourProxyConnections) { c -> c.candidate.cid == cid }
        if (proxyConnection.isPresent) {
            this.selectedByThemCandidate.set(proxyConnection.get())
            return true
        }

        // the peer selected a connection that is not our proxy. so we can close our proxies
        closeConnections(ourProxyConnections)

        val connection = this.connectionProvider.findPeerConnection(cid)
        if (connection.isPresent) {
            this.selectedByThemCandidate.set(connection.get())
            return true
        } else {
            Log.d(Config.LOGTAG, "none of the connected candidates has cid " + cid)
            return false
        }
    }

    fun setCandidateError() {
        this.selectedByThemCandidate.setException(
            CandidateErrorException("Remote could not connect to any of our candidates")
        )
    }

    fun setProxyActivated(cid: String) {
        this.theirProxyActivation.set(cid)
    }

    fun setProxyError() {
        this.theirProxyActivation.setException(
            IllegalStateException("Remote could not activate their proxy")
        )
    }

    fun setTheirCandidates(candidates: Collection<Candidate>) {
        this.theirCandidates =
            Ordering.from(
                    Comparator<Candidate> { o1, o2 -> Integer.compare(o2.priority, o1.priority) }
                )
                .immutableSortedCopy(candidates)
    }

    private class ConnectionProvider(
        private val account: Jid,
        destination: String,
        useTor: Boolean,
        useI2P: Boolean,
        useRelays: Boolean,
    ) : Runnable {

        private val clientConnectionExecutorService: ExecutorService = Executors.newFixedThreadPool(4)

        val candidates: ImmutableList<Candidate>

        private val port: Int = SecureRandom().nextInt(60_000) + 1024

        private val acceptingConnections = AtomicBoolean(true)

        private var serverSocket: ServerSocket? = null

        private val destination: String = destination

        val peerConnections = ArrayList<Connection>()

        init {
            val localAddresses: Array<InetAddress>
            if (Config.USE_DIRECT_JINGLE_CANDIDATES && !useTor && !useI2P && !useRelays) {
                localAddresses = DirectConnectionUtils.getLocalAddresses().toTypedArray()
            } else {
                localAddresses = emptyArray()
            }
            val candidateBuilder = ImmutableList.builder<Candidate>()
            for (i in localAddresses.indices) {
                val inetAddress = localAddresses[i]
                candidateBuilder.add(
                    Candidate(
                        UUID.randomUUID().toString(),
                        inetAddress.getHostAddress(),
                        account,
                        port,
                        8257536 + i,
                        CandidateType.DIRECT,
                    )
                )
            }
            this.candidates = candidateBuilder.build()
        }

        override fun run() {
            if (this.candidates.isEmpty()) {
                Log.d(Config.LOGTAG, "no direct candidates. stopping ConnectionProvider")
                return
            }
            try {
                ServerSocket(this.port).use { serverSocket ->
                    this.serverSocket = serverSocket
                    while (acceptingConnections.get()) {
                        val clientSocket: Socket
                        try {
                            clientSocket = serverSocket.accept()
                        } catch (ignored: SocketException) {
                            Log.d(Config.LOGTAG, "server socket has been closed.")
                            return
                        }
                        clientConnectionExecutorService.execute {
                            acceptClientConnection(clientSocket)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "could not create server socket", e)
            }
        }

        private fun acceptClientConnection(socket: Socket) {
            val localAddress = socket.getLocalAddress()
            val hostAddress = localAddress?.getHostAddress()
            val candidate = Iterables.tryFind(this.candidates) { c -> c.host == hostAddress }
            if (candidate.isPresent) {
                acceptingConnections(socket, candidate.get())
            } else {
                closeSocket(socket)
                Log.d(
                    Config.LOGTAG,
                    "no local candidate found for connection on " + hostAddress,
                )
            }
        }

        private fun acceptingConnections(socket: Socket, candidate: Candidate) {
            val remoteAddress = socket.getRemoteSocketAddress()
            Log.d(
                Config.LOGTAG,
                "accepted client connection from " + remoteAddress + " to " + candidate,
            )
            try {
                socket.setSoTimeout(3000)
                val authBegin = ByteArray(2)
                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()
                ByteStreams.readFully(inputStream, authBegin)
                if (authBegin[0] != 0x5.toByte()) {
                    socket.close()
                }
                val methodCount = authBegin[1].toInt()
                val methods = ByteArray(methodCount)
                ByteStreams.readFully(inputStream, methods)
                if (SocksSocketFactory.contains(0x00.toByte(), methods)) {
                    outputStream.write(byteArrayOf(0x05, 0x00))
                } else {
                    outputStream.write(byteArrayOf(0x05, 0xff.toByte()))
                }
                val connectCommand = ByteArray(4)
                ByteStreams.readFully(inputStream, connectCommand)
                if (connectCommand[0] == 0x05.toByte() &&
                    connectCommand[1] == 0x01.toByte() &&
                    connectCommand[3] == 0x03.toByte()
                ) {
                    val destinationCount = inputStream.read()
                    val destination = ByteArray(destinationCount)
                    ByteStreams.readFully(inputStream, destination)
                    val port = ByteArray(2)
                    ByteStreams.readFully(inputStream, port)
                    val receivedDestination = String(destination)
                    val response = ByteBuffer.allocate(7 + destination.size)
                    val responseHeader: ByteArray
                    val success: Boolean
                    if (receivedDestination == this.destination) {
                        responseHeader = byteArrayOf(0x05, 0x00, 0x00, 0x03)
                        synchronized(this.peerConnections) {
                            peerConnections.add(Connection(candidate, socket))
                        }
                        success = true
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "destination mismatch. received " +
                                receivedDestination +
                                " (expected " +
                                this.destination +
                                ")",
                        )
                        responseHeader = byteArrayOf(0x05, 0x04, 0x00, 0x03)
                        success = false
                    }
                    response.put(responseHeader)
                    response.put(destination.size.toByte())
                    response.put(destination)
                    response.put(port)
                    outputStream.write(response.array())
                    outputStream.flush()
                    if (success) {
                        Log.d(
                            Config.LOGTAG,
                            remoteAddress.toString() + " successfully connected to " + candidate,
                        )
                    } else {
                        closeSocket(socket)
                    }
                }
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "failed to accept client connection to " + candidate, e)
                closeSocket(socket)
            }
        }

        fun findPeerConnection(cid: String?): Optional<Connection> {
            synchronized(this.peerConnections) {
                return Iterables.tryFind(this.peerConnections) { connection ->
                    connection.candidate.cid == cid
                }
            }
        }

        fun close() {
            this.acceptingConnections.set(false) // we have probably done this earlier already
            closeServerSocket(this.serverSocket)
            synchronized(this.peerConnections) {
                closeConnections(this.peerConnections)
                this.peerConnections.clear()
            }
        }
    }

    private class ConnectionFinder(
        private val candidates: ImmutableList<Candidate>,
        private val destination: String,
        private val selectedByThemCandidate: ListenableFuture<Connection>?,
        private val useTor: Boolean,
        private val useI2P: Boolean,
    ) : Runnable {

        val connectionFuture: SettableFuture<Connection> = SettableFuture.create()

        override fun run() {
            for (candidate in this.candidates) {
                val selectedByThemCandidatePriority = getSelectedByThemCandidatePriority()
                if (selectedByThemCandidatePriority != null &&
                    selectedByThemCandidatePriority > candidate.priority
                ) {
                    Log.d(
                        Config.LOGTAG,
                        "The candidate selected by peer had a higher priority then anything we could try",
                    )
                    connectionFuture.setException(
                        CandidateErrorException(
                            "The candidate selected by peer had a higher priority then anything we could try"
                        )
                    )
                    return
                }
                try {
                    connectionFuture.set(connect(candidate))
                    Log.d(Config.LOGTAG, "connected to " + candidate)
                    return
                } catch (e: IOException) {
                    Log.d(Config.LOGTAG, "could not connect to candidate " + candidate)
                }
            }
            connectionFuture.setException(
                CandidateErrorException(
                    String.format(
                        Locale.US,
                        "Gave up after %d candidates",
                        this.candidates.size,
                    )
                )
            )
        }

        @Throws(IOException::class)
        private fun connect(candidate: Candidate): Connection {
            val timeout = 3000
            val socket: Socket
            if (useTor && !useI2P) {
                Log.d(Config.LOGTAG, "using Tor to connect to candidate " + candidate.host)
                socket = SocksSocketFactory.createSocketOverTor(candidate.host, candidate.port)
            } else if (useI2P) {
                socket = SocksSocketFactory.createSocketOverI2P(candidate.host, candidate.port)
            } else {
                socket = Socket()
                val address: SocketAddress = InetSocketAddress(candidate.host, candidate.port)
                socket.connect(address, timeout)
            }
            socket.setSoTimeout(timeout)
            SocksSocketFactory.createSocksConnection(socket, destination, 0)
            socket.setSoTimeout(0)
            return Connection(candidate, socket)
        }

        private fun getSelectedByThemCandidatePriority(): Int? {
            val future = this.selectedByThemCandidate
            if (future != null && future.isDone()) {
                try {
                    val connection = future.get()
                    return connection.candidate.priority
                } catch (e: ExecutionException) {
                    return null
                } catch (e: InterruptedException) {
                    return null
                }
            } else {
                return null
            }
        }
    }

    class CandidateErrorException internal constructor(message: String) :
        IllegalStateException(message)

    internal enum class Owner {
        THEIRS,
        OURS,
    }

    internal class ConnectionWithOwner(
        @JvmField internal val connection: Connection,
        @JvmField internal val owner: Owner,
    )

    class Connection(
        @JvmField val candidate: Candidate,
        @JvmField val socket: Socket,
    )

    class Candidate(
        @JvmField val cid: String,
        @JvmField val host: String,
        @JvmField val jid: Jid,
        @JvmField val port: Int,
        @JvmField val priority: Int,
        @JvmField val type: CandidateType,
    ) : Transport.Candidate {

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("cid", cid)
                .add("host", host)
                .add("jid", jid)
                .add("port", port)
                .add("priority", priority)
                .add("type", type)
                .toString()

        fun asElement(): Element {
            val element = Element("candidate", Namespace.JINGLE_TRANSPORTS_S5B)
            element.setAttribute("cid", this.cid)
            element.setAttribute("host", this.host)
            element.setAttribute("jid", this.jid)
            element.setAttribute("port", this.port)
            element.setAttribute("priority", this.priority)
            element.setAttribute("type", this.type.toString().lowercase(Locale.ROOT))
            return element
        }

        companion object {
            @JvmStatic
            fun of(element: Element): Candidate {
                Preconditions.checkArgument(
                    "candidate" == element.getName(),
                    "trying to construct candidate from non candidate element",
                )
                Preconditions.checkArgument(
                    Namespace.JINGLE_TRANSPORTS_S5B == element.getNamespace(),
                    "candidate element is in correct namespace",
                )
                val cid = element.getAttribute("cid")
                val host = element.getAttribute("host")
                val jid = element.getAttribute("jid")
                val port = element.getAttribute("port")
                val priority = element.getAttribute("priority")
                val type = element.getAttribute("type")
                if (cid.isNullOrEmpty() ||
                    host.isNullOrEmpty() ||
                    jid.isNullOrEmpty() ||
                    port.isNullOrEmpty() ||
                    priority.isNullOrEmpty() ||
                    type.isNullOrEmpty()
                ) {
                    throw IllegalArgumentException("Candidate is missing non optional attribute")
                }
                return Candidate(
                    cid,
                    host,
                    Jid.of(jid),
                    Integer.parseInt(port),
                    Integer.parseInt(priority),
                    CandidateType.valueOf(type.uppercase(Locale.ROOT)),
                )
            }
        }
    }

    enum class CandidateType {
        DIRECT,
        PROXY,
    }
}

private fun closeSocket(socket: Socket) {
    try {
        socket.close()
    } catch (e: IOException) {
        Log.w(Config.LOGTAG, "error closing socket", e)
    }
}

private fun closeConnections(connections: Iterable<SocksByteStreamsTransport.Connection>) {
    for (connection in connections) {
        closeSocket(connection.socket)
    }
}

private fun closeServerSocket(serverSocket: ServerSocket?) {
    if (serverSocket == null) {
        return
    }
    try {
        serverSocket.close()
    } catch (ignored: IOException) {
        // ignored
    }
}
