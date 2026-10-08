package uk.xa0.tulkki.ui.util

import android.os.Handler
import android.os.Looper

import java.util.concurrent.Executor

object MainThreadExecutor : Executor {

    // Java passed Looper.myLooper() straight into Handler, which NPEs on a null looper; the SDK
    // annotation makes that a Kotlin non-null parameter, so the NPE is thrown here instead.
    private val handler = Handler(Looper.myLooper() ?: throw NullPointerException())

    override fun execute(command: Runnable) {
        handler.post(command)
    }

    @JvmStatic
    fun getInstance(): MainThreadExecutor = this
}
