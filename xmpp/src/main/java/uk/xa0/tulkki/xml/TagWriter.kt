package uk.xa0.tulkki.xml

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.models.StreamElement

/**
 * Tulkki: the stanza writer, with its own single writer thread.
 *
 * Ported from `TagWriter.java`. The Java-visible
 * surface is kept member for member: the constructor, the `synchronized` methods (`@Synchronized`),
 * the checked `IOException`/`InterruptedException` clauses (`@Throws`, which javac's surface reads)
 * and the two `writeTag` overloads.
 *
 * Two behaviours are reproduced rather than tidied. `setOutputStream`/`beginDocument`/`writeTag`/
 * `writeElement` still reject a null stream with `IOException`, so the parameters that Java checked
 * stay nullable. And the writer thread still assigns the latch, drains the queue and answers a
 * missing stream the way the Java did: the old `NullPointerException` inside the `try` is what the
 * `catch (Exception)` turned into a `break`, so the Kotlin takes the same path explicitly instead
 * of a `!!` on a field the worker thread may not have set yet.
 */
class TagWriter {

    private var outputStream: OutputStreamWriter? = null
    private var finished = false

    private val writeQueue = LinkedBlockingQueue<StreamElement>()
    private var stanzaWriterCountDownLatch: CountDownLatch? = null

    private val asyncStanzaWriter: Thread = object : Thread() {

        override fun run() {
            val latch = CountDownLatch(1)
            stanzaWriterCountDownLatch = latch
            while (!isInterrupted) {
                if (finished && writeQueue.isEmpty()) {
                    break
                }
                try {
                    val output = writeQueue.take()
                    val stream = outputStream ?: throw NullPointerException()
                    stream.write(output.toString())
                    if (writeQueue.isEmpty()) {
                        stream.flush()
                    }
                } catch (e: Exception) {
                    break
                }
            }
            latch.countDown()
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun setOutputStream(out: OutputStream?) {
        if (out == null) {
            throw IOException()
        }
        this.outputStream = OutputStreamWriter(out)
    }

    @Throws(IOException::class)
    fun beginDocument() {
        val stream = outputStream ?: throw IOException("output stream was null")
        stream.write("<?xml version='1.0'?>")
    }

    @Throws(IOException::class)
    fun writeTag(tag: Tag) {
        writeTag(tag, true)
    }

    @Synchronized
    @Throws(IOException::class)
    fun writeTag(tag: Tag, flush: Boolean) {
        val stream = outputStream ?: throw IOException("output stream was null")
        stream.write(tag.toString())
        if (flush) {
            stream.flush()
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun writeElement(element: StreamElement) {
        val stream = outputStream ?: throw IOException("output stream was null")
        stream.write(element.toString())
        stream.flush()
    }

    fun writeStanzaAsync(stanza: StreamElement) {
        if (finished) {
            Log.d(Config.LOGTAG, "attempting to write stanza to finished TagWriter")
        } else {
            if (!asyncStanzaWriter.isAlive) {
                try {
                    asyncStanzaWriter.start()
                } catch (e: IllegalThreadStateException) {
                    // already started
                }
            }
            writeQueue.add(stanza)
        }
    }

    fun finish() {
        this.finished = true
    }

    @Throws(InterruptedException::class)
    fun await(timeout: Long, timeunit: TimeUnit): Boolean {
        val latch = stanzaWriterCountDownLatch ?: return true
        return latch.await(timeout, timeunit)
    }

    fun isActive(): Boolean = outputStream != null

    @Synchronized
    fun forceClose() {
        asyncStanzaWriter.interrupt()
        val stream = outputStream
        if (stream != null) {
            try {
                stream.close()
            } catch (e: IOException) {
                // ignoring
            }
        }
        outputStream = null
    }
}
