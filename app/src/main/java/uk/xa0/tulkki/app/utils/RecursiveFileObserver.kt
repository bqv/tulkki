package uk.xa0.tulkki.app.utils

import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.ArrayList
import java.util.Stack
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import uk.xa0.tulkki.xmpp.Config

/**
 * Copyright (C) 2012 Bartek Przybylski
 * Copyright (C) 2015 ownCloud Inc.
 * Copyright (C) 2016 Daniel Gultsch
 *
 * <p>Watches a directory tree by holding one [FileObserver] per directory.
 *
 * <p><strong>Two shape facts, both forced by Kotlin and both behaviour-preserving.</strong>
 *
 * <p>`depth(File)` walked the parent chain by **reassigning its parameter** -
 * `while ((file = file.getParentFile()) != null)`. Kotlin parameters are `val`s, so the same walk is
 * written with a local cursor: one increment per parent, and the same count.
 *
 * <p>`observing(File)` compares `observer.path` against a candidate, and Java's enclosing class may
 * read a nested class's private field while Kotlin's may not. The inner class's `path` is therefore
 * declared as an ordinary `val filePath` - the class itself is `private inner`, so nothing outside
 * this file can see either the class or the property, and the comparison is unchanged.
 *
 * <p>The static `MASK` and `EVENT_EXECUTOR` live in the companion and are read from the inner class,
 * which Kotlin permits (a nested class is a member of the enclosing class). The executor is still
 * one single-threaded executor for the whole class, and every event is still handed to it before
 * anything touches the filesystem.
 */
abstract class RecursiveFileObserver protected constructor(private val path: String) {

    private val mObservers = ArrayList<SingleFileObserver>()

    private val shouldStop = AtomicBoolean(true)

    fun startWatching() {
        shouldStop.set(false)
        startWatchingInternal()
    }

    @Synchronized
    private fun startWatchingInternal() {
        val stack = Stack<String>()
        stack.push(path)

        while (!stack.isEmpty()) {
            if (shouldStop.get()) {
                Log.d(Config.LOGTAG, "file observer received command to stop")
                return
            }
            val directory = File(stack.pop())
            mObservers.add(SingleFileObserver(directory, MASK))
            val files = directory.listFiles()
            for (file in files ?: emptyArray<File>()) {
                if (shouldStop.get()) {
                    Log.d(Config.LOGTAG, "file observer received command to stop")
                    return
                }
                if (file.isDirectory && file.name[0] != '.') {
                    val currentPath = file.absolutePath
                    if (depth(file) <= 8 && !stack.contains(currentPath) && !observing(file)) {
                        stack.push(currentPath)
                    }
                }
            }
        }
        for (observer in mObservers) {
            observer.startWatching()
        }
    }

    private fun depth(file: File): Int {
        var depth = 0
        var parent = file.parentFile
        while (parent != null) {
            depth++
            parent = parent.parentFile
        }
        return depth
    }

    private fun observing(file: File): Boolean {
        for (observer in mObservers) {
            if (file == observer.filePath) {
                return true
            }
        }
        return false
    }

    fun stopWatching() {
        shouldStop.set(true)
        stopWatchingInternal()
    }

    @Synchronized
    private fun stopWatchingInternal() {
        for (observer in mObservers) {
            observer.stopWatching()
        }
        mObservers.clear()
    }

    abstract fun onEvent(event: Int, path: File)

    fun restartWatching() {
        stopWatching()
        startWatching()
    }

    private inner class SingleFileObserver(val filePath: File, mask: Int) :
            FileObserver(filePath.absolutePath, mask) {

        override fun onEvent(event: Int, filename: String?) {
            if (filename == null) {
                Log.d(
                        Config.LOGTAG,
                        "ignored file event with NULL filename (event=" + event + ")")
                return
            }
            EVENT_EXECUTOR.execute {
                val file = File(filePath, filename)
                if ((event and FileObserver.ALL_EVENTS) == FileObserver.CREATE) {
                    if (file.isDirectory) {
                        Log.d(
                                Config.LOGTAG,
                                "file observer observed new directory creation " + file)
                        if (!observing(file)) {
                            val observer = SingleFileObserver(file, MASK)
                            observer.startWatching()
                        }
                    }
                    return@execute
                }
                this@RecursiveFileObserver.onEvent(event, file)
            }
        }
    }

    companion object {

        private val EVENT_EXECUTOR: Executor = Executors.newSingleThreadExecutor()

        private val MASK =
                FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.CREATE
    }
}
