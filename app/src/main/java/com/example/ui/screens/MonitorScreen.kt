package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BottleneckAnalysis
import com.example.data.model.OptimizationAction
import com.example.data.model.UiState
import com.example.ui.components.MetricGaugeCard
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
import com.example.ui.theme.VioletAccent
import com.example.viewmodel.TerminalMonitorViewModel
import java.util.Locale

@Composable
fun MonitorScreen(
    viewModel: TerminalMonitorViewModel,
    onRunCommandInTerminal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics by viewModel.metricsState.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val bottleneckState by viewModel.bottleneckState.collectAsStateWithLifecycle()

    val cpuThreshold = activeProfile?.cpuAlertThreshold ?: 75L
    val memThreshold = activeProfile?.memAlertThreshold ?: 80L

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "System Monitor",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                )
                Text(
                    text = "Live device & Alpaca rootfs telemetry • ${metrics.cpuCores} cores • uptime ${metrics.uptimeSeconds}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = MintEmerald.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MintEmerald.copy(alpha = 0.4f)),
            ) {
                Text(
                    text = "LIVE 1.5s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MintEmerald,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        // Responsive 2-column (or 1-column on very narrow screens) telemetry grid
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val twoColumns = maxWidth >= 340.dp
            if (twoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricGaugeCard(
                            title = "CPU",
                            primaryValue = String.format(Locale.US, "%.1f%%", metrics.cpuPercent),
                            subtitle = if (metrics.cpuFreqMhz > 0) "${metrics.cpuFreqMhz} MHz • ${metrics.processCount} threads" else "${metrics.cpuCores} cores • ${metrics.processCount} threads",
                            badgeText = "Alert >$cpuThreshold%",
                            accentColor = if (metrics.cpuPercent >= cpuThreshold) AmberAlert else CyanElectric,
                            icon = Icons.Rounded.Speed,
                            history = metrics.cpuHistory,
                            maxValue = 100f,
                            isWarning = metrics.cpuPercent >= cpuThreshold,
                            modifier = Modifier.weight(1f),
                        )
                        MetricGaugeCard(
                            title = "Memory",
                            primaryValue = "${metrics.memUsedMb} MB",
                            subtitle = "${metrics.memPercent.toInt()}% of ${metrics.memTotalMb} MB (JVM ${metrics.jvmHeapUsedMb}MB)",
                            badgeText = "Alert >$memThreshold%",
                            accentColor = if (metrics.memPercent >= memThreshold) AmberAlert else MintEmerald,
                            icon = Icons.Rounded.Memory,
                            history = metrics.memHistory,
                            maxValue = 100f,
                            isWarning = metrics.memPercent >= memThreshold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricGaugeCard(
                            title = "Disk & I/O",
                            primaryValue = String.format(Locale.US, "%.1f KB/s", metrics.diskIoKbps),
                            subtitle = String.format(Locale.US, "%.1f / %.1f GB used (%.0f%%)", metrics.diskUsedGb, metrics.diskTotalGb, metrics.diskPercent),
                            badgeText = "${metrics.diskPercent.toInt()}% full",
                            accentColor = AmberAlert,
                            icon = Icons.Rounded.Storage,
                            history = metrics.diskIoHistory,
                            modifier = Modifier.weight(1f),
                        )
                        MetricGaugeCard(
                            title = "Network",
                            primaryValue = String.format(Locale.US, "%.1f KB/s", metrics.netRxKbps + metrics.netTxKbps),
                            subtitle = String.format(Locale.US, "RX %.1f • TX %.1f KB/s (%.2f MB)", metrics.netRxKbps, metrics.netTxKbps, metrics.totalNetMb),
                            badgeText = "RX+TX",
                            accentColor = VioletAccent,
                            icon = Icons.Rounded.NetworkCheck,
                            history = metrics.netHistory,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricGaugeCard(
                        title = "CPU",
                        primaryValue = String.format(Locale.US, "%.1f%%", metrics.cpuPercent),
                        subtitle = "${metrics.cpuCores} cores • ${metrics.processCount} threads",
                        badgeText = "Alert >$cpuThreshold%",
                        accentColor = CyanElectric,
                        icon = Icons.Rounded.Speed,
                        history = metrics.cpuHistory,
                        maxValue = 100f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MetricGaugeCard(
                        title = "Memory",
                        primaryValue = "${metrics.memUsedMb} MB",
                        subtitle = "${metrics.memPercent.toInt()}% of ${metrics.memTotalMb} MB",
                        badgeText = "Alert >$memThreshold%",
                        accentColor = MintEmerald,
                        icon = Icons.Rounded.Memory,
                        history = metrics.memHistory,
                        maxValue = 100f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // AI Bottleneck Analyzer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
            border = BorderStroke(1.dp, ObsidianOutline),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = CyanElectric,
                        )
                        Column {
                            Text(
                                text = "AI Bottleneck & Optimization Analyzer",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                            )
                            Text(
                                text = "Model: ${activeProfile?.aiModelName ?: "gemini-3.8-flash"} • Proactive alerts: ${if (activeProfile?.proactiveMonitoring != false) "ON" else "OFF"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Button(
                    onClick = { viewModel.runBottleneckAnalysis(isProactive = false) },
                    enabled = bottleneckState !is UiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("analyze_bottlenecks_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanElectric,
                        contentColor = ObsidianBg,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (bottleneckState is UiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = ObsidianBg,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyzing live telemetry...")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyze Performance Bottlenecks", fontWeight = FontWeight.Bold)
                    }
                }

                when (val state = bottleneckState) {
                    is UiState.Loading -> Unit
                    is UiState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    is UiState.Success -> {
                        val analysis = state.data
                        if (analysis == null) {
                            Text(
                                text = "Tap Analyze above to have Gemini inspect CPU, memory, disk I/O, network traffic, and recent shell commands for performance bottlenecks.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            BottleneckReportCard(
                                analysis = analysis,
                                onRunAction = onRunCommandInTerminal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottleneckReportCard(
    analysis: BottleneckAnalysis,
    onRunAction: (String) -> Unit,
) {
    val badgeColor = when (analysis.severity.lowercase()) {
        "critical" -> CrimsonError
        "warning" -> AmberAlert
        else -> MintEmerald
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = badgeColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = analysis.severity.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                text = "Focus: ${analysis.bottleneckArea}",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
        }
        Text(
            text = analysis.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        analysis.actions.forEachIndexed { idx, action ->
            OptimizationActionRow(
                action = action,
                index = idx,
                onRun = { onRunAction(action.command) },
            )
        }
    }
}

@Composable
private fun OptimizationActionRow(
    action: OptimizationAction,
    index: Int,
    onRun: () -> Unit,
) {
    Surface(
        color = ObsidianSurfaceVariant,
        border = BorderStroke(1.dp, ObsidianOutline),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = action.impact,
                    style = MaterialTheme.typography.labelSmall,
                    color = MintEmerald,
                )
            }
            Text(
                text = action.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianBg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "$ ${action.command}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = CyanElectric,
                )
            }
            OutlinedButton(
                onClick = onRun,
                border = BorderStroke(1.dp, MintEmerald),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("run_optimization_button_$index"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MintEmerald,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Run in Terminal", color = MintEmerald)
            }
        }
    }
}
