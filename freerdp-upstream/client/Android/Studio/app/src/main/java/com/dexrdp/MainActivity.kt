package com.dexrdp

import android.content.ClipData
import android.app.AlertDialog
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.dexrdp.data.ConnectionProfile
import com.dexrdp.data.ProfileStore
import com.dexrdp.rdp.SessionDiagnostics
import com.dexrdp.rdp.SessionDiagnostics.ProbeResult
import com.dexrdp.rdp.SessionLauncher
import com.dexrdp.ui.DexRdpTheme
import com.dexrdp.ui.LauncherScreen
import com.freerdp.freerdpcore.utils.Diagnostics
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var store: ProfileStore

    private var profiles by mutableStateOf<List<ConnectionProfile>>(emptyList())
    private var selectedId by mutableStateOf<String?>(null)
    private var keyboardPassthrough by mutableStateOf(false)
    private var versionLabel by mutableStateOf("")
    private var lastError by mutableStateOf("")
    private var lastErrorAt by mutableStateOf("")
    private var lastTarget by mutableStateOf("")
    private var probeResult by mutableStateOf("")
    private var logContent by mutableStateOf("")
    private var udpOffer by mutableStateOf("")
    private var udpEngine by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ProfileStore(this)
        SessionLauncher.applySessionDefaults(this)
        reloadProfiles()

        setContent {
            DexRdpTheme {
                LauncherScreen(
                    profiles = profiles,
                    selectedId = selectedId,
                    keyboardPassthroughEnabled = keyboardPassthrough,
                    versionLabel = versionLabel,
                    lastError = lastError,
                    lastErrorAt = lastErrorAt,
                    lastTarget = lastTarget,
                    probeResult = probeResult,
                    udpOffer = udpOffer,
                    udpEngine = udpEngine,
                    logContent = logContent,
                    onTestConnection = { runPortProbe() },
                    onViewLog = { logContent = SessionDiagnostics.readLogTail(this) },
                    onCopyLog = { copyLogToClipboard() },
                    onSelect = { selectedId = it },
                    onCreate = {
                        val profile = ConnectionProfile(name = "New connection")
                        persist(listOf(profile) + profiles)
                        selectedId = profile.id
                    },
                    onUpdate = { updated ->
                        persist(profiles.map { if (it.id == updated.id) updated else it })
                    },
                    onDelete = { id ->
                        persist(profiles.filterNot { it.id == id })
                        selectedId = profiles.firstOrNull { it.id != id }?.id
                    },
                    onConnect = { profile -> connectWithKeyboardCheck(profile) },
                    onConnectEngine = { profile -> launchEngine(profile) },
                    onOpenKeyboardSettings = {
                        SessionLauncher.openKeyboardPassthroughSettings(this)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        keyboardPassthrough = SessionLauncher.isKeyboardPassthroughEnabled(this)
        versionLabel = readVersionLabel()
        lastError = Diagnostics.getLastError(this)
        lastErrorAt = Diagnostics.getLastErrorAt(this)
        lastTarget = Diagnostics.getLastTarget(this)
        udpOffer = SessionDiagnostics.findMultitransportOffer(this)
        udpEngine = com.freerdp.freerdpcore.services.LibFreeRDP.getUdpEngineVersion()
    }

    private fun launchEngine(profile: ConnectionProfile) {
        SessionDiagnostics.appendLine(this, "engine: button tapped (${profile.host}:${profile.port})")
        val (displayW, displayH) = SessionLauncher.displaySize(this)
        val (w, h) = profile.resolution.sizeOr(displayW, displayH)
        SessionDiagnostics.appendLine(this, "engine: starting RdpSessionActivity ${w}x${h}")
        val intent = android.content.Intent(this, com.dexrdp.engine.RdpSessionActivity::class.java).apply {
            putExtra("host", profile.host)
            putExtra("port", profile.port)
            putExtra("user", profile.username)
            putExtra("pass", profile.password)
            putExtra("domain", profile.domain)
            putExtra("width", w)
            putExtra("height", h)
        }
        startActivity(intent)
        SessionDiagnostics.appendLine(this, "engine: startActivity returned")
    }

    private fun connectWithKeyboardCheck(profile: ConnectionProfile) {
        if (keyboardPassthrough) {
            doConnect(profile)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Super key passthrough is off")
            .setMessage(
                "Right now DeX captures the Windows/Super key, so Win+E, Win+R and " +
                    "similar shortcuts never reach the remote PC.\n\n" +
                    "Enable \"DeX RDP\" under Accessibility to forward them."
            )
            .setPositiveButton("Open settings") { _, _ ->
                SessionLauncher.openKeyboardPassthroughSettings(this)
            }
            .setNegativeButton("Connect anyway") { _, _ -> doConnect(profile) }
            .show()
    }

    private fun doConnect(profile: ConnectionProfile) {
        Diagnostics.clear(this)
        lastError = ""
        lastErrorAt = ""
        lastTarget = ""

        if (SessionLauncher.launch(this, profile)) {
            Toast.makeText(this, "Connecting to ${profile.endpoint}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Enter a host address first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runPortProbe() {
        val profile = profiles.firstOrNull { it.id == selectedId }
        if (profile == null || profile.host.isBlank()) {
            probeResult = "Enter a host address first."
            return
        }

        probeResult = "Testing ${profile.endpoint} ..."
        lifecycleScope.launch {
            val result = SessionDiagnostics.probeTcp(profile.host, profile.port)
            probeResult = when (result) {
                is ProbeResult.Success ->
                    "Reachable: ${profile.endpoint} accepted the connection in ${result.latencyMs} ms."

                is ProbeResult.Refused ->
                    "Refused: nothing is listening on ${profile.endpoint}. Check the address and that " +
                        "Remote Desktop is enabled."

                is ProbeResult.Failed ->
                    "Unreachable: ${result.detail}"
            }
        }
    }

    private fun copyLogToClipboard() {
        val text = SessionDiagnostics.readLogTail(this, maxLines = 400)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DeX RDP log", text))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun readVersionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val name = info.versionName ?: "?"
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "v$name ($code)"
    } catch (e: Exception) {
        "unknown"
    }

    private fun reloadProfiles() {
        profiles = store.load()
        if (selectedId == null) {
            selectedId = profiles.firstOrNull()?.id
        }
    }

    private fun persist(updated: List<ConnectionProfile>) {
        profiles = updated
        store.save(updated)
    }
}