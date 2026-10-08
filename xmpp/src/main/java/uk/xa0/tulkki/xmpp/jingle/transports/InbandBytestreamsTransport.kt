package uk.xa0.tulkki.xmpp.jingle.transports

import android.util.Log
import com.google.common.io.BaseEncoding
import com.google.common.io.Closeables
import com.google.common.primitives.Ints
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.jingle.stanzas.IbbTransportInfo
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The in-band bytestream transport (XEP-0261).
 *
 * Ported from Java by lane B. Decisions taken rather than inherited:
 *
 * 1. **The two constructors stay two.** The five-argument one is primary; the three-argument one
 *    delegates to it with a random stream id and the default block size, exactly as Java's `this(...)`
 *    did.
 * 2. **`streamId` is a `val`**, so the `getStreamId()` that `JingleConnectionManager:985` calls keeps
 *    its name; `blockSize` and `transportCallback` stay private and mutable, as Java declared them.
 * 3. **`receiveResponseToOpen`/`receiveOpen` reach their nullable callback through
 *    `?: throw NullPointerException()`.** Java evaluated `transportCallback.…` with no guard, so the
 *    failure is spelled out rather than silently skipped. `!!` is not used.
 * 4. **`from` is `Jid?`.** Java's first statement was `from == null ||`, and
 *    `JingleConnectionManager:986` passes `packet.getFrom()`, which can be null.
 * 5. **`deliverPacket`'s `when` keeps Java's `default ->` throw** as an `else` branch, and the
 *    `PacketType` constants are unchanged.
 * 6. **`Ints.saturatedCast` is kept** in `setPeerBlockSize`, because Kotlin's `Long.toInt()` truncates
 *    where Guava saturates, and a truncated negative would survive the `minOf` clamp Java applied.
 * 7. **`BlockSender` is a private nested class** (`static` in Java), and the `closeQuietly` helper is a
 *    file-private top-level function, the shape the tree uses for Java's private statics.
 */
