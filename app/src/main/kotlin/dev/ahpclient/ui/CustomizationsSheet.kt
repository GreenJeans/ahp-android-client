package dev.ahpclient.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.CustomizationCategory
import dev.ahpclient.state.CustomizationEntry
import dev.ahpclient.state.CustomizationsView
import dev.ahpclient.state.HostFileTarget
import dev.ahpclient.state.SlashCommand
import dev.ahpclient.state.SlashCommandKind
import dev.ahpclient.state.SlashCommandMatch
import dev.ahpclient.state.SlashCommands

/**
 * Modal bottom sheet modeled on the VS Code Agents "Customizations" panel.
 *
 * Shows the Overview category list with counts (Plugins, MCP Servers, Skills, Instructions, Agents, Hooks, Tools),
 * search and category filtering, one-tap `/skill` or `@agent` insertion into the composer, and host file inspection
 * (`SKILL.md`, `.agent.md`, `.instructions.md`, `.prompt.md`) when a file reader is wired in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationsSheet(
    customizations: CustomizationsView,
    initialCategory: CustomizationCategory? = null,
    initialQuery: String = "",
    onDismiss: () -> Unit,
    onUseSlashCommand: ((String) -> Unit)? = null,
    onUseAgent: ((String) -> Unit)? = null,
    onViewFile: ((HostFileTarget) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        CustomizationsSheetContent(
            customizations = customizations,
            initialCategory = initialCategory,
            initialQuery = initialQuery,
            onDismiss = onDismiss,
            onUseSlashCommand = onUseSlashCommand,
            onUseAgent = onUseAgent,
            onViewFile = onViewFile,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizationsSheetContent(
    customizations: CustomizationsView,
    initialCategory: CustomizationCategory? = null,
    initialQuery: String = "",
    onDismiss: () -> Unit,
    onUseSlashCommand: ((String) -> Unit)? = null,
    onUseAgent: ((String) -> Unit)? = null,
    onViewFile: ((HostFileTarget) -> Unit)? = null,
) {
    var selectedCategoryName by rememberSaveable(initialCategory) { mutableStateOf(initialCategory?.name) }
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    val selectedCategory = selectedCategoryName?.let { name ->
        CustomizationCategory.entries.firstOrNull { it.name == name }
    }
    val matchingEntries = customizations.entriesIn(selectedCategory, query)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .testTag("customizations_sheet"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Customizations",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = if (customizations.isEmpty) {
                        "No customizations reported for this environment"
                    } else {
                        "${customizations.totalCount} active or configured items in this environment"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("customizations_close")) {
                Text("Done")
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(120) },
            placeholder = { Text("Search skills, MCP servers, instructions, tools…") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }, modifier = Modifier.testTag("customizations_search_clear")) {
                        Icon(AppIcons.Clear, contentDescription = "Clear search")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("customizations_search"),
        )

        Spacer(Modifier.height(8.dp))

        // Category filter bar (Overview + each category with count)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategoryName = null },
                label = { Text("Overview") },
                modifier = Modifier.testTag("customizations_tab_OVERVIEW"),
            )
            for (cat in CustomizationCategory.entries) {
                val count = customizations.countOf(cat)
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = {
                        selectedCategoryName = if (selectedCategory == cat) null else cat.name
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = categoryIcon(cat),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = {
                        Text(if (count > 0) "${cat.label} ($count)" else cat.label)
                    },
                    modifier = Modifier.testTag("customizations_tab_${cat.name}"),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // When on Overview with no search query, render the VS Code-style category summary rows at the top.
            if (selectedCategory == null && query.isBlank()) {
                item(key = "overview_categories") {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customizations_overview_card"),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            CustomizationCategory.entries.forEachIndexed { idx, cat ->
                                val count = customizations.countOf(cat)
                                val hasError = customizations.entriesIn(cat).any { it.isError }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedCategoryName = cat.name }
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                        .testTag("customizations_overview_row_${cat.name}"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            imageVector = categoryIcon(cat),
                                            contentDescription = null,
                                            tint = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = cat.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (hasError) {
                                            Surface(
                                                shape = MaterialTheme.shapes.extraSmall,
                                                color = MaterialTheme.colorScheme.errorContainer,
                                            ) {
                                                Text(
                                                    text = "Attention",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        if (count > 0) {
                                            Text(
                                                text = count.toString(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.testTag("customizations_count_${cat.name}"),
                                            )
                                        } else {
                                            Text(
                                                text = "0",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.testTag("customizations_count_${cat.name}"),
                                            )
                                        }
                                    }
                                }
                                if (idx < CustomizationCategory.entries.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }

            if (matchingEntries.isEmpty()) {
                item(key = "empty_state") {
                    Text(
                        text = if (query.isNotBlank()) {
                            "No customizations match \"$query\"."
                        } else if (selectedCategory != null) {
                            "No ${selectedCategory.label.lowercase()} reported for this session."
                        } else {
                            "No customizations reported yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .testTag("customizations_empty"),
                    )
                }
            } else {
                items(matchingEntries, key = { it.id }) { entry ->
                    CustomizationEntryCard(
                        entry = entry,
                        onUseSlashCommand = onUseSlashCommand?.let { use ->
                            { cmdName ->
                                use(cmdName)
                                onDismiss()
                            }
                        },
                        onUseAgent = onUseAgent?.let { use ->
                            { agentUri ->
                                use(agentUri)
                                onDismiss()
                            }
                        },
                        onViewFile = onViewFile?.let { view ->
                            { target ->
                                onDismiss()
                                view(target)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomizationEntryCard(
    entry: CustomizationEntry,
    onUseSlashCommand: ((String) -> Unit)?,
    onUseAgent: ((String) -> Unit)?,
    onViewFile: ((HostFileTarget) -> Unit)?,
) {
    val isSlashInvokable = (entry.category == CustomizationCategory.SKILLS ||
        (entry.category == CustomizationCategory.INSTRUCTIONS && entry.statusLabel == "Prompt")) &&
        entry.enabled && entry.userInvokable
    val isAgentInvokable = entry.category == CustomizationCategory.AGENTS &&
        entry.enabled && entry.userInvokable && !entry.uri.isNullOrBlank()
    val fileTarget = entry.uri?.let { HostFileTarget.of(it) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("customization_item_${entry.name}"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = categoryIcon(entry.category),
                        contentDescription = entry.category.label,
                        tint = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = when {
                            isSlashInvokable -> "/${entry.name}"
                            isAgentInvokable -> "@${entry.name}"
                            else -> entry.name
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = if (isSlashInvokable || isAgentInvokable || entry.category == CustomizationCategory.TOOLS) {
                            FontFamily.Monospace
                        } else {
                            FontFamily.Default
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.argumentHint?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                entry.statusLabel?.let { status ->
                    val containerColor = when {
                        entry.isError -> MaterialTheme.colorScheme.errorContainer
                        !entry.enabled -> MaterialTheme.colorScheme.surfaceVariant
                        entry.isReady -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    val contentColor = when {
                        entry.isError -> MaterialTheme.colorScheme.onErrorContainer
                        !entry.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        entry.isReady -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = containerColor,
                        contentColor = contentColor,
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            entry.description?.let { desc ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (entry.source != null || entry.badges.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.source?.let { src ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = src,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    for (badge in entry.badges) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = if (badge.startsWith("/") || badge.startsWith("@") || badge.contains('*')) {
                                    FontFamily.Monospace
                                } else {
                                    FontFamily.Default
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            val agentUri = entry.uri
            if ((isSlashInvokable && onUseSlashCommand != null) ||
                (isAgentInvokable && onUseAgent != null && agentUri != null) ||
                (fileTarget != null && onViewFile != null)
            ) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSlashInvokable && onUseSlashCommand != null) {
                        OutlinedButton(
                            onClick = { onUseSlashCommand(entry.name) },
                            modifier = Modifier.testTag("customization_use_skill_${entry.name}"),
                        ) {
                            Text("Use /${entry.name}")
                        }
                    }
                    if (isAgentInvokable && onUseAgent != null && agentUri != null) {
                        OutlinedButton(
                            onClick = { onUseAgent(agentUri) },
                            modifier = Modifier.testTag("customization_use_agent_${entry.name}"),
                        ) {
                            Text("Use @${entry.name}")
                        }
                    }
                    if (fileTarget != null && onViewFile != null) {
                        TextButton(
                            onClick = { onViewFile(fileTarget) },
                            modifier = Modifier.testTag("customization_view_file_${entry.name}"),
                        ) {
                            Text("View file")
                        }
                    }
                }
            }
        }
    }
}

internal fun categoryIcon(category: CustomizationCategory): ImageVector = when (category) {
    CustomizationCategory.PLUGINS -> AppIcons.Plug
    CustomizationCategory.MCP_SERVERS -> AppIcons.Host
    CustomizationCategory.SKILLS -> AppIcons.Lightbulb
    CustomizationCategory.INSTRUCTIONS -> AppIcons.BookOpen
    CustomizationCategory.AGENTS -> AppIcons.AgentCode
    CustomizationCategory.HOOKS -> AppIcons.Lightning
    CustomizationCategory.TOOLS -> AppIcons.Wrench
}

/**
 * Formats a `/command` name with bold primary-colored spans for every range in [SlashCommandMatch.highlightRanges]
 * (supporting prefix, substring, and multi-span subsequence matches like `/prw` -> **`/pr`**`-`**`w`**`atch`).
 */
