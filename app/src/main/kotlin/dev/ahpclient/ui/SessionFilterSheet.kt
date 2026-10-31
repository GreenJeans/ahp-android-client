package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.FilterFacetOption
import dev.ahpclient.state.SessionFilter
import dev.ahpclient.state.SessionGrouping
import dev.ahpclient.state.SessionListModel
import dev.ahpclient.state.SessionOrdering
import dev.ahpclient.state.SessionShowScope

/**
 * Filter, ordering and grouping options for the sessions list, modeled on the VS Code Agents Panel filter menu.
 *
 * Every chip and switch row has at least a 48 dp touch target and announces its label and selected/checked state for TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionFilterSheet(
    model: SessionListModel,
    filter: SessionFilter,
    onFilterChange: (SessionFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp) },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = modifier.testTag("session_filter_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Filter sessions",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(
                    onClick = onReset,
                    enabled = !filter.isDefault,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reset_filters"),
                ) {
                    Text("Reset filters")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionHeader("Ordering & grouping")
            TileCard(shape = connectedShape(0, 3), color = card) {
                FilterTilePadding {
                    Text("Ordering", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SessionOrdering.entries.forEach { ordering ->
                            FilterChip(
                                selected = filter.ordering == ordering,
                                onClick = { onFilterChange(filter.copy(ordering = ordering)) },
                                label = { Text(ordering.label) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("order_${ordering.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(1, 3), color = card) {
                FilterTilePadding {
                    Text("Grouping", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SessionGrouping.entries.forEach { grouping ->
                            FilterChip(
                                selected = filter.grouping == grouping,
                                onClick = { onFilterChange(filter.copy(grouping = grouping)) },
                                label = { Text(grouping.label) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("group_${grouping.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(2, 3), color = card) {
                FilterTilePadding {
                    Text("Show", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SessionShowScope.entries.forEach { scope ->
                            FilterChip(
                                selected = filter.showScope == scope,
                                onClick = { onFilterChange(filter.copy(showScope = scope)) },
                                label = { Text(scope.label) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("show_scope_${scope.name.lowercase()}"),
                            )
                        }
                    }
                }
            }

            if (model.availableHosts.size > 1) {
                FacetSection(
                    title = "Environment",
                    allLabel = "All hosts",
                    options = model.availableHosts,
                    selected = filter.hostIds,
                    tagPrefix = "facet_host",
                    color = card,
                    onClear = { onFilterChange(filter.copy(hostIds = emptySet())) },
                    onToggle = { id -> onFilterChange(filter.toggleHost(id)) },
                )
            }

            if (model.availableWorkspaces.size > 1) {
                FacetSection(
                    title = "Workspace",
                    allLabel = "All workspaces",
                    options = model.availableWorkspaces,
                    selected = filter.workspaces,
                    tagPrefix = "facet_workspace",
                    color = card,
                    onClear = { onFilterChange(filter.copy(workspaces = emptySet())) },
                    onToggle = { id -> onFilterChange(filter.toggleWorkspace(id)) },
                )
            }

            if (model.availableProviders.size > 1) {
                FacetSection(
                    title = "Harness",
                    allLabel = "All harnesses",
                    options = model.availableProviders,
                    selected = filter.providers,
                    tagPrefix = "facet_provider",
                    color = card,
                    onClear = { onFilterChange(filter.copy(providers = emptySet())) },
                    onToggle = { id -> onFilterChange(filter.toggleProvider(id)) },
                )
            }

            if (model.availableInitiators.isNotEmpty()) {
                FacetSection(
                    title = "Created in",
                    allLabel = "Any origin",
                    options = model.availableInitiators,
                    selected = filter.initiators,
                    tagPrefix = "facet_initiator",
                    color = card,
                    onClear = { onFilterChange(filter.copy(initiators = emptySet())) },
                    onToggle = { id -> onFilterChange(filter.toggleInitiator(id)) },
                )
            }

            SectionHeader("Visibility")
            TileCard(shape = connectedShape(0, 3), color = card) {
                FilterTilePadding {
                    FilterSwitchRow(
                        label = "Show done sessions",
                        subtitle = "Completed idle sessions you have already read",
                        checked = filter.showDone,
                        tag = "filter_show_done",
                        onCheckedChange = { onFilterChange(filter.copy(showDone = it)) },
                    )
                }
            }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(1, 3), color = card) {
                FilterTilePadding {
                    val hiddenNote = if (!filter.showExternal && model.hiddenExternal > 0) {
                        "Background CLI, subagent & automation runs (${model.hiddenExternal} hidden)"
                    } else {
                        "Background CLI, subagent & automation runs"
                    }
                    FilterSwitchRow(
                        label = "Show external & spawned sessions",
                        subtitle = hiddenNote,
                        checked = filter.showExternal,
                        tag = "filter_show_external",
                        onCheckedChange = { onFilterChange(filter.copy(showExternal = it)) },
                    )
                }
            }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(2, 3), color = card) {
                FilterTilePadding {
                    val archivedNote = if (!filter.showArchived && model.hiddenArchived > 0) {
                        "Archived sessions (${model.hiddenArchived} hidden)"
                    } else {
                        "Archived sessions"
                    }
                    FilterSwitchRow(
                        label = "Show archived sessions",
                        subtitle = archivedNote,
                        checked = filter.showArchived,
                        tag = "filter_show_archived",
                        onCheckedChange = { onFilterChange(filter.copy(showArchived = it)) },
                    )
                }
            }
            }
        }
    }
}

private const val MAX_INITIAL_FACET_OPTIONS = 8

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetSection(
    title: String,
    allLabel: String,
    options: List<FilterFacetOption>,
    selected: Set<String>,
    tagPrefix: String,
    color: androidx.compose.ui.graphics.Color,
    onClear: () -> Unit,
    onToggle: (String) -> Unit,
) {
    var expanded by rememberSaveable(tagPrefix) { mutableStateOf(false) }
    val canCollapse = options.size > MAX_INITIAL_FACET_OPTIONS
    val visibleOptions = if (expanded || !canCollapse) {
        options
    } else {
        val head = options.take(MAX_INITIAL_FACET_OPTIONS)
        val headIds = head.mapTo(HashSet()) { it.id }
        head + options.drop(MAX_INITIAL_FACET_OPTIONS).filter { it.id in selected && it.id !in headIds }
    }
    SectionHeader(title)
    TileCard(color = color) {
        FilterTilePadding {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = selected.isEmpty(),
                    onClick = onClear,
                    label = { Text(allLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("${tagPrefix}_all"),
                )
                visibleOptions.forEach { option ->
                    FilterChip(
                        selected = option.id in selected,
                        onClick = { onToggle(option.id) },
                        label = {
                            Text(
                                "${option.label} (${option.count})",
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("${tagPrefix}_${option.id}"),
                    )
                }
            }
            if (canCollapse) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("${tagPrefix}_toggle_all"),
                ) {
                    Text(if (expanded) "Show fewer" else "Show all (${options.size})")
                }
            }
        }
    }
}

@Composable
private fun FilterSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun FilterTilePadding(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
}
