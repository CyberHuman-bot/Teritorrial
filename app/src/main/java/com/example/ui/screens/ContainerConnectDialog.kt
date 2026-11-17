package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.repository.SshConnectionState
import com.example.data.repository.SshContainerConfig
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanElectric
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MintEmerald
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerConnectSheet(
    sshState: SshConnectionState,
    onConnect: (SshContainerConfig) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val activeConfig = (sshState as? SshConnectionState.Connected)?.config
    var host by remember(activeConfig) { mutableStateOf(activeConfig?.host ?: "127.0.0.1") }
    var portText by remember(activeConfig) { mutableStateOf((activeConfig?.port ?: 8022).toString()) }
    var username by remember(activeConfig) { mutableStateOf(activeConfig?.username ?: "root") }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
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
                        imageVector = Icons.Rounded.Dns,
                        contentDescription = null,
                        tint = CyanElectric,
                    )
                    Text(
                        text = "Real Linux Container (SSH2)",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close container sheet",
                        tint = TextMuted,
                    )
                }
            }

            Surface(
                color = ObsidianSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ObsidianOutline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Local shell runs Android's native Linux kernel (/system/bin/sh). " +
                        "Android targetSdk 36 SELinux blocks executing downloaded distro ELF binaries in app storage, " +
                        "so connect an SSH2 PTY here for a full Alpine/Ubuntu/Docker/Termux container.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }

            if (sshState is SshConnectionState.Connected) {
                Surface(
                    color = MintEmerald.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MintEmerald.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Connected PTY Session",
                                style = MaterialTheme.typography.labelMedium,
                                color = MintEmerald,
                            )
                            Text(
                                text = sshState.config.displayTarget(),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = TextPrimary,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onDisconnect()
                                onDismiss()
                            },
                            border = BorderStroke(1.dp, CrimsonError.copy(alpha = 0.6f)),
                            modifier = Modifier.testTag("ssh_disconnect_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LinkOff,
                                contentDescription = null,
                                tint = CrimsonError,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Disconnect", color = CrimsonError)
                        }
                    }
                }
            }

            if (sshState is SshConnectionState.Error) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = sshState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.labelMedium,
                color = CyanElectric,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = host == "127.0.0.1" && portText == "8022",
                    onClick = {
                        host = "127.0.0.1"
                        portText = "8022"
                        if (username == "root") username = "u0_a319"
                    },
                    label = { Text("Termux / proot (127.0.0.1:8022)") },
                )
                FilterChip(
                    selected = host == "10.0.2.2" && portText == "2222",
                    onClick = {
                        host = "10.0.2.2"
                        portText = "2222"
                        username = "root"
                    },
                    label = { Text("Host Docker (10.0.2.2:2222)") },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host / IP") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ssh_host_input"),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(0.42f)
                        .testTag("ssh_port_input"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ssh_user_input"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ssh_password_input"),
                )
            }

            OutlinedTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = { Text("Optional Private Key (PEM / OpenSSH)") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                color = ObsidianSurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Termux setup: pkg install openssh && passwd && sshd\n" +
                        "Alpine Docker: docker run -d -p 2222:22 --name alpine-box alpine sh -c \"apk add openssh && ssh-keygen -A && echo 'root:alpine' | chpasswd && sed -i 's/#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config && /usr/sbin/sshd -D\"",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = TextMuted,
                    modifier = Modifier.padding(10.dp),
                )
            }

            Button(
                onClick = {
                    val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 22
                    onConnect(
                        SshContainerConfig(
                            host = host.trim(),
                            port = port,
                            username = username.trim(),
                            password = password,
                            privateKey = privateKey,
                        )
                    )
                    onDismiss()
                },
                enabled = host.isNotBlank() && username.isNotBlank() && sshState !is SshConnectionState.Connecting,
                colors = ButtonDefaults.buttonColors(containerColor = MintEmerald),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("ssh_connect_button"),
            ) {
                if (sshState is SshConnectionState.Connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = ObsidianSurface,
                    )
                } else {
                    Text(
                        text = if (sshState is SshConnectionState.Connected) "Reconnect Container" else "Connect SSH Container",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