internal fun formatSlashCommandName(
    match: SlashCommandMatch,
    primaryColor: Color,
    normalColor: Color,
): AnnotatedString = buildAnnotatedString {
    val name = match.command.name
    val ranges = match.highlightRanges
    if (ranges.isEmpty()) {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = normalColor)) {
            append("/")
            append(name)
        }
        return@buildAnnotatedString
    }
    val startsAtZero = ranges.first().first == 0
    withStyle(
        if (startsAtZero) {
            SpanStyle(fontWeight = FontWeight.Bold, color = primaryColor)
        } else {
            SpanStyle(color = normalColor)
        },
    ) {
        append("/")
    }
    var cursor = 0
    for (range in ranges) {
        val rStart = range.first.coerceIn(cursor, name.length)
        val rEnd = (range.last + 1).coerceIn(rStart, name.length)
        if (rStart > cursor) {
            withStyle(SpanStyle(color = normalColor)) {
                append(name.substring(cursor, rStart))
            }
        }
        if (rEnd > rStart) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primaryColor)) {
                append(name.substring(rStart, rEnd))
            }
        }
        cursor = rEnd
    }
    if (cursor < name.length) {
        withStyle(SpanStyle(color = normalColor)) {
            append(name.substring(cursor))
        }
    }
}

/**
 * Full-screen searchable Slash Command Picker modal bottom sheet opened via "Show all" from the inline `/` menu.
 *
 * Designed for environments with a large number of installed skills and prompts: supports subsequence search
 * (`prw` -> `/pr-watch`), kind and source/plugin filter chips, full descriptions, host file inspection, and
 * one-tap command invocation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlashCommandPickerSheet(
    commands: List<SlashCommand>,
    initialQuery: String = "",
    onDismiss: () -> Unit,
    onSelect: (SlashCommand) -> Unit,
    onViewFile: ((HostFileTarget) -> Unit)? = null,
    onOpenCustomizations: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        SlashCommandPickerSheetContent(
            commands = commands,
            initialQuery = initialQuery,
            onDismiss = onDismiss,
            onSelect = onSelect,
            onViewFile = onViewFile,
            onOpenCustomizations = onOpenCustomizations,
        )
    }
}

@Composable
fun SlashCommandPickerSheetContent(
    commands: List<SlashCommand>,
    initialQuery: String = "",
    onDismiss: () -> Unit,
    onSelect: (SlashCommand) -> Unit,
    onViewFile: ((HostFileTarget) -> Unit)? = null,
    onOpenCustomizations: (() -> Unit)? = null,
) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var selectedKindName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSource by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedKind = selectedKindName?.let { name ->
        SlashCommandKind.entries.firstOrNull { it.name == name }
    }
    val sources = commands.mapNotNull { it.source }.distinct()
    val matches = SlashCommands.filterCommands(
        query = query,
        commands = commands,
        kindFilter = selectedKind,
        sourceFilter = selectedSource,
    )

    val skillCount = commands.count { it.kind == SlashCommandKind.SKILL }
    val promptCount = commands.count { it.kind == SlashCommandKind.PROMPT }
    val builtInCount = commands.count { it.kind == SlashCommandKind.BUILT_IN }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .testTag("slash_picker_sheet"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Slash commands",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "${matches.size} of ${commands.size} commands",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpenCustomizations != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenCustomizations()
                        },
                        modifier = Modifier.testTag("slash_picker_open_customizations"),
                    ) {
                        Text("Customizations")
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("slash_picker_close")) {
                    Text("Done")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(120) },
            placeholder = { Text("Search slash commands (e.g. prw for /pr-watch)…") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }, modifier = Modifier.testTag("slash_picker_search_clear")) {
                        Icon(AppIcons.Clear, contentDescription = "Clear search")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("slash_picker_search"),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedKind == null && selectedSource == null,
                onClick = {
                    selectedKindName = null
                    selectedSource = null
                },
                label = { Text("All (${commands.size})") },
                modifier = Modifier.testTag("slash_picker_filter_ALL"),
            )
            if (skillCount > 0) {
                FilterChip(
                    selected = selectedKind == SlashCommandKind.SKILL,
                    onClick = {
                        selectedKindName = if (selectedKind == SlashCommandKind.SKILL) null else SlashCommandKind.SKILL.name
                    },
                    leadingIcon = {
                        Icon(AppIcons.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("Skills ($skillCount)") },
                    modifier = Modifier.testTag("slash_picker_filter_SKILL"),
                )
            }
            if (promptCount > 0) {
                FilterChip(
                    selected = selectedKind == SlashCommandKind.PROMPT,
                    onClick = {
                        selectedKindName = if (selectedKind == SlashCommandKind.PROMPT) null else SlashCommandKind.PROMPT.name
                    },
                    leadingIcon = {
                        Icon(AppIcons.BookOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("Prompts ($promptCount)") },
                    modifier = Modifier.testTag("slash_picker_filter_PROMPT"),
                )
            }
            if (builtInCount > 0) {
                FilterChip(
                    selected = selectedKind == SlashCommandKind.BUILT_IN,
                    onClick = {
                        selectedKindName = if (selectedKind == SlashCommandKind.BUILT_IN) null else SlashCommandKind.BUILT_IN.name
                    },
                    leadingIcon = {
                        Icon(AppIcons.Slash, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("Built-in ($builtInCount)") },
                    modifier = Modifier.testTag("slash_picker_filter_BUILT_IN"),
                )
            }
            for (src in sources) {
                FilterChip(
                    selected = selectedSource == src,
                    onClick = {
                        selectedSource = if (selectedSource == src) null else src
                    },
                    label = { Text(src) },
                    modifier = Modifier.testTag("slash_picker_source_$src"),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .testTag("slash_picker_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (matches.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (query.isNotBlank()) {
                            "No slash commands match \"$query\"."
                        } else {
                            "No slash commands available."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .testTag("slash_picker_empty"),
                    )
                }
            } else {
                items(matches, key = { it.command.name }) { match ->
                    val cmd = match.command
                    val fileTarget = cmd.uri?.let { HostFileTarget.of(it) }
                    val primaryColor = MaterialTheme.colorScheme.primary
                    val normalColor = MaterialTheme.colorScheme.onSurface
                    val nameText = formatSlashCommandName(match, primaryColor, normalColor)

                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(cmd)
                                onDismiss()
                            }
                            .testTag("slash_picker_item_${cmd.name}"),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = nameText,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    cmd.argumentHint?.let { hint ->
                                        Text(
                                            text = hint,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    val badgeText = cmd.source ?: when (cmd.kind) {
                                        SlashCommandKind.SKILL -> "Skill"
                                        SlashCommandKind.PROMPT -> "Prompt"
                                        SlashCommandKind.BUILT_IN -> "Built-in"
                                    }
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ) {
                                        Text(
                                            text = badgeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (fileTarget != null && onViewFile != null) {
                                        TextButton(
                                            onClick = {
                                                onDismiss()
                                                onViewFile(fileTarget)
                                            },
                                            modifier = Modifier.testTag("slash_picker_view_${cmd.name}"),
                                        ) {
                                            Text("View file", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            cmd.description?.let { desc ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
