package com.dexrdp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DiagnosticsSection(
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
    modifier: Modifier = Modifier
) {
    var showLog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "DIAGNOSTICS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onTestConnection) { Text("Test port") }
            OutlinedButton(onClick = {
                onViewLog()
                showLog = true
            }) { Text("View log") }
            OutlinedButton(onClick = onCopyLog) { Text("Copy log") }
        }

        if (probeResult.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = probeResult, style = MaterialTheme.typography.bodySmall)
        }

        if (lastError.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Last failure" + if (lastTarget.isBlank()) "" else " ($lastTarget)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = lastError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
            if (lastErrorAt.isNotBlank()) {
                Text(
                    text = lastErrorAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "UDP engine: " + if (udpEngine.isBlank()) "unknown" else udpEngine,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Server UDP offer: " + if (udpOffer.isBlank()) {
                "NOT seen - this Windows host is not offering RDP-UDP"
            } else {
                udpOffer
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "\"Test port\" checks the PC is reachable on this port. A failure there is a " +
                "network or firewall problem rather than an RDP problem.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Connection log") },
            text = {
                Text(
                    text = logContent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showLog = false }) { Text("Close") }
            }
        )
    }
}