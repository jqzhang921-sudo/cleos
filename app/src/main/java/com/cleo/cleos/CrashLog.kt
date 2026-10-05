package com.cleo.cleos

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The last crash, kept on the phone to be read and sent on. Most people with the app can't plug
 * their phone into a computer, and "Cleos closes itself as it opens" (said of it with QQ 音乐 in the
 * background) doesn't say where. Installed first thing: it writes down what failed, in which
 * version on which phone, then lets the crash go on as it would have.
 *
 * Something that failed out of sight with the app going on anyway ([note]) is kept too, in its own
 * file: the background jobs that are let go of are worth knowing about, and a line that said the app
 * closed when it didn't would be worse than none.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    /** Where a failure the app lived through goes; see [note]. */
    private const val NOTE_FILE = "last-error.txt"

    /** More than any stack needs; a cause nested many times over is cut. */
    private const val MAX = 12_000

    /** Notes are kept newest-last; past this many characters the oldest of them go. */
    private const val NOTES_MAX = 40_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(describe(app, thread, error, "闪退")) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A background job failed with no one to catch it — a wake whose TA was deleted mid-reply, most
     * of all. Written down so it isn't silent, and the job let go of: the app goes on.
     */
    fun note(context: Context, error: Throwable) {
        val app = context.applicationContext
        runCatching {
            val file = File(app.filesDir, NOTE_FILE)
            val before = file.takeIf { it.exists() }?.readText().orEmpty()
            file.writeText((before + describe(app, Thread.currentThread(), error, "后台出错")).takeLast(NOTES_MAX))
        }
    }

    /** What was written at the last crash; null when there hasn't been one since it was cleared. */
    fun read(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    /** What failed out of sight since it was cleared; null when nothing has. */
    fun readNote(context: Context): String? = runCatching { File(context.filesDir, NOTE_FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    fun clearNote(context: Context) {
        runCatching { File(context.filesDir, NOTE_FILE).delete() }
    }

    /** [what] is what the first line calls it: 闪退 for a crash, 后台出错 for one the app lived through. */
    private fun describe(context: Context, thread: Thread, error: Throwable, what: String): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Cleos $version，${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} $what")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}），线程 ${thread.name}")
            append(stack.take(MAX))
        }
    }
}
