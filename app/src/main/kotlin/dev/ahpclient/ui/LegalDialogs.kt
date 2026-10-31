package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal const val LEGAL_DISCLAIMER =
    "AHP Client is an independent client for the open Agent Host Protocol. " +
        "It is not affiliated with, endorsed by, or sponsored by Microsoft Corporation or GitHub, Inc. " +
        "Visual Studio Code and GitHub are trademarks of their respective owners."

@Composable
internal fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("privacy_policy_dialog"),
        title = { Text("Privacy policy") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PolicySection(
                    title = "1. Zero developer data collection",
                    body = "AHP Client has no developer backend server, no analytics SDK, no crash-reporting SDK, and no advertising SDK. " +
                        "Prompts, code diffs, terminal output, and session transcripts travel directly between your phone and the agent hosts you configure.",
                )
                PolicySection(
                    title = "2. Encrypted on-device storage",
                    body = "GitHub OAuth device-flow tokens, direct-host bearer tokens, and the cold-start session list cache are stored exclusively on your device, " +
                        "encrypted with AES-256-GCM under hardware-backed Android Keystore keys. Cloud backup is disabled (allowBackup=false).",
                )
                PolicySection(
                    title = "3. Direct network connections",
                    body = "• GitHub (github.com): Used only when you sign in via OAuth Device Flow to obtain or refresh tokens for Microsoft Dev Tunnels.\n" +
                        "• Microsoft Dev Tunnels (*.tunnels.api.visualstudio.com, *.devtunnels.ms): Used over TLS (wss://) to list and connect to your VS Code Remote Tunnel Access hosts.\n" +
                        "• Direct ahpd hosts: Connects directly to the ws:// or wss:// address you enter.\n" +
                        "• Google Play Billing: Processes optional one-time AHP Client Pro unlock purchases directly through Google Play.",
                )
                PolicySection(
                    title = "4. Device permissions & deletion",
                    body = "Biometric checks run locally via Android BiometricPrompt before approving tool calls. " +
                        "The foreground monitoring service runs only while you turn on 'Monitor in background'. " +
                        "Tapping Sign out in Settings or clearing app storage immediately destroys the Keystore keys and deletes cached data.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("privacy_policy_close"),
            ) {
                Text("Close")
            }
        },
    )
}

@Composable
internal fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("licenses_dialog"),
        title = { Text("Open-source licenses") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LicenseEntry(
                    name = "Agent Host Protocol Kotlin SDK",
                    copyright = "Copyright (c) Microsoft Corporation",
                    license = "MIT License",
                )
                LicenseEntry(
                    name = "Kotlin & Kotlinx (Coroutines, Serialization)",
                    copyright = "Copyright (c) JetBrains s.r.o. and Kotlin Programming Language contributors",
                    license = "Apache License, Version 2.0",
                )
                LicenseEntry(
                    name = "Android Jetpack, Compose, DataStore, WorkManager & Biometric",
                    copyright = "Copyright (c) The Android Open Source Project",
                    license = "Apache License, Version 2.0",
                )
                LicenseEntry(
                    name = "OkHttp",
                    copyright = "Copyright (c) Square, Inc.",
                    license = "Apache License, Version 2.0",
                )
                LicenseEntry(
                    name = "Multiplatform Markdown Renderer",
                    copyright = "Copyright (c) Mike Penz",
                    license = "Apache License, Version 2.0",
                )
                PolicySection(
                    title = "MIT License",
                    body = "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files, " +
                        "to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, " +
                        "sublicense, and/or sell copies of the Software, subject to the above copyright notice and this permission notice being included in all copies. " +
                        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND.",
                )
                PolicySection(
                    title = "Apache License, Version 2.0",
                    body = "Licensed under the Apache License, Version 2.0. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0. " +
                        "Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an \"AS IS\" BASIS, " +
                        "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("licenses_close"),
            ) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun PolicySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LicenseEntry(name: String, copyright: String, license: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text("$copyright • $license", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
