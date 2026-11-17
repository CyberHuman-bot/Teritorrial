package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AiSuggestion
import com.example.data.model.BottleneckAnalysis
import com.example.data.model.EntryType
import com.example.data.model.SystemMetricsSnapshot
import com.example.data.model.TerminalEntry
import com.example.data.model.TerminalProfile
import com.example.data.model.UiState
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MintEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceHigh
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.TerminalOutputStyle
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.VioletAccent
import com.example.viewmodel.TerminalMonitorViewModel
import com.example.voice.VoiceRecognizerController
import com.example.voice.VoiceState

@Composable
fun TerminalScreen(
    viewModel: TerminalMonitorViewModel,
    onNavigateToMonitor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val voiceController = remember(context) { VoiceRecognizerController(context) }
    DisposableEffect(voiceController) {
        onDispose { voiceController.destroy() }
    }

    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val metrics by viewModel.metricsState.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val currentDir by viewModel.currentDirDisplay.collectAsStateWithLifecycle()
    val suggestionsState by viewModel.suggestionsState.collectAsStateWithLifecycle()
    val proactiveAlert by viewModel.proactiveAlert.collectAsStateWithLifecycle()
    val voiceAiTranslate by viewModel.voiceAiTranslate.collectAsStateWithLifecycle()
    val isTranslatingVoice by viewModel.isTranslatingVoice.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val voiceState by voiceController.voiceState.collectAsStateWithLifecycle()

    LaunchedEffect(voiceState) {
        if (voiceState is VoiceState.Result) {
            val transcript = (voiceState as VoiceState.Result).transcript
            voiceController.resetState()
            viewModel.handleVoiceTranscript(transcript)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceController.startListening()
        } else {
            viewModel.onInputChanged(inputText)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg),
    ) {
        TerminalHeaderBar(
            activeProfile = activeProfile,
            currentDir = currentDir,
            metrics = metrics,
            onClearTerminal = { viewModel.executeCommand("clear") },
            onOpenMonitor = onNavigateToMonitor,
        )

        AnimatedVisibility(visible = proactiveAlert != null) {
            proactiveAlert?.let { alert ->
                ProactiveAlertBanner(
                    alert = alert,
                    onRunFirstFix = { cmd ->
                        viewModel.dismissProactiveAlert()
                        viewModel.executeCommand(cmd)
                    },
                    onInspect = {
                        viewModel.dismissProactiveAlert()
                        onNavigateToMonitor()
                    },
                    onDismiss = { viewModel.dismissProactiveAlert() },
                )
            }
        }

        AnimatedVisibility(visible = statusBanner != null || voiceState is VoiceState.Error) {
            val bannerMsg = (voiceState as? VoiceState.Error)?.message ?: statusBanner.orEmpty()
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = bannerMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            voiceController.resetState()
                            viewModel.clearStatusBanner()
                        },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss message",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        // Scrollable Terminal Output Buffer
        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("terminal_output_list"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    TerminalEntryRow(entry = entry)
                }
            }
        }

        // Live Voice Listening Indicator
        AnimatedVisibility(visible = voiceState is VoiceState.Listening || isTranslatingVoice) {
            VoiceStatusStrip(
                voiceState = voiceState,
                isTranslating = isTranslatingVoice,
                onStop = { voiceController.stopListening() },
            )
        }

        // Real-Time AI Suggestions Bar
        AiSuggestionsStrip(
            suggestionsState = suggestionsState,
            onRefresh = { viewModel.requestManualSuggestions() },
            onSelectSuggestion = { suggestion, runNow ->
                viewModel.applySuggestion(suggestion.command, runImmediately = runNow)
            },
        )

        // Quick Linux Builtin Chips + History Arrows
        QuickActionRow(
            voiceAiTranslate = voiceAiTranslate,
            onToggleVoiceAi = { viewModel.toggleVoiceAiTranslate() },
            onHistoryUp = { viewModel.cycleHistory(up = true) },
            onHistoryDown = { viewModel.cycleHistory(up = false) },
            onQuickCommand = { cmd -> viewModel.executeCommand(cmd) },
        )

        // Command Input Bar
        TerminalCommandBar(
            inputText = inputText,
            promptSymbol = activeProfile?.promptSymbol ?: "alpaca:~$",
            isListening = voiceState is VoiceState.Listening,
            onInputChanged = { viewModel.onInputChanged(it) },
            onExecute = { viewModel.executeCommand() },
            onMicClick = {
                if (voiceState is VoiceState.Listening) {
                    voiceController.stopListening()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        voiceController.startListening()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            },
        )
    }
}

