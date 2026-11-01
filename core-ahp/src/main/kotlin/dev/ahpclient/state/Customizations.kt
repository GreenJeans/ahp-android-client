package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.AgentCustomization
import com.microsoft.agenthostprotocol.generated.ChildCustomization
import com.microsoft.agenthostprotocol.generated.ChildCustomizationAgent
import com.microsoft.agenthostprotocol.generated.ChildCustomizationHook
import com.microsoft.agenthostprotocol.generated.ChildCustomizationMcpServer
import com.microsoft.agenthostprotocol.generated.ChildCustomizationPrompt
import com.microsoft.agenthostprotocol.generated.ChildCustomizationRule
import com.microsoft.agenthostprotocol.generated.ChildCustomizationSkill
import com.microsoft.agenthostprotocol.generated.Customization
import com.microsoft.agenthostprotocol.generated.CustomizationDirectory
import com.microsoft.agenthostprotocol.generated.CustomizationEnablement
import com.microsoft.agenthostprotocol.generated.CustomizationLoadState
import com.microsoft.agenthostprotocol.generated.CustomizationLoadStateDegraded
import com.microsoft.agenthostprotocol.generated.CustomizationLoadStateError
import com.microsoft.agenthostprotocol.generated.CustomizationLoadStateLoaded
import com.microsoft.agenthostprotocol.generated.CustomizationLoadStateLoading
import com.microsoft.agenthostprotocol.generated.CustomizationMcpServer
import com.microsoft.agenthostprotocol.generated.CustomizationPlugin
import com.microsoft.agenthostprotocol.generated.HookCustomization
import com.microsoft.agenthostprotocol.generated.McpServerCustomization
import com.microsoft.agenthostprotocol.generated.McpServerState
import com.microsoft.agenthostprotocol.generated.McpServerStateAuthRequired
import com.microsoft.agenthostprotocol.generated.McpServerStateError
import com.microsoft.agenthostprotocol.generated.McpServerStateReady
import com.microsoft.agenthostprotocol.generated.McpServerStateStarting
import com.microsoft.agenthostprotocol.generated.McpServerStateStopped
import com.microsoft.agenthostprotocol.generated.PromptCustomization
import com.microsoft.agenthostprotocol.generated.RootState
import com.microsoft.agenthostprotocol.generated.RuleCustomization
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SkillCustomization
import com.microsoft.agenthostprotocol.generated.ToolDefinition

/** Where a slash command comes from: an installed skill, a reusable prompt file, or a built-in client command. */
enum class SlashCommandKind {
    SKILL,
    PROMPT,
    BUILT_IN,
}

/**
 * A slash command the person can invoke in the chat composer by typing `/name`.
 *
 * Skills and prompts are sent to the host as the leading `/name` token of the user's message; built-in commands can also open
 * client sheets or switch chat controls locally.
 */
data class SlashCommand(
    val name: String,
    val description: String? = null,
    val argumentHint: String? = null,
    val kind: SlashCommandKind = SlashCommandKind.SKILL,
    val uri: String? = null,
    val source: String? = null,
) {
    /** The command name prefixed with `/`, e.g. `/skill-eval`. */
    val slashName: String get() = "/$name"
}

/**
 * One matching [SlashCommand] for the current `/` query in the composer.
 *
 * [highlightStart] and [highlightEnd] mark the character range within [SlashCommand.name] that matched the query so the UI can
 * bold the matched span just like the VS Code Agents panel.
 */
data class SlashCommandMatch(
    val command: SlashCommand,
    val highlightStart: Int = 0,
    val highlightEnd: Int = 0,
)

/**
 * The slash command currently active at the start of the composer message, along with any trailing [arguments] text after the command.
 */
data class ActiveSlashCommand(
    val command: SlashCommand,
    val arguments: String,
)

/**
 * An inline `@agent` mention query detected in the composer text, with matching [AgentChoice]s and the token range to replace on pick.
 */
data class AgentMentionQuery(
    val query: String,
    val tokenStart: Int,
    val tokenEnd: Int,
    val matches: List<AgentChoice>,
)

/**
 * Categories shown in the environment Customizations sheet, matching the VS Code Agents panel.
 */
