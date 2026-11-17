package com.example.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.firebase.attemptAutoSignIn
import com.example.firebase.onGoogleSignInClicked
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MintEmerald
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var isSigningIn by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {},
            scope = scope,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ObsidianBg, Color(0xFF0E1822), ObsidianBg)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_alpaca_hero),
                    contentDescription = "Alpaca Shell AI Linux Terminal illustration",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    ObsidianBg.copy(alpha = 0.85f),
                                )
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        color = CyanElectric.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CyanElectric.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = "ALPACA v3.21",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanElectric,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Surface(
                        color = MintEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MintEmerald.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = "AI COPILOT",
                            style = MaterialTheme.typography.labelMedium,
                            color = MintEmerald,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Alpaca Shell",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Bootstrap Linux terminal powered by real-time Gemini suggestions, voice-to-shell commands, live telemetry analysis, and synced profiles.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
                border = BorderStroke(1.dp, ObsidianOutline),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    FeatureRow(
                        icon = Icons.Rounded.Terminal,
                        tint = CyanElectric,
                        title = "Alpaca Linux Bootstrap Shell",
                        subtitle = "Stateful ash/sh/bash workspace, apk tools, pipes & scripts",
                    )
                    FeatureRow(
                        icon = Icons.Rounded.GraphicEq,
                        tint = MintEmerald,
                        title = "Voice & Real-Time AI Suggestions",
                        subtitle = "Speak natural language or type for live command completions",
                    )
                    FeatureRow(
                        icon = Icons.Rounded.MonitorHeart,
                        tint = MaterialTheme.colorScheme.tertiary,
                        title = "Live System Monitor & Bottleneck AI",
                        subtitle = "Tracks CPU, memory, disk I/O & network with proactive alerts",
                    )
                    FeatureRow(
                        icon = Icons.Rounded.Code,
                        tint = CyanElectric,
                        title = "Code Generator & Debugger Studio",
                        subtitle = "Generate, debug, fix & cloud-sync scripts across profiles",
                    )
                }
            }

            if (authError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = authError.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            Button(
                onClick = {
                    authError = null
                    isSigningIn = true
                    onGoogleSignInClicked(
                        context = context,
                        credentialManager = credentialManager,
                        onAuthSuccess = {
                            isSigningIn = false
                            onAuthSuccess()
                        },
                        onAuthError = { msg ->
                            isSigningIn = false
                            authError = msg
                        },
                        scope = scope,
                        onAuthCancelled = {
                            isSigningIn = false
                        },
                    )
                },
                enabled = !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("google_sign_in_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanElectric,
                    contentColor = ObsidianBg,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = ObsidianBg,
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    Text(
                        text = "Sign in with Google to Initialize Shell",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = JetBrainsMonoFontFamily,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = ObsidianSurfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
