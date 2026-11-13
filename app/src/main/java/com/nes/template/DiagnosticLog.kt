package com.nes.template

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticLog private constructor(private val directory: File) {
    private val current = File(directory, "events.log")
    private val previous = File(directory, "events.previous.log")
    private val session = UUID.randomUUID().toString()
    private var started = false

    @Synchronized
    fun record(event: String, error: Throwable? = null) {
        try {
            check(directory.isDirectory || directory.mkdirs())
            if (current.length() >= 512 * 1024) {
                check(!previous.exists() || previous.delete())
                check(current.renameTo(previous))
            }
            val now = System.currentTimeMillis()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(now))
            val detail = event.replace('\n', ' ').replace('\r', ' ')
            FileOutputStream(current, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("$time epoch=$now elapsed=${SystemClock.elapsedRealtime()} pid=${Process.myPid()} session=$session thread=${Thread.currentThread().name} $detail")
                if (error != null) error.printStackTrace(PrintWriter(writer))
            }
        } catch (error: Exception) {
            Log.e("DiagnosticLog", "Cannot write diagnostic event", error)
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        installExceptionHandler()
        record("start package=${context.packageName} version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} core=${BuildConfig.CORE_ID} abi=${Build.SUPPORTED_ABIS.joinToString()} firmware=${Build.FINGERPRINT}")
        Thread({
            collectExitHistory(context)
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                File(context.applicationInfo.sourceDir).inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                record("apk sha256=${digest.digest().joinToString("") { "%02x".format(it) }}")
            }.onFailure { record("apk fingerprint unavailable", it) }
        }, "diagnostics").start()
    }

    private fun installExceptionHandler() {
        val handler = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val file = File(directory, "java-${System.currentTimeMillis()}-$session.txt")
                file.printWriter().use { writer ->
                    writer.println("pid=${Process.myPid()} session=$session thread=${thread.name}")
                    error.printStackTrace(writer)
                }
            } catch (failure: Exception) {
                Log.e("DiagnosticLog", "Cannot write Java crash stack", failure)
            } finally {
                handler.uncaughtException(thread, error)
            }
        }
    }

    private fun collectExitHistory(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val manager = context.getSystemService(ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            val saved = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
            val last = saved.getLong("last_exit", 0)
            var newest = last
            for (exit in exits.sortedBy { it.timestamp }) {
                if (exit.timestamp <= last) continue
                record("exit time=${exit.timestamp} pid=${exit.pid} reason=${exit.reason} status=${exit.status} description=${exit.description}")
                when (exit.reason) {
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR -> archiveExit(exit)
                    else -> Unit
                }
                newest = maxOf(newest, exit.timestamp)
            }
            for ((prefix, extension) in listOf("crash-" to "txt", "crash-" to "trace", "native-" to "pb", "java-" to "txt")) {
                attachments().filter { it.name.startsWith(prefix) && it.extension == extension }
                    .sortedByDescending { it.name }.drop(5).forEach { it.delete() }
            }
            saved.edit { putLong("last_exit", newest) }
        } catch (error: Exception) {
            record("exit history unavailable: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun archiveExit(exit: ApplicationExitInfo) {
        val key = "${exit.timestamp}-${exit.pid}"
        val report = File(directory, "crash-$key.txt")
        val timeline = synchronized(this) {
            listOf(previous, current).filter { it.isFile }.flatMap { it.readLines() }
        }
        val event = Regex(" epoch=(\\d+) elapsed=\\d+ pid=${exit.pid} session=([^ ]+) ")
        val sessionId = timeline.asReversed().firstNotNullOfOrNull { line ->
            event.find(line)?.takeIf { it.groupValues[1].toLong() <= exit.timestamp }?.groupValues?.get(2)
        }
        val traceStatus = runCatching {
            val native = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            if (native && Build.VERSION.SDK_INT < 31) return@runCatching "unsupported"
            val trace = exit.traceInputStream ?: return@runCatching "unavailable"
            val name = if (native) "native-$key.pb" else "crash-$key.trace"
            trace.use { input ->
                val temporary = File(directory, "$name.tmp")
                try {
                    temporary.outputStream().use { input.copyTo(it) }
                    check(temporary.renameTo(File(directory, name)))
                } finally {
                    temporary.delete()
                }
            }
            name
        }.getOrElse { "read failed: ${it.javaClass.simpleName}" }
        val temporary = File(directory, "${report.name}.tmp")
        try {
            temporary.bufferedWriter().use { writer ->
                writer.appendLine(exit.toString())
                writer.appendLine("session=${sessionId ?: "unavailable"} systemTrace=$traceStatus")
                if (sessionId != null) {
                    var include = false
                    for (line in timeline) {
                        if (" epoch=" in line && " elapsed=" in line) {
                            val match = event.find(line)
                            include = match != null && match.groupValues[2] == sessionId &&
                                match.groupValues[1].toLong() <= exit.timestamp
                        }
                        if (include) writer.appendLine(line)
                    }
                }
            }
            check(temporary.renameTo(report))
        } finally {
            temporary.delete()
        }
    }

    private fun attachments(): List<File> = directory.listFiles { file ->
        file.isFile && (file.name.startsWith("crash-") || file.name.startsWith("native-") ||
            file.name.startsWith("java-")) && file.extension in setOf("txt", "trace", "pb")
    }?.toList().orEmpty()

    @Synchronized
    fun export(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            for (file in listOf(previous, current) + attachments()) {
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    companion object {
        private var instance: DiagnosticLog? = null

        @Synchronized
        fun get(context: Context): DiagnosticLog =
            instance ?: DiagnosticLog(File(context.filesDir, "diagnostics")).also { instance = it }
    }
}
