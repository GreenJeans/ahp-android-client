package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private enum class AboutSectionTab(val label: String, val tag: String) {
    OVERVIEW("Overview", "about_tab_overview"),
    PRIVACY("Privacy", "about_tab_privacy"),
    LICENSES("Licenses", "about_tab_licenses"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSheet(onDismiss: () -> Unit) {
    var selectedTab by rememberSaveable { mutableStateOf(AboutSectionTab.OVERVIEW) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp) },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.testTag("about_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "About AHP Client",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AboutSectionTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label, maxLines = 1) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(tab.tag),
                    )
                }
            }
            when (selectedTab) {
                AboutSectionTab.OVERVIEW -> OverviewCards()
                AboutSectionTab.PRIVACY -> PrivacyCards()
                AboutSectionTab.LICENSES -> LicenseCards()
            }
        }
    }
}

@Composable
private fun OverviewCards() {
    val items = listOf(
        "Independent AHP client" to "Connects your phone to coding-agent hosts speaking the open Agent Host Protocol (AHP).",
        "Remote tunnels & direct hosts" to "VS Code Remote Tunnel Access uses Microsoft's Visual Studio Tunnel Service GitHub app; direct ahpd hosts connect independently by address.",
        "Trademarks" to "Not affiliated with, endorsed by, or sponsored by Microsoft Corporation or GitHub, Inc. Visual Studio Code and GitHub are trademarks of their respective owners.",
    )
    InfoCardStack(items, tag = "about_overview_content")
}

@Composable
private fun PrivacyCards() {
    val items = listOf(
        "Zero data collection" to "No developer servers, analytics, telemetry, or ads. Session data travels directly between your phone and your hosts.",
        "Encrypted on device" to "GitHub tokens, direct-host tokens, and the session cache are encrypted with AES-256-GCM in Android Keystore (allowBackup=false).",
        "Direct connections only" to "Communicates only with GitHub (device sign-in), Microsoft Dev Tunnels (wss://), your direct ahpd hosts, and Google Play Billing.",
        "Permissions & deletion" to "Biometrics are verified locally by Android. Background monitoring runs only while enabled. Signing out destroys stored keys.",
    )
    InfoCardStack(items, tag = "about_privacy_content")
}

@Composable
private fun LicenseCards() {
    val libraries = listOf(
        "Agent Host Protocol SDK" to "Microsoft Corporation · MIT License",
        "Kotlin & Kotlinx" to "JetBrains s.r.o. · Apache License 2.0",
        "Android Jetpack & Compose" to "The Android Open Source Project · Apache License 2.0",
        "OkHttp" to "Square, Inc. · Apache License 2.0",
        "Markdown Renderer" to "Mike Penz · Apache License 2.0",
    )
    Column(modifier = Modifier.testTag("about_licenses_content")) {
        libraries.forEachIndexed { index, (name, meta) ->
            if (index > 0) Spacer(Modifier.height(Design.Gap))
            TileCard(
                shape = connectedShape(index, libraries.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            "Open-source components are provided \"AS IS\" without warranty under the MIT License and Apache License 2.0.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun InfoCardStack(items: List<Pair<String, String>>, tag: String) {
    Column(modifier = Modifier.testTag(tag)) {
        items.forEachIndexed { index, (title, body) ->
            if (index > 0) Spacer(Modifier.height(Design.Gap))
            TileCard(
                shape = connectedShape(index, items.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
