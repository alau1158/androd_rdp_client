package com.dexrdp.rdp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object SessionDiagnostics {

    sealed class ProbeResult {
        data class Success(val latencyMs: Long) : ProbeResult()
        data class Refused(val detail: String) : ProbeResult()
        data class Failed(val detail: String) : ProbeResult()
    }

    fun logFile(context: android.content.Context): File =
        File(File(context.filesDir, "logs"), "freerdp.log")

    /** Appends a line written from Java, so the log is never blank even if the
     *  native logger fails to initialise. */
    fun appendLine(context: android.content.Context, line: String) {
        runCatching {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("$stamp [app] $line\n")
        }
    }

    fun readLogTail(context: android.content.Context, maxLines: Int = 120): String {
        val file = logFile(context)
        if (!file.exists()) return "No log file yet."
        val lines = runCatching { file.readLines() }.getOrElse { return "Could not read log: ${it.message}" }
        if (lines.isEmpty()) return "Log file is empty."
        val tail = if (lines.size > maxLines) lines.subList(lines.size - maxLines, lines.size) else lines
        return tail.joinToString("\n")
    }

    /**
     * Reports whether the remote Windows host offered the RDP multitransport (UDP)
     * channel. RDP-UDP is server-initiated, so without this offer there is nothing
     * for the client to connect to.
     */
    fun findMultitransportOffer(context: android.content.Context): String {
        val file = logFile(context)
        if (!file.exists()) return ""

        val lines = runCatching { file.readLines() }.getOrElse { return "" }
        val offer = lines.lastOrNull { it.contains("server OFFERED multitransport") } ?: return ""

        val protocol = offer.substringAfter("protocol=", "").substringBefore(" ").trim()
        return when {
            protocol.contains("0x0001", ignoreCase = true) -> "yes - reliable UDP (0x0001)"
            protocol.contains("0x0002", ignoreCase = true) -> "yes - lossy UDP (0x0002)"
            protocol.isNotEmpty() -> "yes ($protocol)"
            else -> "yes"
        }
    }

    suspend fun probeTcp(host: String, port: Int, timeoutMs: Int = 4000): ProbeResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                ProbeResult.Success(System.currentTimeMillis() - started)
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                if (e is java.net.ConnectException || message.contains("refused", true)) {
                    ProbeResult.Refused(message)
                } else {
                    ProbeResult.Failed(message)
                }
            } finally {
                runCatching { socket?.close() }
            }
        }
}