package dev.ahpclient.tunnel

import dev.ahpclient.platform.AtomicBoolean
import dev.ahpclient.platform.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Built-in offline demo host (`ws://localhost:9187/demo`) that speaks Agent Host Protocol (AHP) 1.0 in memory.
 *
 * Used by Apple App Review / Google Play reviewers and new users exploring Hostline before setting up a live
 * VS Code Remote Tunnel or `ahpd` server. Because it implements [TextSocket] below [AhpConnection], every store,
 * reducer, and UI screen runs through the real production code path.
 */
object DemoAhpHost {
    const val URL = "ws://localhost:9187/demo"
    const val NAME = "Demo Workspace"
    const val PRIMARY_SESSION_URI = "ahp-session:/demo-auth"
}

class DemoSocketOpener(
    private val delegate: SocketOpener,
) : SocketOpener {
    override suspend fun open(url: String, headers: Map<String, String>): TextSocket {
        if (url == DemoAhpHost.URL) return DemoAhpSocket()
        return delegate.open(url, headers)
    }
}

class DemoAhpSocket : TextSocket {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val seq = AtomicLong(500L)
    private var clientId: String = "demo-client"

    override suspend fun receive(): String = try {
        inbox.receive()
    } catch (_: ClosedReceiveChannelException) {
        throw SocketClosedException(TextSocket.NORMAL_CLOSURE, "demo closed")
    }