enum class CustomizationCategory(val label: String) {
    PLUGINS("Plugins"),
    MCP_SERVERS("MCP Servers"),
    SKILLS("Skills"),
    INSTRUCTIONS("Instructions"),
    AGENTS("Agents"),
    HOOKS("Hooks"),
    TOOLS("Tools"),
}

/**
 * One item inside a [CustomizationCategory] for the environment Customizations inspector.
 */
data class CustomizationEntry(
    val id: String,
    val category: CustomizationCategory,
    val name: String,
    val description: String? = null,
    val uri: String? = null,
    val source: String? = null,
    val enabled: Boolean = true,
    val userInvokable: Boolean = true,
    val statusLabel: String? = null,
    val isError: Boolean = false,
    val isReady: Boolean = false,
    val argumentHint: String? = null,
    val badges: List<String> = emptyList(),
) {
    /** Whether this customization is backed by a host `file://` URI that can be opened in the file viewer. */
    val canViewFile: Boolean get() = uri?.let { HostFileTarget.of(it) } != null
}

/**
 * The complete environment customizations view for a session: counts and items across Plugins, MCP Servers, Skills, Instructions,
 * Agents, Hooks, and Tools. Pure, bounded, and tested without a phone.
 */
data class CustomizationsView(
    val entries: List<CustomizationEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()
    val totalCount: Int get() = entries.size

    fun countOf(category: CustomizationCategory): Int =
        entries.count { it.category == category }

    fun entriesIn(category: CustomizationCategory?, query: String = ""): List<CustomizationEntry> {
        val q = query.trim().lowercase()
        return entries.filter { entry ->
            (category == null || entry.category == category) &&
                (q.isEmpty() ||
                    entry.name.lowercase().contains(q) ||
                    entry.description?.lowercase()?.contains(q) == true ||
                    entry.source?.lowercase()?.contains(q) == true ||
                    entry.statusLabel?.lowercase()?.contains(q) == true ||
                    entry.badges.any { it.lowercase().contains(q) })
        }
    }

    companion object {
        val EMPTY = CustomizationsView()
    }
}

/**
 * Extracts user-invokable slash commands and environment customizations from a session (and its provider in [RootState]),
 * and matches `/` and `@` queries in the chat composer.
 */
object SlashCommands {
    const val MAX_COMMANDS = 200
    const val MAX_SUGGESTIONS = 12
    const val MAX_ENTRIES_PER_CATEGORY = 250
    private const val MAX_NAME = 80
    private const val MAX_DESCRIPTION = 240
    private const val MAX_SOURCE = 80
    private const val MAX_BADGE = 60
    private const val MAX_BADGES = 4

