package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
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
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.TerminalOutputStyle
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.VioletAccent
import com.example.viewmodel.TerminalMonitorViewModel
import com.example.voice.VoiceEngineMode
import com.example.voice.VoiceRecognizerController
import com.example.voice.VoiceState
import kotlin.math.sin

private val TermuxBlack = Color(0xFF000000)
private val TermuxKeyBg = Color(0xFF161B22)
private val TermuxKeyBorder = Color(0xFF28323E)
private val TermuxPromptGreen = Color(0xFF4ADE80)
private val TermuxOutputWhite = Color(0xFFE5E7EB)

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
    val voiceAutoExecute by viewModel.voiceAutoExecute.collectAsStateWithLifecycle()
    val voiceReadoutEnabled by viewModel.voiceReadoutEnabled.collectAsStateWithLifecycle()
    val isTranslatingVoice by viewModel.isTranslatingVoice.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val voicePermissionDenied by viewModel.voicePermissionDenied.collectAsStateWithLifecycle()
    val voiceState by voiceController.voiceState.collectAsStateWithLifecycle()
    val voiceEngineMode by voiceController.engineMode.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var isInputFocused by remember { mutableStateOf(false) }

    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = inputText,
                selection = TextRange(inputText.length)
            )
        )
    }

    LaunchedEffect(inputText) {
        if (inputText != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = inputText,
                selection = TextRange(inputText.length)
            )
        }
    }

    fun focusTerminalInput() {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun insertTokenAtCursor(token: String) {
        val sel = textFieldValue.selection
        val start = sel.min.coerceIn(0, textFieldValue.text.length)
        val end = sel.max.coerceIn(0, textFieldValue.text.length)
        val newText = buildString {
            append(textFieldValue.text.substring(0, start))
            append(token)
            append(textFieldValue.text.substring(end))
        }
        val nextCursor = start + token.length
        textFieldValue = TextFieldValue(text = newText, selection = TextRange(nextCursor))
        viewModel.onInputChanged(newText)
        focusTerminalInput()
    }

    LaunchedEffect(voiceState) {
        when (val state = voiceState) {
            is VoiceState.Result -> {
                voiceController.resetState()
                viewModel.handleVoiceTranscript(state.transcript)
            }
            is VoiceState.AudioReady -> {
                voiceController.resetState()
                viewModel.handleVoiceAudio(state.audioBytes, state.mimeType)
            }
            else -> Unit
        }
    }

    LaunchedEffect(voiceController) {
        viewModel.voiceSpeakEvent.collect { spoken ->
            voiceController.speak(spoken)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.clearStatusBanner()
            voiceController.startListening()
        } else {
            viewModel.setVoicePermissionDeniedError()
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
            .background(TermuxBlack),
    ) {
        // Slim Termux-style session header
        TerminalHeaderBar(
            activeProfile = activeProfile,
            currentDir = currentDir,
            metrics = metrics,
            isListening = voiceState is VoiceState.Listening,
            voiceReadoutEnabled = voiceReadoutEnabled,
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
            onToggleVoiceReadout = {
                viewModel.toggleVoiceReadout()
                if (voiceReadoutEnabled) {
                    voiceController.stopSpeaking()
                }
            },
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = bannerMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    if (voicePermissionDenied) {
                        TextButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.testTag("voice_open_settings_button"),
                        ) {
                            Text(
                                text = stringResource(R.string.voice_open_settings),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
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

        // Pure Termux Console Buffer (tap anywhere to focus terminal input)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(TermuxBlack)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { focusTerminalInput() },
                ),
        ) {
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("terminal_output_list"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        TermuxConsoleLine(entry = entry)
                    }
                }
            }
        }

        // Active Voice Strip (only visible when recording or processing voice)
        AnimatedVisibility(visible = voiceState is VoiceState.Listening || isTranslatingVoice) {
            VoiceStatusStrip(
                voiceState = voiceState,
                isTranslating = isTranslatingVoice,
                voiceAiTranslate = voiceAiTranslate,
                voiceAutoExecute = voiceAutoExecute,
                voiceEngineMode = voiceEngineMode,
                onToggleVoiceAi = { viewModel.toggleVoiceAiTranslate() },
                onToggleVoiceAutoExecute = { viewModel.toggleVoiceAutoExecute() },
                onCycleVoiceEngine = { voiceController.cycleEngineMode() },
                onCancel = { voiceController.cancelListening() },
                onDone = { voiceController.stopListening() },
            )
        }

        // Slim Inline AI Suggestions Row
        TermuxAiSuggestionsBar(
            suggestionsState = suggestionsState,
            onRefresh = { viewModel.requestManualSuggestions() },
            onSelectSuggestion = { suggestion, runNow ->
                viewModel.applySuggestion(suggestion.command, runImmediately = runNow)
                if (!runNow) focusTerminalInput()
            },
        )

        HorizontalDivider(color = TermuxKeyBorder, thickness = 1.dp)

        // Termux Inline Prompt Input Bar (No Mic Button — Pure Terminal Input)
        TermuxPromptInputBar(
            textFieldValue = textFieldValue,
            promptLabel = buildTermuxPromptLabel(activeProfile?.promptSymbol, currentDir),
            isFocused = isInputFocused,
            focusRequester = focusRequester,
            onFocusChanged = { isInputFocused = it },
            onValueChange = { updated ->
                textFieldValue = updated
                if (updated.text != inputText) {
                    viewModel.onInputChanged(updated.text)
                }
            },
            onExecute = {
                viewModel.executeCommand(textFieldValue.text)
                focusTerminalInput()
            },
            onBarClick = { focusTerminalInput() },
        )

        // Authentic Termux Extra-Keys Bar
        TermuxExtraKeysBar(
            onInsertToken = ::insertTokenAtCursor,
            onTabComplete = {
                val first = (suggestionsState as? UiState.Success)?.data?.firstOrNull()
                if (first != null) {
                    viewModel.applySuggestion(first.command, runImmediately = false)
                } else {
                    viewModel.requestManualSuggestions()
                }
                focusTerminalInput()
            },
            onHistoryUp = {
                viewModel.cycleHistory(up = true)
                focusTerminalInput()
            },
            onHistoryDown = {
                viewModel.cycleHistory(up = false)
                focusTerminalInput()
            },
            onClear = {
                viewModel.executeCommand("clear")
                focusTerminalInput()
            },
        )
    }
}

