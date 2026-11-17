package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AiModelOption
import com.example.data.model.ShellType
import com.example.data.model.TerminalProfile
import com.example.data.model.UiState
import com.example.firebase.signOutUser
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MintEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.viewmodel.ProfilesViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    profilesState: UiState<List<TerminalProfile>>,
    activeProfile: TerminalProfile?,
    profilesViewModel: ProfilesViewModel,
    onRerunHistoryCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val actionMessage by profilesViewModel.actionMessage.collectAsStateWithLifecycle()
    val isBusy by profilesViewModel.isBusy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var editingProfile by remember { mutableStateOf<TerminalProfile?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var profilePendingDelete by remember { mutableStateOf<TerminalProfile?>(null) }

    LaunchedEffect(actionMessage) {
        actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            profilesViewModel.clearMessage()
        }
    }

    val currentProfiles = (profilesState as? UiState.Success)?.data.orEmpty()
    val userEmail = Firebase.auth.currentUser?.email ?: "Authenticated User"

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianBg,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingProfile = null
                    showCreateSheet = true
                },
                containerColor = CyanElectric,
                contentColor = ObsidianBg,
                modifier = Modifier.testTag("create_profile_fab"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Create new terminal profile")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ObsidianBg),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                AccountHeaderCard(
                    email = userEmail,
                    activeProfileName = activeProfile?.name ?: "None",
                    onSignOut = {
                        signOutUser(credentialManager = credentialManager, scope = scope)
                    },
                )
            }

            item {
                Text(
                    text = "Terminal Profiles (${currentProfiles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }

            when (profilesState) {
                is UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = CyanElectric)
                        }
                    }
                }
                is UiState.Error -> {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = profilesState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
                is UiState.Success -> {
                    items(currentProfiles, key = { it.id }) { profile ->
                        ProfileItemCard(
                            profile = profile,
                            isBusy = isBusy,
                            canDelete = currentProfiles.size > 1,
                            onLoad = {
                                profilesViewModel.loadProfile(profile.id, currentProfiles)
                            },
                            onEdit = {
                                editingProfile = profile
                                showCreateSheet = true
                            },
                            onDelete = { profilePendingDelete = profile },
                        )
                    }
                }
            }

            // Active Profile Command History Section
            if (activeProfile != null) {
                item {
                    CommandHistorySection(
                        profile = activeProfile,
                        onRerun = onRerunHistoryCommand,
                        onClear = { profilesViewModel.clearHistory(activeProfile.id) },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    if (showCreateSheet) {
        ProfileEditorSheet(
            existing = editingProfile,
            onDismiss = { showCreateSheet = false },
            onSave = { name, shell, prompt, model, temp, proactive, autoSuggest, cpuThresh, memThresh ->
                val target = editingProfile
                if (target == null) {
                    profilesViewModel.createProfile(
                        name = name,
                        preferredShell = shell,
                        promptSymbol = prompt,
                        aiModelName = model,
                        aiTemperature = temp,
                        proactiveMonitoring = proactive,
                        autoSuggestEnabled = autoSuggest,
                        cpuAlertThreshold = cpuThresh,
                        memAlertThreshold = memThresh,
                        activateImmediately = true,
                        existingProfiles = currentProfiles,
                    )
                } else {
                    profilesViewModel.updateProfile(
                        profile = target,
                        name = name,
                        preferredShell = shell,
                        promptSymbol = prompt,
                        aiModelName = model,
                        aiTemperature = temp,
                        proactiveMonitoring = proactive,
                        autoSuggestEnabled = autoSuggest,
                        cpuAlertThreshold = cpuThresh,
                        memAlertThreshold = memThresh,
                    )
                }
                showCreateSheet = false
            },
        )
    }

    profilePendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { profilePendingDelete = null },
            containerColor = ObsidianSurface,
            title = { Text("Delete '${toDelete.name}'?", color = TextPrimary) },
            text = {
                Text(
                    "This permanently removes the profile, its AI settings, and its stored command history.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        profilesViewModel.deleteProfile(toDelete, currentProfiles.size)
                        profilePendingDelete = null
                    }
                ) {
                    Text("Delete", color = CrimsonError)
                }
            },
            dismissButton = {
                TextButton(onClick = { profilePendingDelete = null }) {
                    Text("Cancel", color = CyanElectric)
                }
            },
        )
    }
}

@Composable
private fun AccountHeaderCard(
    email: String,
    activeProfileName: String,
    onSignOut: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = CyanElectric.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = CyanElectric)
                    }
                }
                Column {
                    Text(
                        text = email,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Active profile: $activeProfileName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MintEmerald,
                    )
                }
            }
            OutlinedButton(
                onClick = onSignOut,
                border = BorderStroke(1.dp, ObsidianOutline),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("sign_out_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Logout,
                    contentDescription = "Sign out",
                    tint = CrimsonError,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign Out", color = CrimsonError)
            }
        }
    }
}