@Composable
private fun TerminalHeaderBar(
    activeProfile: TerminalProfile?,
    currentDir: String,
    metrics: SystemMetricsSnapshot,
    onClearTerminal: () -> Unit,
    onOpenMonitor: () -> Unit,
) {
    Surface(
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    color = MintEmerald.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = MintEmerald,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column {
                    Text(
                        text = activeProfile?.name ?: "Alpaca Core",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${activeProfile?.preferredShell ?: "/bin/ash"} • $currentDir",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanElectric,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    color = ObsidianSurfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ObsidianOutline),
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clickable { onOpenMonitor() }
                        .testTag("terminal_telemetry_pill"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "CPU ${metrics.cpuPercent.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (metrics.cpuPercent >= (activeProfile?.cpuAlertThreshold ?: 75L)) AmberAlert else CyanElectric,
                        )
                        Text(
                            text = "MEM ${metrics.memPercent.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (metrics.memPercent >= (activeProfile?.memAlertThreshold ?: 80L)) AmberAlert else MintEmerald,
                        )
                    }
                }

                IconButton(
                    onClick = onClearTerminal,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("clear_terminal_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = "Clear terminal output",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProactiveAlertBanner(
    alert: BottleneckAnalysis,
    onRunFirstFix: (String) -> Unit,
    onInspect: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = AmberContainer,
        border = BorderStroke(1.dp, AmberAlert.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = AmberAlert,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "AI Telemetry Alert: ${alert.bottleneckArea}",
                        style = MaterialTheme.typography.titleSmall,
                        color = AmberAlert,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Dismiss alert",
                        tint = AmberAlert,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = alert.summary,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                alert.actions.firstOrNull()?.let { firstAction ->
                    TextButton(onClick = { onRunFirstFix(firstAction.command) }) {
                        Text("Run: ${firstAction.command}", color = CyanElectric)
                    }
                }
                TextButton(onClick = onInspect) {
                    Text("View Monitor", color = AmberAlert)
                }
            }
        }
    }
}

@Composable
private fun TerminalEntryRow(entry: TerminalEntry) {
    when (entry.type) {
        EntryType.COMMAND -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = entry.prompt,
                    style = TerminalOutputStyle.copy(fontWeight = FontWeight.Bold),
                    color = MintEmerald,
                )
                Text(
                    text = entry.text,
                    style = TerminalOutputStyle.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                )
            }
        }
        EntryType.OUTPUT -> {
            Text(
                text = entry.text,
                style = TerminalOutputStyle,
                color = Color(0xFFCFD9E3),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EntryType.ERROR -> {
            Surface(
                color = CrimsonError.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = entry.text,
                    style = TerminalOutputStyle,
                    color = CrimsonError,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        EntryType.SYSTEM -> {
            Surface(
                color = CyanElectric.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, CyanElectric.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = entry.text,
                    style = TerminalOutputStyle,
                    color = CyanElectric,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        EntryType.AI_SUGGESTION -> {
            Surface(
                color = VioletAccent.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = entry.text,
                    style = TerminalOutputStyle,
                    color = Color(0xFFD6BCFA),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceStatusStrip(
    voiceState: VoiceState,
    isTranslating: Boolean,
    onStop: () -> Unit,
) {
    val listening = voiceState as? VoiceState.Listening
    Surface(
        color = CyanElectric.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, CyanElectric.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (isTranslating) {
                        "Gemini AI translating voice to shell command..."
                    } else {
                        val preview = listening?.partialText.orEmpty()
                        if (preview.isBlank()) "Listening... speak a command or intent" else "\"$preview\""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanElectric,
                    modifier = Modifier.weight(1f),
                )
                if (listening != null) {
                    TextButton(onClick = onStop) {
                        Text("Stop", color = CrimsonError)
                    }
                }
            }
            if (isTranslating) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    color = CyanElectric,
                )
            } else if (listening != null) {
                LinearProgressIndicator(
                    progress = { (listening.rmsDb / 10f).coerceIn(0.1f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    color = MintEmerald,
                )
            }
        }
    }
}

@Composable
private fun AiSuggestionsStrip(
    suggestionsState: UiState<List<AiSuggestion>>,
    onRefresh: () -> Unit,
    onSelectSuggestion: (AiSuggestion, Boolean) -> Unit,
) {
    Surface(
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = CyanElectric,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "AI Real-Time Suggestions",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanElectric,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (suggestionsState is UiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = CyanElectric,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "Suggest",
                        style = MaterialTheme.typography.labelMedium,
                        color = MintEmerald,
                        modifier = Modifier
                            .clickable { onRefresh() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("ai_suggest_refresh_button"),
                    )
                }
            }

            when (suggestionsState) {
                is UiState.Success -> {
                    val list = suggestionsState.data
                    if (list.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            list.forEachIndexed { index, suggestion ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceVariant),
                                    border = BorderStroke(1.dp, ObsidianOutline),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .clickable { onSelectSuggestion(suggestion, false) }
                                        .testTag("ai_suggestion_card_$index"),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column {
                                            Text(
                                                text = suggestion.command,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MintEmerald,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = suggestion.title,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                        Surface(
                                            color = CyanElectric.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable {
                                                onSelectSuggestion(suggestion, true)
                                            },
                                        ) {
                                            Text(
                                                text = "RUN",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = CyanElectric,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is UiState.Error -> {
                    Text(
                        text = suggestionsState.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = CrimsonError,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is UiState.Loading -> Unit
            }
        }
    }
}

@Composable
private fun QuickActionRow(
    voiceAiTranslate: Boolean,
    onToggleVoiceAi: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onQuickCommand: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = voiceAiTranslate,
            onClick = onToggleVoiceAi,
            label = { Text("Voice→AI Shell", style = MaterialTheme.typography.labelSmall) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = VioletAccent.copy(alpha = 0.25f),
                selectedLabelColor = Color(0xFFD6BCFA),
            ),
            modifier = Modifier.testTag("voice_ai_toggle_chip"),
        )
        IconButton(
            onClick = onHistoryUp,
            modifier = Modifier
                .size(40.dp)
                .testTag("history_up_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = "Previous command",
                tint = TextPrimary,
            )
        }
        IconButton(
            onClick = onHistoryDown,
            modifier = Modifier
                .size(40.dp)
                .testTag("history_down_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Next command",
                tint = TextPrimary,
            )
        }
        listOf("ls -la", "free -h", "df -h", "apk info", "uname -a", "sh ~/sysinfo.sh").forEach { cmd ->
            AssistChip(
                onClick = { onQuickCommand(cmd) },
                label = {
                    Text(
                        text = cmd,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = ObsidianSurfaceVariant,
                    labelColor = TextPrimary,
                ),
                border = BorderStroke(1.dp, ObsidianOutline),
            )
        }
    }
}

@Composable
private fun TerminalCommandBar(
    inputText: String,
    promptSymbol: String,
    isListening: Boolean,
    onInputChanged: (String) -> Unit,
    onExecute: () -> Unit,
    onMicClick: () -> Unit,
) {
    Surface(
        color = ObsidianSurfaceHigh,
        border = BorderStroke(1.dp, ObsidianOutline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = promptSymbol,
                style = MaterialTheme.typography.labelMedium,
                color = MintEmerald,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (inputText.isEmpty()) {
                    Text(
                        text = "Enter shell command or tap mic...",
                        style = TerminalOutputStyle,
                        color = TextMuted,
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChanged,
                    singleLine = true,
                    textStyle = TerminalOutputStyle.copy(color = TextPrimary),
                    cursorBrush = SolidColor(CyanElectric),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onExecute() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("terminal_command_input"),
                )
            }
            IconButton(
                onClick = onMicClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isListening) CrimsonError else ObsidianSurfaceVariant,
                    contentColor = if (isListening) ObsidianBg else CyanElectric,
                ),
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (isListening) CrimsonError else ObsidianSurfaceVariant,
                        shape = CircleShape,
                    )
                    .testTag("voice_mic_button"),
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = if (isListening) "Stop voice input" else "Voice command input",
                )
            }
            IconButton(
                onClick = onExecute,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MintEmerald,
                    contentColor = ObsidianBg,
                ),
                modifier = Modifier
                    .size(48.dp)
                    .background(color = MintEmerald, shape = CircleShape)
                    .testTag("execute_command_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Execute command",
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TerminalCommandBarPreview() {
    com.example.ui.theme.AlpacaShellTheme {
        TerminalCommandBar(
            inputText = "ls -la /home/alpaca",
            promptSymbol = "alpaca:~$",
            isListening = false,
            onInputChanged = {},
            onExecute = {},
            onMicClick = {},
        )
    }
}