private fun buildTermuxPromptLabel(promptSymbol: String?, currentDir: String): String {
    val cleanSymbol = promptSymbol?.trim().orEmpty()
    return when {
        cleanSymbol.isEmpty() -> "$currentDir $"
        cleanSymbol.endsWith("$") || cleanSymbol.endsWith("#") -> "$currentDir ${cleanSymbol.last()}"
        else -> "$currentDir $"
    }
}

@Composable
private fun TerminalHeaderBar(
    activeProfile: TerminalProfile?,
    currentDir: String,
    metrics: SystemMetricsSnapshot,
    isListening: Boolean,
    voiceReadoutEnabled: Boolean,
    onMicClick: () -> Unit,
    onToggleVoiceReadout: () -> Unit,
    onClearTerminal: () -> Unit,
    onOpenMonitor: () -> Unit,
) {
    Surface(
        color = Color(0xFF0A0D10),
        border = BorderStroke(1.dp, TermuxKeyBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${activeProfile?.name ?: "alpine"} (${activeProfile?.preferredShell ?: "/bin/ash"})",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = TermuxPromptGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = currentDir,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = CyanElectric,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Surface(
                    color = TermuxKeyBg,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, TermuxKeyBorder),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clickable { onOpenMonitor() }
                        .testTag("terminal_telemetry_pill"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "CPU ${metrics.cpuPercent.toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = if (metrics.cpuPercent >= (activeProfile?.cpuAlertThreshold ?: 75L)) AmberAlert else CyanElectric,
                        )
                        Text(
                            text = "MEM ${metrics.memPercent.toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = if (metrics.memPercent >= (activeProfile?.memAlertThreshold ?: 80L)) AmberAlert else TermuxPromptGreen,
                        )
                    }
                }

                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("voice_mic_button"),
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = if (isListening) "Stop voice input" else "Voice command input",
                        tint = if (isListening) CrimsonError else TextMuted,
                    )
                }

                IconButton(
                    onClick = onToggleVoiceReadout,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("voice_readout_toggle_button"),
                ) {
                    Icon(
                        imageVector = if (voiceReadoutEnabled) {
                            Icons.AutoMirrored.Rounded.VolumeUp
                        } else {
                            Icons.AutoMirrored.Rounded.VolumeOff
                        },
                        contentDescription = if (voiceReadoutEnabled) "Disable voice readout" else "Enable voice readout",
                        tint = if (voiceReadoutEnabled) CyanElectric else TextMuted,
                    )
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
                        tint = TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun TermuxConsoleLine(entry: TerminalEntry) {
    val monoStyle = TerminalOutputStyle.copy(
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    )
    when (entry.type) {
        EntryType.COMMAND -> {
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = TermuxPromptGreen,
                            fontWeight = FontWeight.Bold,
                        )
                    ) {
                        append(entry.prompt)
                        append(" ")
                    }
                    withStyle(
                        SpanStyle(
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                        )
                    ) {
                        append(entry.text)
                    }
                },
                style = monoStyle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EntryType.OUTPUT -> {
            Text(
                text = entry.text,
                style = monoStyle,
                color = TermuxOutputWhite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EntryType.ERROR -> {
            Text(
                text = entry.text,
                style = monoStyle,
                color = CrimsonError,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EntryType.SYSTEM -> {
            Text(
                text = entry.text,
                style = monoStyle,
                color = CyanElectric,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EntryType.AI_SUGGESTION -> {
            Text(
                text = "# ${entry.text}",
                style = monoStyle,
                color = Color(0xFFC4B5FD),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TermuxPromptInputBar(
    textFieldValue: TextFieldValue,
    promptLabel: String,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    onExecute: () -> Unit,
    onBarClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TermuxBlack)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBarClick,
            )
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = promptLabel,
            style = TerminalOutputStyle.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = if (isFocused) TermuxPromptGreen else MintEmerald,
        )
        BasicTextField(
            value = textFieldValue,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TerminalOutputStyle.copy(
                color = Color.White,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(TermuxPromptGreen),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = { onExecute() },
                onDone = { onExecute() },
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            text = "type command...",
                            style = TerminalOutputStyle.copy(fontSize = 13.5.sp),
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyUp &&
                        (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)
                    ) {
                        onExecute()
                        true
                    } else {
                        false
                    }
                }
                .testTag("terminal_command_input"),
        )
        IconButton(
            onClick = onExecute,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("execute_command_button"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardReturn,
                contentDescription = "Execute command",
                tint = TermuxPromptGreen,
            )
        }
    }
}

@Composable
private fun TermuxExtraKeysBar(
    onInsertToken: (String) -> Unit,
    onTabComplete: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = Color(0xFF0C1015),
        border = BorderStroke(1.dp, TermuxKeyBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TermuxKeyButton(label = "TAB", onClick = onTabComplete)
            TermuxKeyButton(label = "↑", onClick = onHistoryUp, testTag = "history_up_button")
            TermuxKeyButton(label = "↓", onClick = onHistoryDown, testTag = "history_down_button")
            TermuxKeyButton(label = "/", onClick = { onInsertToken("/") })
            TermuxKeyButton(label = "-", onClick = { onInsertToken("-") })
            TermuxKeyButton(label = "|", onClick = { onInsertToken(" | ") })
            TermuxKeyButton(label = "~", onClick = { onInsertToken("~/") })
            TermuxKeyButton(label = "&&", onClick = { onInsertToken(" && ") })
            TermuxKeyButton(label = ">", onClick = { onInsertToken(" > ") })
            TermuxKeyButton(label = "CTRL+L", onClick = onClear)
            TermuxKeyButton(label = "ls", onClick = { onInsertToken("ls -la") })
            TermuxKeyButton(label = "apk", onClick = { onInsertToken("apk ") })
        }
    }
}

@Composable
private fun TermuxKeyButton(
    label: String,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    Surface(
        color = TermuxKeyBg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, TermuxKeyBorder),
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clickable { onClick() }
            .let { mod -> if (testTag != null) mod.testTag(testTag) else mod },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = TermuxOutputWhite,
            )
        }
    }
}