class InbandBytestreamsTransport(
    private val xmppConnection: XmppConnection,
    private val with: Jid,
    private val initiator: Boolean,
    val streamId: String,
    blockSize: Int,
) : Transport {

    private var blockSize: Int = minOf(DEFAULT_BLOCK_SIZE, blockSize)
    private var transportCallback: Transport.Callback? = null

    private val pipedInputStream = PipedInputStream(DEFAULT_BLOCK_SIZE)
    private val pipedOutputStream = PipedOutputStream()
    private val terminationLatch = CountDownLatch(1)
    private val blockSender =
        BlockSender(xmppConnection, with, streamId, this.blockSize, pipedInputStream)
    private val blockSenderThread = Thread(blockSender)

    private val isReceiving = AtomicBoolean(false)

    constructor(xmppConnection: XmppConnection, with: Jid, initiator: Boolean) :
        this(
            xmppConnection,
            with,
            initiator,
            UUID.randomUUID().toString(),
            DEFAULT_BLOCK_SIZE,
        )

    override fun setTransportCallback(callback: Transport.Callback) {
        this.transportCallback = callback
    }

    override fun connect() {
        if (initiator) {
            openInBandTransport()
        }
    }

    override fun getTerminationLatch(): CountDownLatch = this.terminationLatch

    private fun openInBandTransport() {
        val iqPacket = Iq(Iq.Type.SET)
        iqPacket.setTo(with)
        val open = iqPacket.addChild("open", Namespace.IBB)
        open.setAttribute("block-size", this.blockSize)
        open.setAttribute("sid", this.streamId)
        Log.d(Config.LOGTAG, "sending ibb open")
        Log.d(Config.LOGTAG, iqPacket.toString())
        xmppConnection.sendIqPacket(iqPacket) { response -> receiveResponseToOpen(response) }
    }

    private fun receiveResponseToOpen(response: Iq) {
        if (response.getType() == Iq.Type.RESULT) {
            Log.d(Config.LOGTAG, "ibb open was accepted")
            (this.transportCallback ?: throw NullPointerException()).onTransportEstablished()
            this.blockSenderThread.start()
        } else {
            (this.transportCallback ?: throw NullPointerException()).onTransportSetupFailed()
        }
    }

    fun deliverPacket(packetType: PacketType, from: Jid?, payload: Element): Boolean {
        if (from == null || from != with) {
            Log.d(
                Config.LOGTAG,
                "ibb packet received from wrong address. was " + from + " expected " + with,
            )
            return false
        }
        return when (packetType) {
            PacketType.OPEN -> receiveOpen()
            PacketType.DATA -> receiveData(payload.getContent())
            PacketType.CLOSE -> receiveClose()
            else -> throw IllegalArgumentException("Invalid packet type")
        }
    }

    private fun receiveData(encoded: String): Boolean {
        val buffer: ByteArray
        if (encoded.isNullOrEmpty()) {
            buffer = ByteArray(0)
        } else {
            buffer = BaseEncoding.base64().decode(encoded)
        }
        Log.d(Config.LOGTAG, "ibb received " + buffer.size + " bytes")
        try {
            pipedOutputStream.write(buffer)
            pipedOutputStream.flush()
            return true
        } catch (e: IOException) {
            Log.d(Config.LOGTAG, "unable to receive ibb data", e)
            return false
        }
    }

    private fun receiveClose(): Boolean {
        if (this.isReceiving.compareAndSet(true, false)) {
            try {
                this.pipedOutputStream.close()
                return true
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "could not close pipedOutStream")
                return false
            }
        } else {
            Log.d(Config.LOGTAG, "received ibb close but was not receiving")
            return false
        }
    }

    private fun receiveOpen(): Boolean {
        Log.d(Config.LOGTAG, "receiveOpen()")
        if (this.isReceiving.get()) {
            Log.d(Config.LOGTAG, "ibb received open even though we were already open")
            return false
        }
        this.isReceiving.set(true)
        (transportCallback ?: throw NullPointerException()).onTransportEstablished()
        return true
    }

    override fun terminate() {
        // TODO send close
        Log.d(Config.LOGTAG, "IbbTransport.terminate()")
        this.terminationLatch.countDown()
        this.blockSender.close()
        this.blockSenderThread.interrupt()
        closeQuietly(this.pipedOutputStream)
    }

    @Throws(IOException::class)
    override fun getOutputStream(): OutputStream {
        val outputStream = PipedOutputStream()
        this.pipedInputStream.connect(outputStream)
        return outputStream
    }

    @Throws(IOException::class)
    override fun getInputStream(): InputStream {
        val inputStream = PipedInputStream()
        this.pipedOutputStream.connect(inputStream)
        return inputStream
    }

    override fun asTransportInfo(): ListenableFuture<Transport.TransportInfo> =
        Futures.immediateFuture(
            Transport.TransportInfo(IbbTransportInfo(streamId, blockSize), null)
        )

    override fun asInitialTransportInfo(): ListenableFuture<Transport.InitialTransportInfo> =
        Futures.immediateFuture(
            Transport.InitialTransportInfo(
                UUID.randomUUID().toString(),
                IbbTransportInfo(streamId, blockSize),
                null,
            )
        )

    fun setPeerBlockSize(peerBlockSize: Long) {
        this.blockSize = minOf(Ints.saturatedCast(peerBlockSize), DEFAULT_BLOCK_SIZE)
        if (this.blockSize < DEFAULT_BLOCK_SIZE) {
            Log.d(Config.LOGTAG, "peer reconfigured IBB block size to " + this.blockSize)
        }
        this.blockSender.setBlockSize(this.blockSize)
    }

    private class BlockSender(
        private val xmppConnection: XmppConnection,
        private val with: Jid,
        private val streamId: String,
        private var blockSize: Int,
        private val inputStream: PipedInputStream,
    ) : Runnable, Closeable {

        private val semaphore = Semaphore(3)
        private val sequencer = AtomicInteger()
        private val isSending = AtomicBoolean(true)

        override fun run() {
            val buffer = ByteArray(blockSize)
            try {
                while (isSending.get()) {
                    val count = this.inputStream.read(buffer)
                    if (count < 0) {
                        Log.d(Config.LOGTAG, "block sender reached EOF")
                        return
                    }
                    this.semaphore.acquire()
                    val block = ByteArray(count)
                    System.arraycopy(buffer, 0, block, 0, block.size)
                    sendIbbBlock(sequencer.getAndIncrement(), block)
                }
            } catch (e: InterruptedException) {
                if (isSending.get()) {
                    Log.w(Config.LOGTAG, "IbbBlockSender got interrupted while sending", e)
                }
            } catch (e: InterruptedIOException) {
                if (isSending.get()) {
                    Log.w(Config.LOGTAG, "IbbBlockSender got interrupted while sending", e)
                }
            } catch (e: IOException) {
                Log.d(Config.LOGTAG, "block sender terminated", e)
            } finally {
                Closeables.closeQuietly(inputStream)
            }
        }

        private fun sendIbbBlock(sequence: Int, block: ByteArray) {
            Log.d(Config.LOGTAG, "sending ibb block #" + sequence + " " + block.size + " bytes")
            val iqPacket = Iq(Iq.Type.SET)
            iqPacket.setTo(with)
            val data = iqPacket.addChild("data", Namespace.IBB)
            data.setAttribute("sid", this.streamId)
            data.setAttribute("seq", sequence)
            data.setContent(BaseEncoding.base64().encode(block))
            this.xmppConnection.sendIqPacket(iqPacket) { response ->
                if (response.getType() != Iq.Type.RESULT) {
                    Log.d(
                        Config.LOGTAG,
                        "received iq error in response to data block #" + sequence,
                    )
                    isSending.set(false)
                }
                semaphore.release()
            }
        }

        override fun close() {
            this.isSending.set(false)
        }

        fun setBlockSize(blockSize: Int) {
            this.blockSize = blockSize
        }
    }

    enum class PacketType {
        OPEN,
        DATA,
        CLOSE,
    }

    private companion object {
        const val DEFAULT_BLOCK_SIZE = 8192
    }
}

private fun closeQuietly(outputStream: OutputStream) {
    try {
        outputStream.close()
    } catch (ignored: IOException) {
        // ignored
    }
}