@Composable
private fun ProfileItemCard(
    profile: TerminalProfile,
    isBusy: Boolean,
    canDelete: Boolean,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val border = if (profile.activeProfile) BorderStroke(1.5.dp, MintEmerald) else BorderStroke(1.dp, ObsidianOutline)
    Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = border,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_card_${profile.name.lowercase().replace(" ", "_")}"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                        )
                        if (profile.activeProfile) {
                            Surface(
                                color = MintEmerald.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MintEmerald,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "ACTIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MintEmerald,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "${profile.preferredShell} • ${profile.aiModelName} (temp ${String.format(Locale.US, "%.1f", profile.aiTemperature)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanElectric,
                    )
                }
                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit profile", tint = CyanElectric)
                    }
                    if (canDelete) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete profile", tint = CrimsonError)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "CPU Alert: ${profile.cpuAlertThreshold}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "MEM Alert: ${profile.memAlertThreshold}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${profile.commandHistory.size} cmds",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!profile.activeProfile) {
                Button(
                    onClick = onLoad,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanElectric,
                        contentColor = ObsidianBg,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("load_profile_button_${profile.id}"),
                ) {
                    Text("Load Profile", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CommandHistorySection(
    profile: TerminalProfile,
    onRerun: (String) -> Unit,
    onClear: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = AmberAlert)
                    Text(
                        text = "Saved Command History (${profile.commandHistory.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                }
                if (profile.commandHistory.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Clear", color = CrimsonError)
                    }
                }
            }

            if (profile.commandHistory.isEmpty()) {
                Text(
                    text = "No commands executed in '${profile.name}' yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                profile.commandHistory.asReversed().take(12).forEach { cmd ->
                    Surface(
                        color = ObsidianSurfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRerun(cmd) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = cmd,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                ),
                                color = MintEmerald,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Rerun command",
                                tint = CyanElectric,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorSheet(
    existing: TerminalProfile?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        shell: String,
        prompt: String,
        model: String,
        temp: Double,
        proactive: Boolean,
        autoSuggest: Boolean,
        cpuThresh: Long,
        memThresh: Long,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var prompt by remember { mutableStateOf(existing?.promptSymbol ?: "alpaca:~$") }
    var selectedShell by remember { mutableStateOf(existing?.preferredShell ?: ShellType.ASH.path) }
    var selectedModel by remember { mutableStateOf(existing?.aiModelName ?: AiModelOption.FLASH_38.modelId) }
    var temperature by remember { mutableFloatStateOf((existing?.aiTemperature ?: 0.4).toFloat()) }
    var proactiveMonitoring by remember { mutableStateOf(existing?.proactiveMonitoring ?: true) }
    var autoSuggest by remember { mutableStateOf(existing?.autoSuggestEnabled ?: true) }
    var cpuThreshold by remember { mutableFloatStateOf((existing?.cpuAlertThreshold ?: 75L).toFloat()) }
    var memThreshold by remember { mutableFloatStateOf((existing?.memAlertThreshold ?: 80L).toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (existing == null) "Create Terminal Profile" else "Edit '${existing.name}'",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                label = { Text("Profile Name") },
                placeholder = { Text("e.g., Kernel Ops") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanElectric,
                    unfocusedBorderColor = ObsidianOutline,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_name_input"),
            )

            OutlinedTextField(
                value = prompt,
                onValueChange = { if (it.length <= 24) prompt = it },
                label = { Text("Shell Prompt Symbol") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanElectric,
                    unfocusedBorderColor = ObsidianOutline,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Preferred Linux Shell", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShellType.entries.forEach { shell ->
                    FilterChip(
                        selected = selectedShell == shell.path,
                        onClick = { selectedShell = shell.path },
                        label = { Text(shell.path, style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MintEmerald.copy(alpha = 0.2f),
                            selectedLabelColor = MintEmerald,
                        ),
                    )
                }
            }

            Text("Custom AI Model", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AiModelOption.entries.forEach { option ->
                    val selected = selectedModel == option.modelId
                    Surface(
                        color = if (selected) CyanElectric.copy(alpha = 0.14f) else ObsidianSurfaceVariant,
                        border = BorderStroke(1.dp, if (selected) CyanElectric else ObsidianOutline),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedModel = option.modelId },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = option.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) CyanElectric else TextPrimary,
                            )
                            Text(
                                text = option.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Column {
                Text(
                    text = "AI Temperature: ${String.format(Locale.US, "%.1f", temperature)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = CyanElectric, activeTrackColor = CyanElectric),
                )
            }

            Column {
                Text(
                    text = "CPU Alert Threshold: ${cpuThreshold.toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
                Slider(
                    value = cpuThreshold,
                    onValueChange = { cpuThreshold = it },
                    valueRange = 10f..99f,
                    colors = SliderDefaults.colors(thumbColor = AmberAlert, activeTrackColor = AmberAlert),
                )
            }

            Column {
                Text(
                    text = "Memory Alert Threshold: ${memThreshold.toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
                Slider(
                    value = memThreshold,
                    onValueChange = { memThreshold = it },
                    valueRange = 10f..99f,
                    colors = SliderDefaults.colors(thumbColor = MintEmerald, activeTrackColor = MintEmerald),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Proactive AI Bottleneck Alerts", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Switch(
                    checked = proactiveMonitoring,
                    onCheckedChange = { proactiveMonitoring = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = MintEmerald),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Real-Time Inline Suggestions", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Switch(
                    checked = autoSuggest,
                    onCheckedChange = { autoSuggest = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = CyanElectric),
                )
            }

            Button(
                onClick = {
                    if (name.trim().isNotEmpty()) {
                        onSave(
                            name,
                            selectedShell,
                            prompt,
                            selectedModel,
                            temperature.toDouble(),
                            proactiveMonitoring,
                            autoSuggest,
                            cpuThreshold.toLong(),
                            memThreshold.toLong(),
                        )
                    }
                },
                enabled = name.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanElectric,
                    contentColor = ObsidianBg,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_profile_button"),
            ) {
                Text(if (existing == null) "Create & Load Profile" else "Save Changes", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