@Composable
private fun TermuxAiSuggestionsBar(
    suggestionsState: UiState<List<AiSuggestion>>,
    onRefresh: () -> Unit,
    onSelectSuggestion: (AiSuggestion, Boolean) -> Unit,
) {
    Surface(
        color = Color(0xFF0A0D10),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = ObsidianSurfaceVariant,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, CyanElectric.copy(alpha = 0.45f)),
                modifier = Modifier
                    .clickable { onRefresh() }
                    .testTag("ai_suggest_refresh_button"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (suggestionsState is UiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = CyanElectric,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "Refresh AI suggestions",
                            tint = CyanElectric,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Text(
                        text = "AI",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = CyanElectric,
                    )
                }
            }

            when (suggestionsState) {
                is UiState.Success -> {
                    suggestionsState.data.forEachIndexed { index, suggestion ->
                        Surface(
                            color = TermuxKeyBg,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, TermuxKeyBorder),
                            modifier = Modifier
                                .clickable { onSelectSuggestion(suggestion, false) }
                                .testTag("ai_suggestion_card_$index"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = suggestion.command,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                    ),
                                    color = TermuxPromptGreen,
                                    maxLines = 1,
                                )
                                Text(
                                    text = "RUN",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = CyanElectric,
                                    modifier = Modifier.clickable {
                                        onSelectSuggestion(suggestion, true)
                                    },
                                )
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
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
private fun VoiceStatusStrip(
    voiceState: VoiceState,
    isTranslating: Boolean,
    voiceAiTranslate: Boolean,
    voiceAutoExecute: Boolean,
    voiceEngineMode: VoiceEngineMode,
    onToggleVoiceAi: () -> Unit,
    onToggleVoiceAutoExecute: () -> Unit,
    onCycleVoiceEngine: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val listening = voiceState as? VoiceState.Listening
    val animatedLevel by animateFloatAsState(
        targetValue = ((listening?.rmsDb ?: 2f) / 12f).coerceIn(0.12f, 1f),
        animationSpec = tween(durationMillis = 120),
        label = "voice_rms_level",
    )

    Surface(
        color = ObsidianSurface,
        border = BorderStroke(1.dp, CyanElectric.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("voice_status_strip"),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = when {
                            isTranslating -> "Gemini AI"
                            listening?.isAudioCapture == true -> "Gemini Audio"
                            else -> "Live Speech"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = CyanElectric,
                    )
                    if (listening != null) {
                        val mins = listening.elapsedSeconds / 60
                        val secs = listening.elapsedSeconds % 60
                        Text(
                            text = String.format("%d:%02d", mins, secs),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = TermuxPromptGreen,
                        )
                    }
                }

                if (listening != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("voice_cancel_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cancel voice recording",
                                tint = CrimsonError,
                            )
                        }
                        Surface(
                            color = MintEmerald,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .clickable { onDone() }
                                .testTag("voice_done_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Finish voice input",
                                    tint = ObsidianBg,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Done",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ObsidianBg,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = when {
                    isTranslating -> stringResource(R.string.voice_translating)
                    listening?.isAudioCapture == true -> stringResource(R.string.voice_listening_audio)
                    else -> {
                        val preview = listening?.partialText.orEmpty()
                        if (preview.isBlank()) stringResource(R.string.voice_listening_speech) else "\"$preview\""
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TermuxKeyButton(
                    label = if (voiceAiTranslate) "AI Shell: ON" else "AI Shell: OFF",
                    onClick = onToggleVoiceAi,
                    testTag = "voice_ai_toggle_chip",
                )
                TermuxKeyButton(
                    label = if (voiceAutoExecute) "Auto-Run: ON" else "Auto-Run: OFF",
                    onClick = onToggleVoiceAutoExecute,
                    testTag = "voice_auto_run_chip",
                )
                TermuxKeyButton(
                    label = "Mic: ${voiceEngineMode.label}",
                    onClick = onCycleVoiceEngine,
                    testTag = "voice_engine_mode_chip",
                )
            }

            if (isTranslating) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CyanElectric,
                    trackColor = ObsidianSurfaceVariant,
                )
            } else if (listening != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                ) {
                    val barCount = 24
                    val gap = size.width / (barCount * 2f)
                    val barWidth = gap
                    val centerY = size.height / 2f
                    for (i in 0 until barCount) {
                        val wave = (0.35f + 0.65f * kotlin.math.abs(
                            sin((i * 0.6f) + (listening.elapsedSeconds * 1.4f) + (animatedLevel * 5f))
                        ))
                        val barHeight = (size.height * animatedLevel * wave).coerceAtLeast(3.dp.toPx())
                        val x = i * (barWidth + gap) + gap / 2f
                        drawRoundRect(
                            color = if (i % 2 == 0) CyanElectric else TermuxPromptGreen,
                            topLeft = Offset(x, centerY - barHeight / 2f),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TermuxTerminalPreview() {
    com.example.ui.theme.AlpacaShellTheme {
        Column(modifier = Modifier.background(TermuxBlack)) {
            TermuxConsoleLine(
                entry = TerminalEntry(
                    id = 1L,
                    timestamp = "10:12:00",
                    prompt = "~ $",
                    text = "uname -a",
                    type = EntryType.COMMAND,
                )
            )
            TermuxConsoleLine(
                entry = TerminalEntry(
                    id = 2L,
                    timestamp = "10:12:01",
                    text = "Linux alpine-android 6.1.0-android aarch64 Linux (Alpine 3.21.3)",
                    type = EntryType.OUTPUT,
                )
            )
            TermuxPromptInputBar(
                textFieldValue = TextFieldValue("ls -la"),
                promptLabel = "~ $",
                isFocused = true,
                focusRequester = remember { FocusRequester() },
                onFocusChanged = {},
                onValueChange = {},
                onExecute = {},
                onBarClick = {},
            )
            TermuxExtraKeysBar(
                onInsertToken = {},
                onTabComplete = {},
                onHistoryUp = {},
                onHistoryDown = {},
                onClear = {},
            )
        }
    }
}
