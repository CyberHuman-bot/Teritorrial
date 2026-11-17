package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CodeGenResult
import com.example.data.model.DebugReport
import com.example.data.model.SavedScript
import com.example.data.model.TerminalProfile
import com.example.data.model.UiState
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MintEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.TerminalCodeStyle
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.VioletAccent
import com.example.viewmodel.CodeStudioViewModel
import kotlinx.coroutines.launch

@Composable
fun CodeStudioScreen(
    viewModel: CodeStudioViewModel,
    activeProfile: TerminalProfile?,
    onDeployAndRunScript: suspend (String, String) -> Unit,
    onDeployScriptOnly: suspend (String, String) -> String,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Generate", "Debug", "AI Chat", "Saved")
    val snackbarHostState = remember { SnackbarHostState() }
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(toastMessage) {
        toastMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianBg,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ObsidianBg),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Code & Debug Studio",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                )
                Text(
                    text = "AI Engine: ${activeProfile?.aiModelName ?: "gemini-3.8-flash"} • Target: ${activeProfile?.preferredShell ?: "/bin/ash"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanElectric,
                )
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = ObsidianSurface,
                contentColor = CyanElectric,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = CyanElectric,
                    )
                },
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            if (index == 1) viewModel.refreshWorkspaceFiles()
                        },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selectedTab == index) CyanElectric else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier.testTag("studio_tab_${title.lowercase().replace(" ", "_")}"),
                    )
                }
            }

            when (selectedTab) {
                0 -> GenerateTab(
                    viewModel = viewModel,
                    activeProfile = activeProfile,
                    onSaveScript = { gen ->
                        viewModel.saveScriptToCloud(
                            profileId = activeProfile?.id ?: "prof_alpaca_core",
                            title = gen.title,
                            language = gen.language,
                            code = gen.code,
                            description = gen.explanation,
                            sourceType = "generated",
                        )
                    },
                    onDeployOnly = { gen ->
                        scope.launch {
                            val path = onDeployScriptOnly(gen.title, gen.code)
                            snackbarHostState.showSnackbar("Deployed to $path")
                        }
                    },
                    onRunInTerminal = { gen ->
                        scope.launch {
                            onDeployAndRunScript(gen.title, gen.code)
                        }
                    },
                )
                1 -> DebugTab(
                    viewModel = viewModel,
                    activeProfile = activeProfile,
                    onSaveFixed = { report, lang ->
                        viewModel.saveScriptToCloud(
                            profileId = activeProfile?.id ?: "prof_alpaca_core",
                            title = "debugged_script.$lang",
                            language = lang,
                            code = report.fixedCode,
                            description = report.rootCause,
                            sourceType = "debugged",
                        )
                    },
                    onRunFixed = { report, lang ->
                        scope.launch {
                            onDeployAndRunScript("debugged_script.$lang", report.fixedCode)
                        }
                    },
                )
                2 -> AiChatTab(
                    viewModel = viewModel,
                    activeProfile = activeProfile,
                )
                3 -> SavedScriptsTab(
                    viewModel = viewModel,
                    onRunScript = { script ->
                        scope.launch {
                            onDeployAndRunScript(script.title, script.code)
                        }
                    },
                    onLoadIntoDebugger = { script ->
                        viewModel.onDebugCodeChanged(script.code)
                        viewModel.onLanguageSelected(script.language)
                        selectedTab = 1
                    },
                )
            }
        }
    }
}

