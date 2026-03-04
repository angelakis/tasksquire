package com.taskwarrior.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.taskwarrior.android.sync.SyncLogEntry
import com.taskwarrior.android.sync.SyncServerConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: TaskViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val existingConfig by viewModel.syncConfig.collectAsState()
    val autoSyncEnabled by viewModel.autoSyncEnabled.collectAsState()
    val syncLogs by viewModel.syncLogs.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.let { stream ->
                viewModel.importTasksFromJson(stream)
            }
        }
    }

    var serverUrl by remember(existingConfig) {
        mutableStateOf(existingConfig?.serverUrl ?: "")
    }
    var clientId by remember(existingConfig) {
        mutableStateOf(existingConfig?.clientId ?: UUID.randomUUID().toString())
    }
    var encryptionSecret by remember(existingConfig) {
        mutableStateOf(existingConfig?.encryptionSecret ?: "")
    }
    var showSecret by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    LaunchedEffect(Unit) {
        viewModel.refreshSyncLogs()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Sync Server",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Configure a TaskChampion-compatible sync server. " +
                    "Requests identify this client using X-Client-Id and payloads are " +
                    "encrypted with your Encryption Secret.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://sync.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                placeholder = { Text("UUID (generated automatically)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = encryptionSecret,
                onValueChange = { encryptionSecret = it },
                label = { Text("Encryption Secret / Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showSecret) "Hide" else "Show"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { clientId = UUID.randomUUID().toString() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("New Client ID")
                }
                Button(
                    onClick = {
                        viewModel.saveSyncConfig(serverUrl, clientId, encryptionSecret)
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = serverUrl.isNotBlank() && clientId.isNotBlank()
                ) {
                    Text("Save")
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Autosync",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "When enabled, sync runs after local changes, when the app returns to foreground, and periodically in background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = { viewModel.setAutoSyncEnabled(it) }
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Import / Export",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Import tasks from a Taskwarrior JSON export file. " +
                    "Generate one with: task export > tasks.json",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = { filePickerLauncher.launch("application/json") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import from JSON")
            }

            HorizontalDivider()

            Text(
                text = "Sync Logs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Use this when troubleshooting sync failures. Most recent entries appear first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.refreshSyncLogs() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh logs")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Logs")
                }
                OutlinedButton(
                    onClick = { viewModel.clearSyncLogs() },
                    modifier = Modifier.weight(1f),
                    enabled = syncLogs.isNotEmpty()
                ) {
                    Text("Clear Logs")
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (syncLogs.isEmpty()) {
                        Text(
                            text = "No sync logs yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        syncLogs.asReversed().take(40).forEach { entry ->
                            SyncLogRow(entry = entry, timeFormat = timeFormat)
                            HorizontalDivider()
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = "Danger Zone",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )

            Text(
                text = "Clearing the database removes all cached tasks. " +
                    "It keeps your sync settings intact so you can re-sync from the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            Button(
                onClick = { viewModel.clearLocalDatabase() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Clear local database")
            }

            HorizontalDivider()

            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Taskwarrior Android is an open-source task manager that " +
                    "syncs with Taskwarrior via the TaskChampion sync protocol.\n\n" +
                    "Compatible with:\n" +
                    "• taskchampion-sync-server\n" +
                    "• Taskwarrior 3.x (taskchampion backend)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SyncLogRow(entry: SyncLogEntry, timeFormat: SimpleDateFormat) {
    val levelColor = when (entry.level) {
        com.taskwarrior.android.sync.SyncLogLevel.ERROR -> MaterialTheme.colorScheme.error
        com.taskwarrior.android.sync.SyncLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        com.taskwarrior.android.sync.SyncLogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${timeFormat.format(Date(entry.timestamp))}  ${entry.level}",
            style = MaterialTheme.typography.labelSmall,
            color = levelColor
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
