package com.dexrdp

import android.util.Log
import com.freerdp.freerdpcore.application.GlobalApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DexRdpApp : GlobalApp() {

    override fun onCreate() {
        installCrashHandler()
        startEngineLogging()
        super.onCreate()
    }

    /**
     * The engine fires this the moment a connection attempt fails. Recording the
     * reason here is the reliable path: the UI otherwise only ever shows a generic
     * "could not connect" message.
     */
    override fun OnConnectionFailure(instance: Long) {
        val reason = runCatching {
            com.freerdp.freerdpcore.services.LibFreeRDP.lastErrorString(instance)
        }.getOrNull().orEmpty()

        Log.e(LOG_TAG, "Connection failure: $reason")
        runCatching {
            com.dexrdp.rdp.SessionDiagnostics.appendLine(this, "Connection FAILED: $reason")
            com.freerdp.freerdpcore.utils.Diagnostics.recordFailureReason(this, reason)
        }
        super.OnConnectionFailure(instance)
    }

    override fun OnConnectionSuccess(instance: Long) {
        Log.i(LOG_TAG, "Connection succeeded")
        runCatching {
            com.dexrdp.rdp.SessionDiagnostics.appendLine(this, "Connection SUCCEEDED (instance=$instance)")
            com.freerdp.freerdpcore.utils.Diagnostics.clear(this)
        }
        super.OnConnectionSuccess(instance)
    }

    private fun startEngineLogging() {
        val directory = File(filesDir, "logs")
        directory.mkdirs()
        val logFile = File(directory, "freerdp.log")

        runCatching {
            if (logFile.exists() && logFile.length() > 512 * 1024) {
                logFile.delete()
            }
            val enabled = com.freerdp.freerdpcore.services.LibFreeRDP.startFileLogging(logFile.absolutePath)
            com.dexrdp.rdp.SessionDiagnostics.appendLine(
                this,
                "App start; native file logging enabled=$enabled; path=${logFile.absolutePath}"
            )
        }.onFailure {
            Log.w(LOG_TAG, "Could not enable engine file logging", it)
            runCatching {
                com.dexrdp.rdp.SessionDiagnostics.appendLine(
                    this,
                    "App start; native file logging FAILED: ${it}"
                )
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashReport(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            append("DeX RDP crash report\n")
            append("Time: ").append(timestamp).append('\n')
            append("Thread: ").append(thread.name).append('\n')
            append("Android: ").append(android.os.Build.VERSION.RELEASE)
            append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("Device: ").append(android.os.Build.MANUFACTURER)
            append(' ').append(android.os.Build.MODEL).append("\n\n")
            append(stackTrace)
        }

        Log.e(LOG_TAG, report)

        val directory = getExternalFilesDir("logs")
        val target = directory ?: File(filesDir, "logs")
        target.mkdirs()
        File(target, "crash.log").writeText(report)
    }

    companion object {
        const val LOG_TAG = "DexRdpCrash"
    }
}