    /** Built-in client slash commands available alongside the session's installed skills and prompts. */
    fun builtInCommands(
        canFork: Boolean = false,
        canSideChat: Boolean = false,
    ): List<SlashCommand> = buildList {
        add(
            SlashCommand(
                name = "customizations",
                description = "Inspect plugins, MCP servers, skills, instructions, agents, hooks, and tools",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        add(
            SlashCommand(
                name = "skills",
                description = "Browse and search installed skills for this session",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        add(
            SlashCommand(
                name = "skills list",
                description = "List all available skills",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        add(
            SlashCommand(
                name = "skills info",
                description = "Show details for a skill",
                argumentHint = "<skill-name>",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        add(
            SlashCommand(
                name = "mcp",
                description = "Inspect MCP servers and their status",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        add(
            SlashCommand(
                name = "tools",
                description = "Inspect server tools available to the agent",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        add(
            SlashCommand(
                name = "agent",
                description = "Switch the custom agent for this chat",
                argumentHint = "<agent-name | default>",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        add(
            SlashCommand(
                name = "model",
                description = "Switch the model for this chat",
                argumentHint = "<model-name>",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        add(
            SlashCommand(
                name = "clear",
                description = "Start over and clear all turns in this chat",
                kind = SlashCommandKind.BUILT_IN,
            ),
        )
        if (canFork) {
            add(
                SlashCommand(
                    name = "fork",
                    description = "Branch this conversation into a new forked chat tab",
                    argumentHint = "[message]",
                    kind = SlashCommandKind.BUILT_IN,
                ),
            )
        }
        if (canSideChat) {
            add(
                SlashCommand(
                    name = "side",
                    description = "Ask in a side chat anchored to the current turn",
                    argumentHint = "[question]",
                    kind = SlashCommandKind.BUILT_IN,
                ),
            )
        }
    }

    /**
     * All user-invokable slash commands for [session]: installed skills and prompts from enabled plugins/directories first,
     * followed by built-in client commands whose names do not collide with a host skill or prompt.
     */
    fun of(
        session: SessionState?,
        root: RootState? = null,
        canFork: Boolean = false,
        canSideChat: Boolean = false,
        includeBuiltIns: Boolean = true,
    ): List<SlashCommand> {
        val containers = combinedCustomizations(session, root)
        val hostCommands = containers
            .flatMap { container ->
                when (container) {
                    is CustomizationPlugin -> if (enabled(container.value.enablement)) {
                        commandsIn(container.value.children, cleanText(container.value.name, MAX_SOURCE))
                    } else {
                        emptyList()
                    }
                    is CustomizationDirectory -> if (container.value.enabled) {
                        commandsIn(container.value.children, cleanText(container.value.name, MAX_SOURCE))
                    } else {
                        emptyList()
                    }
                    else -> emptyList()
                }
            }
            .distinctBy { it.name.lowercase() }
            .take(MAX_COMMANDS)

        if (!includeBuiltIns) return hostCommands
        val taken = hostCommands.mapTo( HashSet() ) { it.name.lowercase() }
        val builtIns = builtInCommands(canFork = canFork, canSideChat = canSideChat)
            .filterNot { it.name.lowercase() in taken }
        return (hostCommands + builtIns).take(MAX_COMMANDS)
    }

    /**
     * Matches composer [text] against [commands] when the person is typing a `/` command.
     *
     * Returns an empty list when [text] does not start with `/`, or when the person has already completed a command token and moved
     * on to typing its arguments after a space (unless a multi-word command like `/skills list` still matches).
     */
    fun match(
        text: String,
        commands: List<SlashCommand>,
        limit: Int = MAX_SUGGESTIONS,
    ): List<SlashCommandMatch> {
        if (commands.isEmpty() || !text.startsWith("/") || text.contains('\n') || text.contains('\r')) return emptyList()
        val afterSlash = text.substring(1)
        val hasSpace = afterSlash.any { it.isWhitespace() }
        val query = afterSlash.trim().lowercase()

        if (hasSpace) {
            // Once a space is typed, only keep the popup open if a multi-word command (e.g. "/skills list") still matches the prefix.
            val multiWordMatches = commands.mapNotNull { cmd ->
                val nameLower = cmd.name.lowercase()
                if (' ' in nameLower && nameLower.startsWith(query) && nameLower != query) {
                    SlashCommandMatch(cmd, highlightStart = 0, highlightEnd = query.length.coerceAtMost(cmd.name.length))
                } else {
                    null
                }
            }
            return multiWordMatches.take(limit)
        }

        if (query.isEmpty()) {
            return commands.take(limit).map { SlashCommandMatch(it, highlightStart = 0, highlightEnd = 0) }
        }

        val prefixMatches = mutableListOf<SlashCommandMatch>()
        val substringMatches = mutableListOf<SlashCommandMatch>()
        val descriptionMatches = mutableListOf<SlashCommandMatch>()

        for (cmd in commands) {
            val nameLower = cmd.name.lowercase()
            val idx = nameLower.indexOf(query)
            when {
                idx == 0 -> prefixMatches.add(
                    SlashCommandMatch(cmd, highlightStart = 0, highlightEnd = query.length),
                )
                idx > 0 -> substringMatches.add(
                    SlashCommandMatch(cmd, highlightStart = idx, highlightEnd = idx + query.length),
                )
                cmd.description?.lowercase()?.contains(query) == true -> descriptionMatches.add(
                    SlashCommandMatch(cmd, highlightStart = 0, highlightEnd = 0),
                )
            }
        }

        return (prefixMatches + substringMatches + descriptionMatches).take(limit)
    }

    /**
     * Detects when [text] starts with a known `/command` from [commands] (longest command name wins, so `/skills info foo`
     * matches `skills info` before `skills`). Returns null when [text] does not begin with a recognized command token.
     */
    fun active(
        text: String,
        commands: List<SlashCommand>,
    ): ActiveSlashCommand? {
        val trimmedStart = text.trimStart()
        if (!trimmedStart.startsWith("/")) return null
        val body = trimmedStart.substring(1)
        if (body.isEmpty()) return null

        val sorted = commands.sortedByDescending { it.name.length }
        for (cmd in sorted) {
            if (body.startsWith(cmd.name, ignoreCase = true)) {
                val rest = body.substring(cmd.name.length)
                if (rest.isEmpty() || rest[0].isWhitespace()) {
                    return ActiveSlashCommand(
                        command = cmd,
                        arguments = rest.trimStart(),
                    )
                }
            }
        }
        return null
    }

    /**
     * Detects an inline `@agent` mention token at the start or end of [text] so the person can pick a custom agent with `@`.
     */
    fun matchAgentMention(
        text: String,
        agents: List<AgentChoice>,
        limit: Int = 8,
    ): AgentMentionQuery? {
        if (agents.isEmpty() || text.isEmpty()) return null
        // Match either a leading `@query` (no space yet) or a trailing ` @query` token at the end of the text.
        val atIndex = when {
            text.startsWith("@") && text.none { it.isWhitespace() } -> 0
            else -> {
                val lastAt = text.lastIndexOf('@')
                if (lastAt > 0 && text[lastAt - 1].isWhitespace() && text.substring(lastAt + 1).none { it.isWhitespace() }) {
                    lastAt
                } else {
                    -1
                }
            }
        }
        if (atIndex < 0) return null
        val query = text.substring(atIndex + 1).lowercase()
        val matches = agents.filter { agent ->
            query.isEmpty() ||
                agent.name.lowercase().contains(query) ||
                agent.description?.lowercase()?.contains(query) == true
        }.take(limit)
        if (matches.isEmpty()) return null
        return AgentMentionQuery(
            query = query,
            tokenStart = atIndex,
            tokenEnd = text.length,
            matches = matches,
        )
    }

    /** Removes the matched `@agent` token from [text] after the person picks an agent from the mention popup. */
    fun stripAgentMention(text: String, mention: AgentMentionQuery): String {
        val before = text.substring(0, mention.tokenStart.coerceIn(0, text.length)).trimEnd()
        val after = text.substring(mention.tokenEnd.coerceIn(0, text.length)).trimStart()
        return when {
            before.isEmpty() -> after
            after.isEmpty() -> before
            else -> "$before $after"
        }
    }

    /**
     * Builds the full [CustomizationsView] across Plugins, MCP Servers, Skills, Instructions, Agents, Hooks, and Tools for [session].
     */
    fun customizationsOf(
        session: SessionState?,
        root: RootState? = null,
    ): CustomizationsView {
        val containers = combinedCustomizations(session, root)
        val plugins = mutableListOf<CustomizationEntry>()
        val mcpServers = mutableListOf<CustomizationEntry>()
        val skills = mutableListOf<CustomizationEntry>()
        val instructions = mutableListOf<CustomizationEntry>()
        val agents = mutableListOf<CustomizationEntry>()
        val hooks = mutableListOf<CustomizationEntry>()
        val tools = mutableListOf<CustomizationEntry>()

        for (container in containers) {
            when (container) {
                is CustomizationPlugin -> {
                    val p = container.value
                    val isEnabled = enabled(p.enablement)
                    val sourceName = cleanText(p.name, MAX_SOURCE) ?: nameFromUri(p.uri, "Plugin")
                    if (plugins.size < MAX_ENTRIES_PER_CATEGORY) {
                        val loadStatus = loadStateLabel(p.load, isEnabled)
                        val isErr = p.load is CustomizationLoadStateError || p.load is CustomizationLoadStateDegraded
                        val isRdy = isEnabled && p.load is CustomizationLoadStateLoaded
                        val childCount = p.children?.size ?: 0
                        val badges = buildList {
                            p.version?.let { cleanText(it, MAX_BADGE) }?.let { add("v${it.removePrefix("v")}") }
                            enablementScopeLabel(p.enablement)?.let { add(it) }
                            if (childCount > 0) add("$childCount items")
                        }.take(MAX_BADGES)
                        val desc = loadStateDetail(p.load) ?: p.version?.let { "Version $it" }
                        plugins.add(
                            CustomizationEntry(
                                id = "plugin:${p.id.ifBlank { p.uri }}",
                                category = CustomizationCategory.PLUGINS,
                                name = sourceName,
                                description = cleanText(desc, MAX_DESCRIPTION),
                                uri = p.uri.takeIf { it.isNotBlank() },
                                enabled = isEnabled,
                                statusLabel = loadStatus,
                                isError = isErr,
                                isReady = isRdy,
                                badges = badges,
                            ),
                        )
                    }
                    collectChildren(
                        children = p.children,
                        containerEnabled = isEnabled,
                        source = sourceName,
                        mcpServers = mcpServers,
                        skills = skills,
                        instructions = instructions,
                        agents = agents,
                        hooks = hooks,
                    )
                }
                is CustomizationDirectory -> {
                    val d = container.value
                    val isEnabled = d.enabled
                    val sourceName = cleanText(d.name, MAX_SOURCE) ?: nameFromUri(d.uri, "Folder")
                    collectChildren(
                        children = d.children,
                        containerEnabled = isEnabled,
                        source = sourceName,
                        mcpServers = mcpServers,
                        skills = skills,
                        instructions = instructions,
                        agents = agents,
                        hooks = hooks,
                    )
                }
                is CustomizationMcpServer -> {
                    if (mcpServers.size < MAX_ENTRIES_PER_CATEGORY) {
                        mcpServers.add(mcpEntry(container.value, containerEnabled = true, source = null))
                    }
                }
                else -> Unit
            }
        }

        for (tool in session?.serverTools.orEmpty()) {
            if (tools.size >= MAX_ENTRIES_PER_CATEGORY) break
            val name = cleanText(tool.name, MAX_NAME) ?: continue
            if (tools.any { it.name.equals(name, ignoreCase = true) }) continue
            tools.add(toolEntry(tool, name))
        }

        val all = buildList {
            addAll(plugins.distinctBy { it.id })
            addAll(mcpServers.distinctBy { it.id })
            addAll(skills.distinctBy { it.name.lowercase() })
            addAll(instructions.distinctBy { it.id })
            addAll(agents.distinctBy { it.id })
            addAll(hooks.distinctBy { it.id })
            addAll(tools)
        }
        return CustomizationsView(all)
    }

    private fun collectChildren(
        children: List<ChildCustomization>?,
        containerEnabled: Boolean,
        source: String?,
        mcpServers: MutableList<CustomizationEntry>,
        skills: MutableList<CustomizationEntry>,
        instructions: MutableList<CustomizationEntry>,
        agents: MutableList<CustomizationEntry>,
        hooks: MutableList<CustomizationEntry>,
    ) {
        for (child in children.orEmpty()) {
            when (child) {
                is ChildCustomizationSkill -> if (skills.size < MAX_ENTRIES_PER_CATEGORY) {
                    val s = child.value
                    val name = skillNameOf(s.name, s.uri)
                    val isEnabled = containerEnabled && s.enabled != false
                    val invokable = s.disableUserInvocation != true
                    val hint = HostExtras.argumentHint(s.meta)
                    val status = when {
                        !isEnabled -> "Disabled"
                        !invokable -> "Model only"
                        else -> null
                    }
                    val badges = buildList {
                        if (isEnabled && invokable) add("/$name")
                        if (s.disableModelInvocation == true) add("Manual only")
                        if (!invokable) add("Model only")
                    }.take(MAX_BADGES)
                    skills.add(
                        CustomizationEntry(
                            id = "skill:${s.uri.ifBlank { s.id.ifBlank { name } }}",
                            category = CustomizationCategory.SKILLS,
                            name = name,
                            description = cleanText(s.description, MAX_DESCRIPTION),
                            uri = s.uri.takeIf { it.isNotBlank() },
                            source = source,
                            enabled = isEnabled,
                            userInvokable = invokable,
                            statusLabel = status,
                            isReady = isEnabled,
                            argumentHint = hint,
                            badges = badges,
                        ),
                    )
                }
                is ChildCustomizationPrompt -> if (instructions.size < MAX_ENTRIES_PER_CATEGORY) {
                    val p = child.value
                    val name = promptNameOf(p.name, p.uri)
                    val isEnabled = containerEnabled && p.enabled != false
                    val hint = HostExtras.argumentHint(p.meta)
                    val badges = buildList {
                        add("Prompt")
                        if (isEnabled) add("/$name")
                    }
                    instructions.add(
                        CustomizationEntry(
                            id = "prompt:${p.uri.ifBlank { p.id.ifBlank { name } }}",
                            category = CustomizationCategory.INSTRUCTIONS,
                            name = name,
                            description = cleanText(p.description, MAX_DESCRIPTION),
                            uri = p.uri.takeIf { it.isNotBlank() },
                            source = source,
                            enabled = isEnabled,
                            userInvokable = true,
                            statusLabel = if (!isEnabled) "Disabled" else "Prompt",
                            isReady = isEnabled,
                            argumentHint = hint,
                            badges = badges,
                        ),
                    )
                }
                is ChildCustomizationRule -> if (instructions.size < MAX_ENTRIES_PER_CATEGORY) {
                    val r = child.value
                    val name = ruleNameOf(r.name, r.uri)
                    val isEnabled = containerEnabled && r.enabled != false
                    val status = when {
                        !isEnabled -> "Disabled"
                        r.alwaysApply == true -> "Always apply"
                        else -> null
                    }
                    val badges = buildList {
                        if (r.alwaysApply == true) add("Always apply")
                        r.globs.orEmpty().mapNotNull { cleanText(it, MAX_BADGE) }.take(2).forEach { add(it) }
                    }.take(MAX_BADGES)
                    instructions.add(
                        CustomizationEntry(
                            id = "rule:${r.uri.ifBlank { r.id.ifBlank { name } }}",
                            category = CustomizationCategory.INSTRUCTIONS,
                            name = name,
                            description = cleanText(r.description, MAX_DESCRIPTION),
                            uri = r.uri.takeIf { it.isNotBlank() },
                            source = source,
                            enabled = isEnabled,
                            userInvokable = false,
                            statusLabel = status,
                            isReady = isEnabled,
                            badges = badges,
                        ),
                    )
                }
                is ChildCustomizationAgent -> if (agents.size < MAX_ENTRIES_PER_CATEGORY) {
                    val a = child.value
                    val name = CustomAgents.nameOf(a.uri, emptyList())?.takeIf { a.name.isBlank() }
                        ?: cleanText(a.name, MAX_NAME)
                        ?: nameFromUri(a.uri, "agent")
                    val isEnabled = containerEnabled && a.enabled != false
                    val invokable = a.disableUserInvocation != true
                    val status = when {
                        !isEnabled -> "Disabled"
                        !invokable -> "Subagent only"
                        else -> null
                    }
                    val badges = buildList {
                        if (isEnabled && invokable) add("@$name")
                        a.model?.let { cleanText(it, MAX_BADGE) }?.let { add(it) }
                        a.tools?.takeIf { it.isNotEmpty() }?.let { add("${it.size} tools") }
                    }.take(MAX_BADGES)
                    agents.add(
                        CustomizationEntry(
                            id = "agent:${a.uri.ifBlank { a.id.ifBlank { name } }}",
                            category = CustomizationCategory.AGENTS,
                            name = name,
                            description = cleanText(a.description, MAX_DESCRIPTION),
                            uri = a.uri.takeIf { it.isNotBlank() },
                            source = source,
                            enabled = isEnabled,
                            userInvokable = invokable,
                            statusLabel = status,
                            isReady = isEnabled,
                            badges = badges,
                        ),
                    )
                }
                is ChildCustomizationHook -> if (hooks.size < MAX_ENTRIES_PER_CATEGORY) {
                    val h = child.value
                    val name = cleanText(h.name, MAX_NAME) ?: nameFromUri(h.uri, "hook")
                    val isEnabled = containerEnabled && h.enabled != false
                    hooks.add(
                        CustomizationEntry(
                            id = "hook:${h.uri.ifBlank { h.id.ifBlank { name } }}",
                            category = CustomizationCategory.HOOKS,
                            name = name,
                            uri = h.uri.takeIf { it.isNotBlank() },
                            source = source,
                            enabled = isEnabled,
                            userInvokable = false,
                            statusLabel = if (isEnabled) "Active" else "Disabled",
                            isReady = isEnabled,
                        ),
                    )
                }
                is ChildCustomizationMcpServer -> if (mcpServers.size < MAX_ENTRIES_PER_CATEGORY) {
                    mcpServers.add(mcpEntry(child.value, containerEnabled = containerEnabled, source = source))
                }
                else -> Unit
            }
        }
    }

    private fun mcpEntry(
        m: McpServerCustomization,
        containerEnabled: Boolean,
        source: String?,
    ): CustomizationEntry {
        val name = cleanText(m.name, MAX_NAME) ?: nameFromUri(m.uri, "mcp")
        val isEnabled = containerEnabled && enabled(m.enablement)
        val (status, isErr, isRdy, detail) = mcpStateInfo(m.state, isEnabled)
        val badges = buildList {
            enablementScopeLabel(m.enablement)?.let { add(it) }
        }
        return CustomizationEntry(
            id = "mcp:${m.uri.ifBlank { m.id.ifBlank { name } }}",
            category = CustomizationCategory.MCP_SERVERS,
            name = name,
            description = cleanText(detail, MAX_DESCRIPTION),
            uri = m.uri.takeIf { it.isNotBlank() },
            source = source,
            enabled = isEnabled,
            userInvokable = false,
            statusLabel = status,
            isError = isErr,
            isReady = isRdy,
            badges = badges,
        )
    }

    private fun toolEntry(tool: ToolDefinition, name: String): CustomizationEntry {
        val title = cleanText(tool.title, MAX_NAME)?.takeIf { !it.equals(name, ignoreCase = true) }
        val desc = cleanText(tool.description, MAX_DESCRIPTION) ?: title
        val ann = tool.annotations
        val badges = buildList {
            if (ann?.readOnlyHint == true) add("Read-only")
            if (ann?.destructiveHint == true) add("Destructive")
            if (ann?.openWorldHint == true) add("Open-world")
        }.take(MAX_BADGES)
        return CustomizationEntry(
            id = "tool:$name",
            category = CustomizationCategory.TOOLS,
            name = name,
            description = desc,
            userInvokable = false,
            statusLabel = title,
            isReady = true,
            badges = badges,
        )
    }

    private fun commandsIn(children: List<ChildCustomization>?, source: String?): List<SlashCommand> =
        children.orEmpty().mapNotNull { child ->
            when (child) {
                is ChildCustomizationSkill -> {
                    val s = child.value
                    if (s.enabled == false || s.disableUserInvocation == true) {
                        null
                    } else {
                        val name = skillNameOf(s.name, s.uri)
                        SlashCommand(
                            name = name,
                            description = cleanText(s.description, MAX_DESCRIPTION),
                            argumentHint = HostExtras.argumentHint(s.meta),
                            kind = SlashCommandKind.SKILL,
                            uri = s.uri.takeIf { it.isNotBlank() },
                            source = source,
                        )
                    }
                }
                is ChildCustomizationPrompt -> {
                    val p = child.value
                    if (p.enabled == false) {
                        null
                    } else {
                        val name = promptNameOf(p.name, p.uri)
                        SlashCommand(
                            name = name,
                            description = cleanText(p.description, MAX_DESCRIPTION),
                            argumentHint = HostExtras.argumentHint(p.meta),
                            kind = SlashCommandKind.PROMPT,
                            uri = p.uri.takeIf { it.isNotBlank() },
                            source = source,
                        )
                    }
                }
                else -> null
            }
        }

    internal fun combinedCustomizations(session: SessionState?, root: RootState?): List<Customization> {
        val fromSession = session?.customizations.orEmpty()
        val provider = session?.provider
        val fromAgent = if (provider != null) {
            root?.agents?.firstOrNull { it.provider == provider }?.customizations.orEmpty()
        } else {
            emptyList()
        }
        if (fromAgent.isEmpty()) return fromSession
        if (fromSession.isEmpty()) return fromAgent
        val seenKeys = fromSession.mapTo(HashSet()) { containerKey(it) }
        return fromSession + fromAgent.filterNot { containerKey(it) in seenKeys }
    }

    private fun containerKey(c: Customization): String = when (c) {
        is CustomizationPlugin -> "plugin:${c.value.uri.ifBlank { c.value.id }}"
        is CustomizationDirectory -> "dir:${c.value.uri.ifBlank { c.value.id }}"
        is CustomizationMcpServer -> "mcp:${c.value.uri.ifBlank { c.value.id }}"
        else -> c.hashCode().toString()
    }

    internal fun skillNameOf(rawName: String, uri: String): String {
        val cleaned = cleanCommandName(rawName)
        if (cleaned != null && !cleaned.equals("SKILL", ignoreCase = true) && !cleaned.equals("SKILL.md", ignoreCase = true)) {
            return cleaned
        }
        val path = uri.substringBefore('?').substringBefore('#').trimEnd('/', '\\')
        val segments = path.split('/', '\\').filter { it.isNotBlank() }
        val last = segments.lastOrNull().orEmpty()
        val candidate = if (last.equals("SKILL.md", ignoreCase = true) && segments.size >= 2) {
            segments[segments.size - 2]
        } else {
            last.removeSuffix(".md").removeSuffix(".skill")
        }
        return cleanCommandName(candidate) ?: "skill"
    }

    internal fun promptNameOf(rawName: String, uri: String): String {
        cleanCommandName(rawName)?.let { return it }
        val file = uri.substringBefore('?').substringBefore('#').trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        val base = file.removeSuffix(".md").removeSuffix(".prompt")
        return cleanCommandName(base) ?: "prompt"
    }

    private fun ruleNameOf(rawName: String, uri: String): String {
        cleanText(rawName, MAX_NAME)?.let { return it }
        val file = uri.substringBefore('?').substringBefore('#').trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        val base = file.removeSuffix(".md").removeSuffix(".instructions")
        return cleanText(base, MAX_NAME) ?: "instructions"
    }

    private fun nameFromUri(uri: String, fallback: String): String {
        val file = uri.substringBefore('?').substringBefore('#').trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return cleanText(file.removeSuffix(".md"), MAX_NAME) ?: fallback
    }

    private fun cleanCommandName(raw: String?): String? =
        raw?.filterNot { it.isISOControl() }
            ?.trim()
            ?.trimStart('/')
            ?.trim()
            ?.take(MAX_NAME)
            ?.takeIf { it.isNotEmpty() }

    private fun cleanText(raw: String?, max: Int): String? =
        raw?.replace(WHITESPACE_RUN, " ")
            ?.filterNot { it.isISOControl() }
            ?.trim()
            ?.take(max)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun enabled(decisions: List<CustomizationEnablement>?): Boolean =
        when (val first = decisions?.firstOrNull()) {
            is CustomizationEnablement.Session -> first.value.enabled
            is CustomizationEnablement.Workspace -> first.value.enabled
            is CustomizationEnablement.Global -> first.value.enabled
            else -> true
        }

    private fun enablementScopeLabel(decisions: List<CustomizationEnablement>?): String? =
        when (decisions?.firstOrNull()) {
            is CustomizationEnablement.Session -> "Session"
            is CustomizationEnablement.Workspace -> "Workspace"
            is CustomizationEnablement.Global -> "Global"
            else -> null
        }

    private fun loadStateLabel(load: CustomizationLoadState?, enabled: Boolean): String =
        if (!enabled) {
            "Disabled"
        } else {
            when (load) {
                is CustomizationLoadStateLoaded -> "Loaded"
                is CustomizationLoadStateLoading -> "Loading"
                is CustomizationLoadStateDegraded -> "Degraded"
                is CustomizationLoadStateError -> "Error"
                else -> "Enabled"
            }
        }

    private fun loadStateDetail(load: CustomizationLoadState?): String? =
        when (load) {
            is CustomizationLoadStateDegraded -> load.value.message
            is CustomizationLoadStateError -> load.value.message
            else -> null
        }

    private data class McpStatusInfo(
        val label: String,
        val isError: Boolean,
        val isReady: Boolean,
        val detail: String?,
    )

    private fun mcpStateInfo(state: McpServerState, enabled: Boolean): McpStatusInfo {
        if (!enabled) return McpStatusInfo("Disabled", isError = false, isReady = false, detail = null)
        return when (state) {
            is McpServerStateReady -> McpStatusInfo("Ready", isError = false, isReady = true, detail = null)
            is McpServerStateStarting -> McpStatusInfo("Starting", isError = false, isReady = false, detail = null)
            is McpServerStateStopped -> McpStatusInfo("Stopped", isError = false, isReady = false, detail = null)
            is McpServerStateAuthRequired -> McpStatusInfo("Auth required", isError = true, isReady = false, detail = state.value.description)
            is McpServerStateError -> McpStatusInfo("Error", isError = true, isReady = false, detail = state.value.error.message)
            else -> McpStatusInfo("Unknown", isError = false, isReady = false, detail = null)
        }
    }

    private val WHITESPACE_RUN = Regex("""[\r\n\t ]+""")
}