@Composable
private fun GenerateTab(
    viewModel: CodeStudioViewModel,
    activeProfile: TerminalProfile?,
    onSaveScript: (CodeGenResult) -> Unit,
    onDeployOnly: (CodeGenResult) -> Unit,
    onRunInTerminal: (CodeGenResult) -> Unit,
) {
    val prompt by viewModel.genPrompt.collectAsStateWithLifecycle()
    val selectedLang by viewModel.selectedLanguage.collectAsStateWithLifecycle()
    val genState by viewModel.codeGenState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = prompt,
            onValueChange = { viewModel.onGenPromptChanged(it) },
            label = { Text("Describe the script or code snippet") },
            placeholder = { Text("e.g., Monitor /var/log for errors and print a summary table in ash") },
            minLines = 3,
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanElectric,
                unfocusedBorderColor = ObsidianOutline,
                focusedContainerColor = ObsidianSurface,
                unfocusedContainerColor = ObsidianSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("codegen_prompt_input"),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("sh", "bash", "python", "awk", "sed", "c").forEach { lang ->
                FilterChip(
                    selected = selectedLang == lang,
                    onClick = { viewModel.onLanguageSelected(lang) },
                    label = { Text(lang.uppercase(), style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanElectric.copy(alpha = 0.2f),
                        selectedLabelColor = CyanElectric,
                    ),
                )
            }
        }

        Button(
            onClick = { viewModel.generateScript(activeProfile) },
            enabled = genState !is UiState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("generate_script_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanElectric,
                contentColor = ObsidianBg,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (genState is UiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = ObsidianBg,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generating with Gemini...")
            } else {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generate Code Snippet", fontWeight = FontWeight.Bold)
            }
        }

        when (val state = genState) {
            is UiState.Loading -> Unit
            is UiState.Error -> ErrorBanner(state.message)
            is UiState.Success -> {
                val res = state.data
                if (res != null) {
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
                                Text(
                                    text = res.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MintEmerald,
                                )
                                Surface(
                                    color = CyanElectric.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = res.language.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanElectric,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            Text(
                                text = res.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (res.apkDependencies.isNotEmpty()) {
                                Text(
                                    text = "apk packages: ${res.apkDependencies.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberAlert,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ObsidianBg, RoundedCornerShape(10.dp))
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = res.code,
                                    style = TerminalCodeStyle,
                                    color = TextPrimary,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { onSaveScript(res) },
                                    border = BorderStroke(1.dp, CyanElectric),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("save_generated_script_button"),
                                ) {
                                    Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, tint = CyanElectric, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save", color = CyanElectric)
                                }
                                OutlinedButton(
                                    onClick = { onDeployOnly(res) },
                                    border = BorderStroke(1.dp, ObsidianOutline),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                ) {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Deploy", color = TextPrimary)
                                }
                            }
                            Button(
                                onClick = { onRunInTerminal(res) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MintEmerald,
                                    contentColor = ObsidianBg,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("run_generated_script_button"),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Deploy & Run in Terminal", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugTab(
    viewModel: CodeStudioViewModel,
    activeProfile: TerminalProfile?,
    onSaveFixed: (DebugReport, String) -> Unit,
    onRunFixed: (DebugReport, String) -> Unit,
) {
    val codeInput by viewModel.debugCodeInput.collectAsStateWithLifecycle()
    val errorInput by viewModel.debugErrorInput.collectAsStateWithLifecycle()
    val selectedLang by viewModel.selectedLanguage.collectAsStateWithLifecycle()
    val workspaceFiles by viewModel.workspaceFiles.collectAsStateWithLifecycle()
    val debugState by viewModel.debugState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (workspaceFiles.isNotEmpty()) {
            Text(
                text = "Load from Alpaca ~/scripts:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                workspaceFiles.forEach { path ->
                    AssistChip(
                        onClick = { viewModel.loadWorkspaceFileIntoDebugger(path) },
                        label = { Text(path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = ObsidianSurfaceVariant,
                            labelColor = CyanElectric,
                        ),
                    )
                }
            }
        }

        OutlinedTextField(
            value = codeInput,
            onValueChange = { viewModel.onDebugCodeChanged(it) },
            label = { Text("Source code to inspect & debug") },
            minLines = 6,
            maxLines = 12,
            textStyle = TerminalCodeStyle.copy(color = TextPrimary),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanElectric,
                unfocusedBorderColor = ObsidianOutline,
                focusedContainerColor = ObsidianSurface,
                unfocusedContainerColor = ObsidianSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("debug_code_input"),
        )

        OutlinedTextField(
            value = errorInput,
            onValueChange = { viewModel.onDebugErrorChanged(it) },
            label = { Text("Terminal stderr / error message (optional)") },
            minLines = 2,
            maxLines = 4,
            textStyle = TerminalCodeStyle.copy(color = CrimsonError),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AmberAlert,
                unfocusedBorderColor = ObsidianOutline,
                focusedContainerColor = ObsidianSurface,
                unfocusedContainerColor = ObsidianSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("debug_error_input"),
        )

        Button(
            onClick = { viewModel.debugUserCode(activeProfile) },
            enabled = debugState !is UiState.Loading,
            colors = ButtonDefaults.buttonColors(
                containerColor = AmberAlert,
                contentColor = ObsidianBg,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("debug_analyze_button"),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (debugState is UiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ObsidianBg, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Diagnosing issues...")
            } else {
                Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Analyze & Suggest Fixes", fontWeight = FontWeight.Bold)
            }
        }

        when (val state = debugState) {
            is UiState.Loading -> Unit
            is UiState.Error -> ErrorBanner(state.message)
            is UiState.Success -> {
                val report = state.data
                if (report != null) {
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
                            Text(
                                text = "Root Cause",
                                style = MaterialTheme.typography.titleSmall,
                                color = AmberAlert,
                            )
                            Text(
                                text = report.rootCause,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                            )

                            report.issues.forEach { issue ->
                                val tint = when (issue.severity) {
                                    "error" -> CrimsonError
                                    "warning" -> AmberAlert
                                    else -> CyanElectric
                                }
                                Surface(
                                    color = ObsidianSurfaceVariant,
                                    border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "${issue.severity.uppercase()} • ${issue.lineHint}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = tint,
                                        )
                                        Text(
                                            text = issue.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary,
                                        )
                                        Text(
                                            text = "Fix: ${issue.fixSummary}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MintEmerald,
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "Fixed Code",
                                style = MaterialTheme.typography.titleSmall,
                                color = MintEmerald,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ObsidianBg, RoundedCornerShape(10.dp))
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = report.fixedCode,
                                    style = TerminalCodeStyle,
                                    color = TextPrimary,
                                )
                            }
                            if (report.optimizationNotes.isNotBlank()) {
                                Text(
                                    text = "Notes: ${report.optimizationNotes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.applyDebugFixToEditor(report.fixedCode) },
                                    border = BorderStroke(1.dp, CyanElectric),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("apply_debug_fix_button"),
                                ) {
                                    Text("Apply Fix", color = CyanElectric)
                                }
                                OutlinedButton(
                                    onClick = { onSaveFixed(report, selectedLang) },
                                    border = BorderStroke(1.dp, ObsidianOutline),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                ) {
                                    Text("Save Fixed", color = TextPrimary)
                                }
                            }
                            Button(
                                onClick = { onRunFixed(report, selectedLang) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MintEmerald,
                                    contentColor = ObsidianBg,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Fix in Terminal", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiChatTab(
    viewModel: CodeStudioViewModel,
    activeProfile: TerminalProfile?,
) {
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isSending by viewModel.isChatSending.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                val isUser = msg.role == "user"
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Surface(
                        color = when {
                            msg.isError -> MaterialTheme.colorScheme.errorContainer
                            isUser -> CyanElectric.copy(alpha = 0.18f)
                            else -> ObsidianSurface
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                msg.isError -> CrimsonError
                                isUser -> CyanElectric.copy(alpha = 0.5f)
                                else -> ObsidianOutline
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = msg.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        Surface(
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianOutline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask Alpaca AI about scripts or Linux...") },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanElectric,
                        unfocusedBorderColor = ObsidianOutline,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("studio_chat_input"),
                )
                IconButton(
                    onClick = {
                        val toSend = input
                        input = ""
                        viewModel.sendChatMessage(toSend, activeProfile)
                    },
                    enabled = !isSending && input.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .background(CyanElectric, RoundedCornerShape(12.dp))
                        .testTag("studio_chat_send_button"),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ObsidianBg, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send chat message", tint = ObsidianBg)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedScriptsTab(
    viewModel: CodeStudioViewModel,
    onRunScript: (SavedScript) -> Unit,
    onLoadIntoDebugger: (SavedScript) -> Unit,
) {
    val scriptsState by viewModel.scriptsState.collectAsStateWithLifecycle()

    when (val state = scriptsState) {
        is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyanElectric)
            }
        }
        is UiState.Error -> {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                ErrorBanner(state.message)
            }
        }
        is UiState.Success -> {
            val scripts = state.data
            if (scripts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Code,
                            contentDescription = null,
                            tint = CyanElectric,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = "No Saved Scripts Yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                        )
                        Text(
                            text = "Generate or debug a script in Code AI and tap Save to sync it to your cloud library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(scripts, key = { it.id }) { script ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                            border = BorderStroke(1.dp, ObsidianOutline),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = script.title,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontFamily = JetBrainsMonoFontFamily,
                                            ),
                                            color = MintEmerald,
                                        )
                                        Text(
                                            text = "${script.language.uppercase()} • ${script.sourceType}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = VioletAccent,
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteScript(script.id) },
                                        modifier = Modifier.minimumInteractiveComponentSize(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Delete saved script",
                                            tint = CrimsonError,
                                        )
                                    }
                                }
                                if (script.description.isNotBlank()) {
                                    Text(
                                        text = script.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { onLoadIntoDebugger(script) },
                                        border = BorderStroke(1.dp, ObsidianOutline),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                    ) {
                                        Text("Debug", color = CyanElectric)
                                    }
                                    Button(
                                        onClick = { onRunScript(script) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MintEmerald,
                                            contentColor = ObsidianBg,
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                    ) {
                                        Text("Run", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}