    override fun send(text: String): Boolean {
        if (closed.get()) return false
        val frame = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return true
        }
        val method = (frame["method"] as? JsonPrimitive)?.contentOrNull ?: return true
        val id = (frame["id"] as? JsonPrimitive)?.longOrNull
        val params = frame["params"] as? JsonObject ?: JsonObject(emptyMap())
        handleRequest(method, id, params)
        return true
    }

    override fun close(code: Int, reason: String) {
        if (closed.compareAndSet(false, true)) {
            inbox.close()
        }
    }

    private fun reply(id: Long?, resultJson: String) {
        if (id == null) return
        inbox.trySend("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")
    }

    private fun handleRequest(method: String, id: Long?, params: JsonObject) {
        when (method) {
            "initialize" -> {
                (params["clientId"] as? JsonPrimitive)?.contentOrNull?.let { clientId = it }
                reply(id, initializeResultJson(seq.get()))
            }
            "reconnect" -> {
                reply(id, """{"type":"replay"}""")
            }
            "listSessions" -> {
                reply(id, listSessionsResultJson())
            }
            "subscribe" -> {
                val channel = (params["channel"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                reply(id, subscribeResultJson(channel, seq.get()))
            }
            "resourceRead" -> {
                val uri = (params["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                reply(id, resourceReadResultJson(uri))
            }
            "fetchTurns" -> {
                reply(id, """{"turns":[],"hasMore":false}""")
            }
            "completions" -> {
                reply(
                    id,
                    """{"items":[
                        {"label":"/security-audit","insertText":"/security-audit ","detail":"Audits OAuth2, PKCE, JWT validation, and secret storage"},
                        {"label":"@SecurityAuditor","insertText":"@SecurityAuditor ","detail":"Reviews authentication boundaries and token rotation"}
                    ]}""",
                )
            }
            "resolveSessionConfig" -> {
                reply(id, """{"config":{"schema":{"type":"object","properties":{}},"values":{}}}""")
            }
            "sessionConfigCompletions" -> {
                reply(id, """{"values":[]}""")
            }
            "invokeChangesetOperation" -> {
                reply(id, """{}""")
            }
            "createSession", "createChat", "disposeSession", "disposeChat", "ping" -> {
                reply(id, """{}""")
            }
            "dispatchAction" -> {
                val channel = (params["channel"] as? JsonPrimitive)?.contentOrNull ?: return
                val clientSeq = (params["clientSeq"] as? JsonPrimitive)?.longOrNull ?: 1L
                val actionObj = params["action"] as? JsonObject ?: return
                val actionType = (actionObj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val echoedSeq = seq.incrementAndGet()
                val channelJson = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(channel))
                val clientIdJson = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(clientId))
                inbox.trySend(
                    """{"jsonrpc":"2.0","method":"action","params":{"channel":$channelJson,"serverSeq":$echoedSeq,"action":$actionObj,"origin":{"clientId":$clientIdJson,"clientSeq":$clientSeq}}}""",
                )
                if (actionType == "chat/turnStarted") {
                    val turnId = (actionObj["turnId"] as? JsonPrimitive)?.contentOrNull ?: "turn-demo"
                    val turnIdJson = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(turnId))
                    val partSeq = seq.incrementAndGet()
                    val replyMd = "Verified the updated PKCE `S256` challenge check and confirmed all **42 unit tests** in `:core-auth:test` pass with zero allocations."
                    val replyMdJson = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(replyMd))
                    inbox.trySend(
                        """{"jsonrpc":"2.0","method":"action","params":{"channel":$channelJson,"serverSeq":$partSeq,"action":{"type":"chat/responsePart","turnId":$turnIdJson,"part":{"kind":"markdown","id":"part-$partSeq","content":$replyMdJson}}}}""",
                    )
                    val doneSeq = seq.incrementAndGet()
                    inbox.trySend(
                        """{"jsonrpc":"2.0","method":"action","params":{"channel":$channelJson,"serverSeq":$doneSeq,"action":{"type":"chat/turnComplete","turnId":$turnIdJson,"duration":1840}}}""",
                    )
                } else if (actionType == "chat/toolCallConfirmed") {
                    val approved = (actionObj["approved"] as? JsonPrimitive)?.booleanOrNull == true
                    val turnId = (actionObj["turnId"] as? JsonPrimitive)?.contentOrNull ?: "t-mig-1"
                    val toolCallId = (actionObj["toolCallId"] as? JsonPrimitive)?.contentOrNull ?: "tc-approve-mig"
                    val turnIdJson = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(turnId))
                    val toolCallIdJson = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(toolCallId))
                    if (approved) {
                        val completeSeq = seq.incrementAndGet()
                        inbox.trySend(
                            """{"jsonrpc":"2.0","method":"action","params":{"channel":$channelJson,"serverSeq":$completeSeq,"action":{"type":"chat/toolCallComplete","turnId":$turnIdJson,"toolCallId":$toolCallIdJson,"result":{"success":true,"pastTenseMessage":"Applied strict S256 PKCE enforcement in `src/auth/SessionGuard.kt`"}}}}""",
                        )
                        val doneSeq = seq.incrementAndGet()
                        inbox.trySend(
                            """{"jsonrpc":"2.0","method":"action","params":{"channel":$channelJson,"serverSeq":$doneSeq,"action":{"type":"chat/turnComplete","turnId":$turnIdJson,"duration":1260}}}""",
                        )
                    } else {
                        val doneSeq = seq.incrementAndGet()
                        inbox.trySend(
                            """{"jsonrpc":"2.0","method":"action","params":{"channel":$channelJson,"serverSeq":$doneSeq,"action":{"type":"chat/turnComplete","turnId":$turnIdJson,"duration":420}}}""",
                        )
                    }
                }
            }
        }
    }

    private fun initializeResultJson(currentSeq: Long): String = """{
        "protocolVersion": "1.0.0",
        "serverSeq": $currentSeq,
        "snapshots": [
            {
                "resource": "ahp-root://",
                "fromSeq": $currentSeq,
                "state": {
                    "agents": [
                        {
                            "provider": "copilotcli",
                            "displayName": "GitHub Copilot",
                            "description": "Coding agent connected to workspace",
                            "models": [
                                {"id": "claude-sonnet-4.5", "provider": "copilotcli", "name": "Claude Sonnet 4.5", "supportsVision": true},
                                {"id": "gpt-5", "provider": "copilotcli", "name": "GPT-5", "supportsVision": true}
                            ],
                            "capabilities": {
                                "multipleChats": {"fork": true, "sideChat": true},
                                "multipleWorkingDirectories": {"immutablePrimary": true}
                            }
                        }
                    ],
                    "activeSessions": 3,
                    "terminals": [
                        {
                            "resource": "ahp-terminal:/demo-term",
                            "title": "zsh — auth-service",
                            "claim": {
                                "kind": "session",
                                "session": "ahp-session:/demo-auth",
                                "chat": "ahp-chat://default/main"
                            },
                            "lifecycle": {
                                "status": "running"
                            }
                        }
                    ],
                    "config": {
                        "schema": {"type": "object", "properties": {}},
                        "values": {}
                    }
                }
            }
        ],
        "completionTriggerCharacters": ["@", "#", "/", " "],
        "terminalCommandPrefix": "!"
    }"""

    private fun listSessionsResultJson(): String = """{
        "items": [
            {
                "resource": "ahp-session:/demo-auth",
                "provider": "copilotcli",
                "title": "Harden OAuth2 PKCE & token refresh",
                "status": 56,
                "createdAt": "2026-11-09T16:10:00.000Z",
                "modifiedAt": "2026-11-09T17:40:00.000Z",
                "project": {"uri": "file:///work/auth-service", "displayName": "auth-service"},
                "workingDirectories": ["file:///work/auth-service"],
                "changes": {"additions": 42, "deletions": 10, "files": 2},
                "chats": [
                    {
                        "resource": "ahp-chat://default/main",
                        "title": "Main chat",
                        "status": 1,
                        "interactivity": "full",
                        "modifiedAt": "2026-11-09T17:39:00.000Z",
                        "origin": {"kind": "user"},
                        "changes": {"additions": 42, "deletions": 10, "files": 2}
                    },
                    {
                        "resource": "ahp-chat://default/fork-mig",
                        "title": "Verify database migration rollback",
                        "status": 56,
                        "interactivity": "full",
                        "modifiedAt": "2026-11-09T17:40:00.000Z",
                        "origin": {"kind": "fork", "chat": "ahp-chat://default/main", "turnId": "t1"}
                    },
                    {
                        "resource": "ahp-chat://default/side-ed25519",
                        "title": "Why does Ed25519 check allocate here?",
                        "status": 1,
                        "interactivity": "full",
                        "modifiedAt": "2026-11-09T17:32:00.000Z",
                        "origin": {"kind": "sideChat", "chat": "ahp-chat://default/main", "turnId": "t1"}
                    }
                ],
                "defaultChat": "ahp-chat://default/main"
            },
            {
                "resource": "ahp-session:/demo-vllm",
                "provider": "copilotcli",
                "title": "Benchmark vLLM tensor-parallel prefill",
                "status": 1,
                "createdAt": "2026-11-09T14:00:00.000Z",
                "modifiedAt": "2026-11-09T17:20:00.000Z",
                "project": {"uri": "file:///work/llm-bench", "displayName": "llm-bench"},
                "workingDirectories": ["file:///work/llm-bench"],
                "changes": {"additions": 84, "deletions": 12, "files": 3},
                "chats": [
                    {
                        "resource": "ahp-chat://default/vllm-main",
                        "title": "Main chat",
                        "status": 1,
                        "interactivity": "full",
                        "modifiedAt": "2026-11-09T17:20:00.000Z",
                        "origin": {"kind": "user"}
                    }
                ],
                "defaultChat": "ahp-chat://default/vllm-main"
            },
            {
                "resource": "ahp-session:/demo-nano",
                "provider": "copilotcli",
                "title": "Add Gemini Nano on-device quick-reply chips",
                "status": 1,
                "createdAt": "2026-11-09T12:00:00.000Z",
                "modifiedAt": "2026-11-09T16:50:00.000Z",
                "project": {"uri": "file:///work/hostline-mobile", "displayName": "hostline-mobile"},
                "workingDirectories": ["file:///work/hostline-mobile"],
                "changes": {"additions": 128, "deletions": 19, "files": 4},
                "chats": [
                    {
                        "resource": "ahp-chat://default/nano-main",
                        "title": "Main chat",
                        "status": 1,
                        "interactivity": "full",
                        "modifiedAt": "2026-11-09T16:50:00.000Z",
                        "origin": {"kind": "user"}
                    }
                ],
                "defaultChat": "ahp-chat://default/nano-main"
            }
        ]
    }"""

    private fun subscribeResultJson(channel: String, currentSeq: Long): String = when (channel) {
        "ahp-session:/demo-auth" -> """{
            "snapshot": {
                "resource": "ahp-session:/demo-auth",
                "fromSeq": $currentSeq,
                "state": {
                    "provider": "copilotcli",
                    "title": "Harden OAuth2 PKCE & token refresh",
                    "status": 56,
                    "lifecycle": "ready",
                    "project": {"uri": "file:///work/auth-service", "displayName": "auth-service"},
                    "workingDirectories": ["file:///work/auth-service"],
                    "activeClients": [],
                    "changesets": [
                        {
                            "label": "Uncommitted changes",
                            "description": "2 files modified",
                            "changeKind": "uncommitted",
                            "uriTemplate": "ahp-changeset:/demo-uncommitted",
                            "capabilities": {"review": {}}
                        }
                    ],
                    "defaultChat": "ahp-chat://default/main",
                    "chats": [
                        {
                            "resource": "ahp-chat://default/main",
                            "title": "Main chat",
                            "status": 1,
                            "interactivity": "full",
                            "modifiedAt": "2026-11-09T17:39:00.000Z",
                            "origin": {"kind": "user"},
                            "changes": {"additions": 42, "deletions": 10, "files": 2}
                        },
                        {
                            "resource": "ahp-chat://default/fork-mig",
                            "title": "Verify database migration rollback",
                            "status": 56,
                            "interactivity": "full",
                            "modifiedAt": "2026-11-09T17:40:00.000Z",
                            "origin": {"kind": "fork", "chat": "ahp-chat://default/main", "turnId": "t1"}
                        },
                        {
                            "resource": "ahp-chat://default/side-ed25519",
                            "title": "Why does Ed25519 check allocate here?",
                            "status": 1,
                            "interactivity": "full",
                            "modifiedAt": "2026-11-09T17:32:00.000Z",
                            "origin": {"kind": "sideChat", "chat": "ahp-chat://default/main", "turnId": "t1"}
                        }
                    ],
                    "customizations": [
                        {
                            "id": "github-mcp-server",
                            "uri": "file:///work/auth-service/.vscode/mcp.json",
                            "name": "github-mcp-server",
                            "type": "mcpServer",
                            "state": {"kind": "ready"}
                        },
                        {
                            "id": "dir-skills",
                            "uri": "file:///work/auth-service/.github/skills",
                            "name": "Workspace Skills",
                            "type": "directory",
                            "enabled": true,
                            "contents": "skill",
                            "writable": false,
                            "load": {"kind": "loaded"},
                            "children": [
                                {
                                    "id": "cust-skill-1",
                                    "uri": "file:///work/auth-service/.github/skills/security-audit/SKILL.md",
                                    "name": "security-audit",
                                    "type": "skill",
                                    "enabled": true,
                                    "description": "Audits OAuth2, PKCE, JWT validation, and secret storage against OWASP ASVS"
                                }
                            ]
                        },
                        {
                            "id": "dir-agents",
                            "uri": "file:///work/auth-service/.github/agents",
                            "name": "Workspace Agents",
                            "type": "directory",
                            "enabled": true,
                            "contents": "agent",
                            "writable": false,
                            "load": {"kind": "loaded"},
                            "children": [
                                {
                                    "id": "cust-agent-1",
                                    "uri": "file:///work/auth-service/.github/agents/SecurityAuditor.agent.md",
                                    "name": "SecurityAuditor",
                                    "type": "agent",
                                    "enabled": true,
                                    "description": "Reviews authentication boundaries, timing side-channels, and token rotation",
                                    "model": "claude-sonnet-4.5"
                                }
                            ]
                        },
                        {
                            "id": "dir-rules",
                            "uri": "file:///work/auth-service/.github/instructions",
                            "name": "Workspace Rules",
                            "type": "directory",
                            "enabled": true,
                            "contents": "rule",
                            "writable": false,
                            "load": {"kind": "loaded"},
                            "children": [
                                {
                                    "id": "cust-rule-1",
                                    "uri": "file:///work/auth-service/.github/instructions/security.instructions.md",
                                    "name": "kotlin-security-rules",
                                    "type": "rule",
                                    "enabled": true,
                                    "description": "Require constant-time digest comparison and hardware-backed keystore",
                                    "alwaysApply": true
                                }
                            ]
                        }
                    ],
                    "_meta": {
                        "model": "claude-sonnet-4.5",
                        "git": {
                            "branchName": "feat/pkce-s256-hardening",
                            "baseBranchName": "main",
                            "outgoingChanges": 2,
                            "uncommittedChanges": 2
                        }
                    }
                }
            }
        }"""

        "ahp-session:/demo-vllm" -> """{
            "snapshot": {
                "resource": "ahp-session:/demo-vllm",
                "fromSeq": $currentSeq,
                "state": {
                    "provider": "copilotcli",
                    "title": "Benchmark vLLM tensor-parallel prefill",
                    "status": 1,
                    "lifecycle": "ready",
                    "project": {"uri": "file:///work/llm-bench", "displayName": "llm-bench"},
                    "workingDirectories": ["file:///work/llm-bench"],
                    "activeClients": [],
                    "defaultChat": "ahp-chat://default/vllm-main",
                    "chats": [
                        {
                            "resource": "ahp-chat://default/vllm-main",
                            "title": "Main chat",
                            "status": 1,
                            "interactivity": "full",
                            "modifiedAt": "2026-11-09T17:20:00.000Z",
                            "origin": {"kind": "user"}
                        }
                    ]
                }
            }
        }"""

        "ahp-session:/demo-nano" -> """{
            "snapshot": {
                "resource": "ahp-session:/demo-nano",
                "fromSeq": $currentSeq,
                "state": {
                    "provider": "copilotcli",
                    "title": "Add Gemini Nano on-device quick-reply chips",
                    "status": 1,
                    "lifecycle": "ready",
                    "project": {"uri": "file:///work/hostline-mobile", "displayName": "hostline-mobile"},
                    "workingDirectories": ["file:///work/hostline-mobile"],
                    "activeClients": [],
                    "defaultChat": "ahp-chat://default/nano-main",
                    "chats": [
                        {
                            "resource": "ahp-chat://default/nano-main",
                            "title": "Main chat",
                            "status": 1,
                            "interactivity": "full",
                            "modifiedAt": "2026-11-09T16:50:00.000Z",
                            "origin": {"kind": "user"}
                        }
                    ]
                }
            }
        }"""

        "ahp-chat://default/main" -> """{
            "snapshot": {
                "resource": "ahp-chat://default/main",
                "fromSeq": $currentSeq,
                "state": {
                    "resource": "ahp-chat://default/main",
                    "title": "Main chat",
                    "status": 1,
                    "interactivity": "full",
                    "modifiedAt": "2026-11-09T17:39:00.000Z",
                    "origin": {"kind": "user"},
                    "changes": {"additions": 42, "deletions": 10, "files": 2},
                    "turns": [
                        {
                            "id": "t1",
                            "message": {
                                "text": "Add PKCE S256 verifier validation to SessionGuard.kt and run the auth test suite.",
                                "origin": {"kind": "user"},
                                "model": {"id": "claude-sonnet-4.5"}
                            },
                            "responseParts": [
                                {
                                    "kind": "reasoning",
                                    "id": "r1",
                                    "content": "Inspecting SessionGuard.kt and TokenExchangeService.kt to reject plain PKCE downgrades and enforce constant-time SHA-256 verification."
                                },
                                {
                                    "kind": "toolCall",
                                    "toolCall": {
                                        "toolCallId": "tc-edit-guard",
                                        "toolName": "edit_file",
                                        "displayName": "Edit src/auth/SessionGuard.kt",
                                        "status": "completed",
                                        "confirmed": "user-action",
                                        "success": true,
                                        "invocationMessage": "Updated PKCE challenge check in `src/auth/SessionGuard.kt`",
                                        "pastTenseMessage": "Updated PKCE challenge check in `src/auth/SessionGuard.kt`",
                                        "content": [
                                            {
                                                "type": "fileEdit",
                                                "before": {"uri": "file:///work/auth-service/src/auth/SessionGuard.kt", "content": {"uri": "ahp-content:/demo-before"}},
                                                "after": {"uri": "file:///work/auth-service/src/auth/SessionGuard.kt", "content": {"uri": "ahp-content:/demo-after"}},
                                                "diff": {"added": 18, "removed": 4}
                                            }
                                        ]
                                    }
                                },
                                {
                                    "kind": "toolCall",
                                    "toolCall": {
                                        "toolCallId": "tc-run-tests",
                                        "toolName": "run_in_terminal",
                                        "displayName": "Run :core-auth:test",
                                        "status": "completed",
                                        "confirmed": "not-needed",
                                        "success": true,
                                        "invocationMessage": "Ran `./gradlew :core-auth:test`",
                                        "pastTenseMessage": "Ran `./gradlew :core-auth:test` (42 passed)",
                                        "content": [
                                            {
                                                "type": "terminal",
                                                "resource": "ahp-terminal:/demo-term",
                                                "title": "zsh — auth-service",
                                                "isPty": true,
                                                "result": {
                                                    "exitCode": 0,
                                                    "preview": "BUILD SUCCESSFUL in 1s\n42 actionable tasks: 42 passed"
                                                }
                                            }
                                        ],
                                        "_meta": {"toolKind": "terminal", "language": "bash"}
                                    }
                                },
                                {
                                    "kind": "markdown",
                                    "id": "m1",
                                    "content": "I've hardened `SessionGuard.kt` and `TokenExchangeService.kt`:\n\n- Enforced constant-time SHA-256 comparison via `MessageDigest.isEqual`\n- Rejected plain-text `code_verifier` downgrades when `S256` was negotiated\n- All **42 unit tests** in `:core-auth:test` passed (`1.4s`).\n\nTap **Uncommitted changes (+42, −10)** in the top bar to review the line-by-line diff, or switch to the **Verify database migration rollback** fork to test biometric tool approval."
                                }
                            ],
                            "startedAt": "2026-11-09T17:38:00.000Z",
                            "state": "complete",
                            "duration": 14200,
                            "usage": {
                                "inputTokens": 4820,
                                "outputTokens": 390,
                                "model": "claude-sonnet-4.5"
                            }
                        }
                    ]
                }
            }
        }"""

        "ahp-chat://default/fork-mig" -> """{
            "snapshot": {
                "resource": "ahp-chat://default/fork-mig",
                "fromSeq": $currentSeq,
                "state": {
                    "resource": "ahp-chat://default/fork-mig",
                    "title": "Verify database migration rollback",
                    "status": 56,
                    "interactivity": "full",
                    "modifiedAt": "2026-11-09T17:40:00.000Z",
                    "origin": {"kind": "fork", "chat": "ahp-chat://default/main", "turnId": "t1"},
                    "turns": [],
                    "activeTurn": {
                        "id": "t-mig-1",
                        "message": {
                            "text": "Verify V14 refresh-token table rollback migration before applying to staging.",
                            "origin": {"kind": "user"},
                            "model": {"id": "claude-sonnet-4.5"}
                        },
                        "responseParts": [
                            {
                                "kind": "reasoning",
                                "id": "r-mig-1",
                                "content": "Preparing to run flyway undo check against local staging snapshot."
                            },
                            {
                                "kind": "toolCall",
                                "toolCall": {
                                    "toolCallId": "tc-approve-mig",
                                    "toolName": "edit_file",
                                    "displayName": "Edit file: src/auth/SessionGuard.kt",
                                    "status": "pending-confirmation",
                                    "invocationMessage": "Apply strict S256 PKCE enforcement in `src/auth/SessionGuard.kt`?",
                                    "riskAssessment": {
                                        "kind": "judge",
                                        "status": "complete",
                                        "safety": 0.64,
                                        "reason": "Modifies OAuth2 token validation and session revocation middleware. Review diff before applying."
                                    },
                                    "edits": {
                                        "items": [
                                            {
                                                "before": {"uri": "file:///work/auth-service/src/auth/SessionGuard.kt", "content": {"uri": "ahp-content:/demo-before"}},
                                                "after": {"uri": "file:///work/auth-service/src/auth/SessionGuard.kt", "content": {"uri": "ahp-content:/demo-after"}},
                                                "diff": {"added": 18, "removed": 4}
                                            }
                                        ]
                                    }
                                }
                            }
                        ],
                        "startedAt": "2026-11-09T17:40:00.000Z"
                    }
                }
            }
        }"""

        "ahp-chat://default/side-ed25519" -> """{
            "snapshot": {
                "resource": "ahp-chat://default/side-ed25519",
                "fromSeq": $currentSeq,
                "state": {
                    "resource": "ahp-chat://default/side-ed25519",
                    "title": "Why does Ed25519 check allocate here?",
                    "status": 1,
                    "interactivity": "full",
                    "modifiedAt": "2026-11-09T17:32:00.000Z",
                    "origin": {"kind": "sideChat", "chat": "ahp-chat://default/main", "turnId": "t1"},
                    "turns": [
                        {
                            "id": "t-side-1",
                            "message": {
                                "text": "Why does Ed25519 signature verification allocate a new ByteArray on every call?",
                                "origin": {"kind": "user"}
                            },
                            "responseParts": [
                                {
                                    "kind": "markdown",
                                    "id": "m-side-1",
                                    "content": "`ByteBuffer.array()` copies when the slice is non-contiguous. Using a thread-local scratch buffer or passing `(bytes, offset, length)` directly into `Signature.update` avoids the per-request allocation."
                                }
                            ],
                            "startedAt": "2026-11-09T17:31:00.000Z",
                            "state": "complete",
                            "duration": 4100
                        }
                    ]
                }
            }
        }"""

        "ahp-changeset:/demo-uncommitted" -> """{
            "snapshot": {
                "resource": "ahp-changeset:/demo-uncommitted",
                "fromSeq": $currentSeq,
                "state": {
                    "status": "ready",
                    "files": [
                        {
                            "id": "src/auth/SessionGuard.kt",
                            "reviewed": false,
                            "edit": {
                                "before": {"uri": "file:///work/auth-service/src/auth/SessionGuard.kt", "content": {"uri": "ahp-content:/demo-before"}},
                                "after": {"uri": "file:///work/auth-service/src/auth/SessionGuard.kt", "content": {"uri": "ahp-content:/demo-after"}},
                                "diff": {"added": 18, "removed": 4}
                            }
                        },
                        {
                            "id": "src/auth/TokenExchangeService.kt",
                            "reviewed": true,
                            "edit": {
                                "before": {"uri": "file:///work/auth-service/src/auth/TokenExchangeService.kt", "content": {"uri": "ahp-content:/demo-token-before"}},
                                "after": {"uri": "file:///work/auth-service/src/auth/TokenExchangeService.kt", "content": {"uri": "ahp-content:/demo-token-after"}},
                                "diff": {"added": 24, "removed": 6}
                            }
                        }
                    ],
                    "operations": [
                        {"id": "stage-all", "label": "Stage all", "description": "Stage all modified files", "scopes": ["changeset"], "status": "idle"},
                        {"id": "discard-file", "label": "Discard file changes", "scopes": ["resource"], "confirmation": "Discard uncommitted edits to this file?", "status": "idle"},
                        {"id": "stage-file", "label": "Stage file", "scopes": ["resource"], "status": "idle"}
                    ]
                }
            }
        }"""

        "ahp-terminal:/demo-term" -> """{
            "snapshot": {
                "resource": "ahp-terminal:/demo-term",
                "fromSeq": $currentSeq,
                "state": {
                    "title": "zsh — auth-service",
                    "isPty": true,
                    "supportsCommandDetection": true,
                    "claim": {
                        "kind": "session",
                        "session": "ahp-session:/demo-auth",
                        "chat": "ahp-chat://default/main"
                    },
                    "lifecycle": {
                        "status": "running"
                    },
                    "content": [
                        {
                            "type": "command",
                            "commandId": "cmd-1",
                            "commandLine": "./gradlew :core-auth:test",
                            "output": "> Task :core-auth:compileKotlin\n> Task :core-auth:test\n  SessionGuardTest > rejectsPlainPkceDowngrade PASSED\n  SessionGuardTest > verifiesConstantTimeS256Challenge PASSED\n  SessionGuardTest > appliesClockSkewLeeway PASSED\n\nBUILD SUCCESSFUL in 1.4s\n42 actionable tasks: 42 executed\n",
                            "timestamp": 1762709880000,
                            "isComplete": true,
                            "exitCode": 0,
                            "durationMs": 1400
                        }
                    ]
                }
            }
        }"""

        else -> """{
            "snapshot": {
                "resource": "$channel",
                "fromSeq": $currentSeq,
                "state": {
                    "resource": "$channel",
                    "title": "Main chat",
                    "status": 1,
                    "interactivity": "full",
                    "modifiedAt": "2026-11-09T17:20:00.000Z",
                    "turns": [
                        {
                            "id": "t-default-1",
                            "message": {"text": "Summarize the latest benchmark results.", "origin": {"kind": "user"}},
                            "responseParts": [
                                {
                                    "kind": "markdown",
                                    "id": "m-default-1",
                                    "content": "Prefill throughput improved by **28%** (`14,200 tok/s` -> `18,180 tok/s`) with P99 TTFT reduced to `41ms`."
                                }
                            ],
                            "startedAt": "2026-11-09T17:15:00.000Z",
                            "state": "complete",
                            "duration": 6200
                        }
                    ]
                }
            }
        }"""
    }

    private fun resourceReadResultJson(uri: String): String {
        val text = when (uri) {
            "ahp-content:/demo-before" -> """
                package dev.auth

                import java.security.MessageDigest
                import java.time.Clock

                class SessionGuard(private val clock: Clock) {
                    fun verifyPkce(verifier: String, challenge: String, method: String): Boolean {
                        if (method == "plain") return verifier == challenge
                        val digest = sha256Base64Url(verifier)
                        return digest == challenge
                    }

                    fun isTokenFresh(issuedAtEpochSec: Long, nowEpochSec: Long): Boolean {
                        return nowEpochSec - issuedAtEpochSec <= MAX_AGE_SEC
                    }

                    companion object {
                        private const val MAX_AGE_SEC = 3600L
                    }
                }
            """.trimIndent()

            "ahp-content:/demo-after" -> """
                package dev.auth

                import java.security.MessageDigest
                import java.time.Clock

                class SessionGuard(private val clock: Clock) {
                    fun verifyPkce(verifier: String, challenge: String, method: String): Boolean {
                        require(method == "S256") { "PKCE plain downgrade rejected" }
                        val expected = sha256Base64Url(verifier).encodeToByteArray()
                        val actual = challenge.encodeToByteArray()
                        return MessageDigest.isEqual(expected, actual)
                    }

                    fun isTokenFresh(issuedAtEpochSec: Long, nowEpochSec: Long): Boolean {
                        val skewAdjusted = nowEpochSec - CLOCK_SKEW_LEEWAY_SEC
                        return skewAdjusted - issuedAtEpochSec <= MAX_AGE_SEC
                    }

                    companion object {
                        private const val MAX_AGE_SEC = 3600L
                        private const val CLOCK_SKEW_LEEWAY_SEC = 30L
                    }
                }
            """.trimIndent()

            "ahp-content:/demo-token-before" -> """
                package dev.auth

                class TokenExchangeService(private val guard: SessionGuard) {
                    fun exchange(code: String, verifier: String, challenge: String): String {
                        check(guard.verifyPkce(verifier, challenge, "plain"))
                        return issueJwt(code)
                    }
                }
            """.trimIndent()

            "ahp-content:/demo-token-after" -> """
                package dev.auth

                class TokenExchangeService(private val guard: SessionGuard) {
                    fun exchange(code: String, verifier: String, challenge: String, method: String = "S256"): String {
                        check(guard.verifyPkce(verifier, challenge, method)) {
                            "Invalid PKCE code_verifier for S256 challenge"
                        }
                        return issueJwt(code)
                    }
                }
            """.trimIndent()

            else -> """
                # Security Audit Skill

                Checks OAuth2 PKCE S256 enforcement, constant-time token digest verification, and clock-skew bounds across authentication endpoints.
            """.trimIndent()
        }
        return """{"data":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))},"encoding":"utf-8"}"""
    }
}
