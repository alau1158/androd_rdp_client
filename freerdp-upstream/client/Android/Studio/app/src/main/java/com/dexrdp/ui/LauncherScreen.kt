package com.dexrdp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dexrdp.data.ConnectionProfile
import com.dexrdp.data.Resolution
import com.dexrdp.data.Security
import com.dexrdp.data.Transport

@Composable
fun LauncherScreen(
    profiles: List<ConnectionProfile>,
    selectedId: String?,
    keyboardPassthroughEnabled: Boolean,
    versionLabel: String,
    lastError: String,
    lastErrorAt: String,
    lastTarget: String,
    probeResult: String,
    udpOffer: String,
    udpEngine: String,
    logContent: String,
    onTestConnection: () -> Unit,
    onViewLog: () -> Unit,
    onCopyLog: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onUpdate: (ConnectionProfile) -> Unit,
    onDelete: (String) -> Unit,
    onConnect: (ConnectionProfile) -> Unit,
    onConnectEngine: (ConnectionProfile) -> Unit,
    onOpenKeyboardSettings: () -> Unit
) {
    val selected = profiles.firstOrNull { it.id == selectedId }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                keyboardPassthroughEnabled = keyboardPassthroughEnabled,
                versionLabel = versionLabel,
                onOpenKeyboardSettings = onOpenKeyboardSettings
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(modifier = Modifier.fillMaxSize()) {
                ConnectionList(
                    profiles = profiles,
                    selectedId = selectedId,
                    onSelect = onSelect,
                    onCreate = onCreate,
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                )
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                )
                if (selected == null) {
                    EmptyState(modifier = Modifier.weight(1f).fillMaxHeight())
                } else {
                    ProfileEditor(
                        profile = selected,
                        lastError = lastError,
                        lastErrorAt = lastErrorAt,
                        lastTarget = lastTarget,
                        probeResult = probeResult,
                        udpOffer = udpOffer, udpEngine = udpEngine,
                        logContent = logContent,
                        onTestConnection = onTestConnection,
                        onViewLog = onViewLog,
                        onCopyLog = onCopyLog,
                        onUpdate = onUpdate,
                        onDelete = { onDelete(selected.id) },
                        onConnect = { onConnect(selected) },
                        onConnectEngine = onConnectEngine,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    keyboardPassthroughEnabled: Boolean,
    versionLabel: String,
    onOpenKeyboardSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "DeX RDP",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Remote desktop for Samsung DeX",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        KeyboardPassthroughChip(
            enabled = keyboardPassthroughEnabled,
            onClick = onOpenKeyboardSettings
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = versionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun KeyboardPassthroughChip(enabled: Boolean, onClick: () -> Unit) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(20.dp))
            .border(1.dp, contentColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(contentColor, RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (enabled) {
                "Super key passthrough ON"
            } else {
                "Super key passthrough OFF - tap to enable"
            },
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ConnectionList(
    profiles: List<ConnectionProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CONNECTIONS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCreate) { Text("+ New") }
        }
        if (profiles.isEmpty()) {
            Text(
                text = "No saved connections yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(profiles, key = { it.id }) { profile ->
                ConnectionRow(
                    profile = profile,
                    selected = profile.id == selectedId,
                    onClick = { onSelect(profile.id) }
                )
            }
        }
    }
}

@Composable
private fun ConnectionRow(profile: ConnectionProfile, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.background
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
            Text(
                text = buildString {
                    if (profile.username.isNotBlank()) append(profile.username).append('@')
                    append(profile.endpoint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(if (profile.resolution == Resolution.AUTO) "Auto res" else profile.resolution.name)
                Badge(if (profile.transport == Transport.UDP) "UDP" else "TCP")
                if (profile.audio) Badge("Audio")
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "Select a connection, or create a new one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProfileEditor(
    profile: ConnectionProfile,
    lastError: String,
    lastErrorAt: String,
    lastTarget: String,
    probeResult: String,
    udpOffer: String,
    udpEngine: String,
    logContent: String,
    onTestConnection: () -> Unit,
    onViewLog: () -> Unit,
    onCopyLog: () -> Unit,
    onUpdate: (ConnectionProfile) -> Unit,
    onDelete: () -> Unit,
    onConnect: () -> Unit,
    onConnectEngine: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val canConnect = profile.host.isNotBlank()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = profile.displayName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(20.dp))

        Field("Connection name", profile.name) { onUpdate(profile.copy(name = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(2f)) {
                Field("Host or IP address", profile.host) { onUpdate(profile.copy(host = it)) }
            }
            Box(modifier = Modifier.weight(1f)) {
                Field(
                    "Port",
                    if (profile.port == 3389) "" else profile.port.toString(),
                    numeric = true
                ) {
                    val port = it.toIntOrNull()
                    onUpdate(profile.copy(port = if (port == null || port <= 0) 3389 else port))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                Field("Username", profile.username) { onUpdate(profile.copy(username = it)) }
            }
            Box(modifier = Modifier.weight(1f)) {
                Field("Domain (optional)", profile.domain) { onUpdate(profile.copy(domain = it)) }
            }
        }
        Field("Password", profile.password, password = true) {
            onUpdate(profile.copy(password = it))
        }

        if (profile.host.isNotBlank() && profile.username.isBlank()) {
            Text(
                text = "No username set. Windows NLA almost always refuses a connection " +
                    "without credentials, which appears as a generic connection failure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        SectionTitle("Display")
        SelectionRow(
            label = "Resolution",
            options = Resolution.entries.toList(),
            selected = profile.resolution,
            labelOf = { it.label() },
            onSelect = { onUpdate(profile.copy(resolution = it)) }
        )
        SelectionRow(
            label = "Color depth",
            options = listOf(32, 24),
            selected = profile.colorDepth,
            labelOf = { "$it bpp" },
            onSelect = { onUpdate(profile.copy(colorDepth = it)) }
        )

        Spacer(modifier = Modifier.height(20.dp))
        SectionTitle("Security")
        SelectionRow(
            label = "Security protocol",
            options = Security.entries.toList(),
            selected = profile.security,
            labelOf = { it.label() },
            onSelect = { onUpdate(profile.copy(security = it)) }
        )

        Spacer(modifier = Modifier.height(20.dp))
        SectionTitle("Transport")
        SelectionRow(
            label = "Network transport",
            options = Transport.entries.toList(),
            selected = profile.transport,
            labelOf = { it.label() },
            onSelect = { onUpdate(profile.copy(transport = it)) }
        )

        Spacer(modifier = Modifier.height(20.dp))
        SectionTitle("Features")
        ToggleRow("Remote audio", profile.audio) { onUpdate(profile.copy(audio = it)) }
        ToggleRow("Microphone", profile.microphone) { onUpdate(profile.copy(microphone = it)) }
        ToggleRow("Clipboard sharing", profile.clipboard) { onUpdate(profile.copy(clipboard = it)) }
        ToggleRow("H.264 / AVC444 graphics", profile.h264) { onUpdate(profile.copy(h264 = it)) }
        ToggleRow("RemoteFX", profile.remoteFx) { onUpdate(profile.copy(remoteFx = it)) }
        ToggleRow("Windows themes", profile.themes) { onUpdate(profile.copy(themes = it)) }

        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onConnect,
                enabled = canConnect,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.height(48.dp)
            ) {
                Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.height(48.dp)) {
                Text("Delete")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onConnectEngine(profile) },
            enabled = canConnect,
            modifier = Modifier.height(48.dp)
        ) {
            Text("Connect (UDP engine)")
        }

        Spacer(modifier = Modifier.height(20.dp))
        DiagnosticsSection(
            lastError = lastError,
            lastErrorAt = lastErrorAt,
            lastTarget = lastTarget,
            probeResult = probeResult,
            udpOffer = udpOffer, udpEngine = udpEngine,
            logContent = logContent,
            onTestConnection = onTestConnection,
            onViewLog = onViewLog,
            onCopyLog = onCopyLog
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Credentials are stored on this device only. In DeX, tap the Super key chip " +
                "on the start screen to enable full Windows key passthrough.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    password: Boolean = false,
    numeric: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (numeric) {
                androidx.compose.ui.text.input.KeyboardType.Number
            } else {
                androidx.compose.ui.text.input.KeyboardType.Text
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    )
}

@Composable
private fun <T> SelectionRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(190.dp)
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(labelOf(selected))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelOf(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}