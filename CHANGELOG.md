# Changelog

## Unreleased

### GitHub distribution
- **GitHub flavor with Pro included during the beta**: a new `github` product flavor alongside `play` (see `docs/github-distribution.md`). The GitHub build is distributed from GitHub Releases and installable via Obtainium; Google Play Billing does not work in a sideloaded APK, so Pro is included free in this flavor for the beta period. The Play flavor is unchanged. A tag-triggered workflow (`.github/workflows/release-apk.yml`) builds the signed GitHub APK and publishes it to Releases.
- **Build task names changed**: adding flavors renames the release tasks — Play is now `:app:assemblePlayRelease` / `:app:bundlePlayRelease`, GitHub is `:app:assembleGithubRelease`.

## v0.2.0 (`versionCode 3`) — 2026-11-06

### Rebrand
- **AHP Client is now Hostline** (`U9`): Updated launcher name, Quick Settings tile, in-app headings, biometric prompts, and Play Store listing (`Hostline: AHP Client`) while preserving the `com.greenjeans.ahpclient` application ID so existing installs and Pro purchases carry over seamlessly.

### New Features
- **AI & On-Device Quick Replies (`#25`, `#26`, `#27`, `U3`, `U8`)**:
  - Generate context-aware quick-reply suggestions on-device with **Gemini Nano** or via configurable cloud AI providers (Google AI, OpenAI-compatible, Anthropic) with live model discovery.
  - Inline sparkle **Suggest** button inside the message field (`U3`) and persistent dismissal state per chat (`#60`).
- **File & Image Attachments (`N3a`, `#37`)**:
  - Attach up to 5 files or images (`<= 5 MB` each, `<= 8 MB` total) from the Android system document picker when sending, editing, or retrying messages, with model vision support checks.
- **Slash Commands, Mentions & Live Host Completions (`#24`, `N2`, `N3b`, `#43`)**:
  - Local `/` slash-command and `@` agent autocomplete plus live host-backed `completions` for `#` file references, `/` command arguments, and `@` mentions that attach host-resolved context chips.
  - Dynamic autocomplete (`sessionConfigCompletions`) for `enumDynamic` fields when starting a new session (`N2`).
- **Long-Chat Navigation & Full-Screen Reader View (`U1`, `U2`, `#41`, `#60`)**:
  - **In-memory chat cache (`U1`)**: Returning to recently viewed chats is instant and preserves your exact scroll position when the live snapshot arrives.
  - **Pinned last-message bar & reply jump (`U2`)**: Keep your most recent prompt pinned at the top while scrolling long agent turns, and jump straight to the first line of the agent's latest reply with one tap.
  - **Reader View (`U2`)**: Tap **Read** on any completed turn to open a clean, distraction-free full-screen view with the final answer at the top and intermediate progress notes neatly folded underneath.
  - **Steady scroll under touch (`#60`)**: Touching the transcript holds the list steady while a turn streams instead of pulling rows out from under your finger.
- **Hierarchical Chat Switcher & Sub-Chat Navigation (`U5`, `#45`, `#53`, `#60`)**:
  - Replaced the flat multi-chat tab bar with a **Main chat** chip and an **N other chats** bottom sheet grouped into *Waiting on you*, *Forks*, *Side chats*, *Started by you*, *Other chats*, *Chats the agent uses* (collapsed by default), and *Hidden chats* (behind a toggle).
  - Added inline turn provenance links on turns that spawned forks or side chats, plus collapsible sub-chats and a condensed view toggle in the sessions drawer (`#60`).
- **Pre-Approval Risk Assessment & Diff Preview (`P1`, `#33`, `#34`)**:
  - Pending tool confirmation cards now display the host's safety/risk assessment, MCP server contributor attribution, and a preview of proposed file diffs before you tap **Approve** or **Deny**.
- **Environment Customizations & MCP Server Controls (`#24`, `#30`, `P2`)**:
  - Inspect session plugins, MCP servers, skills, instructions, prompts, agents, hooks, and server tools.
  - Start, stop, or background MCP servers and toggle session-scoped customizations on or off (`session/customizationToggled`).
- **Host Automations (`P7`, `#56`, `#58`)**:
  - Browse the host's scheduled and event-triggered automations from the navigation drawer, inspect recent runs and open their sessions, toggle automations on or off (with confirmation before changing scheduled/event automations), and cancel running executions.
- **Live Terminal Streaming & Host Terminals (`U7`, `P8`, `#47`, `#49`, `#57`)**:
  - Running shell/terminal tool calls in chat now stream a live 12-line terminal tail as output arrives (`U7`).
  - Browse and open root-level host terminals (`RootState.terminals`, including VS Code `agenthost-terminal:/<id>` channels) in read-only mode from the drawer (`P8`).
  - Structured terminal command headers showing working directory (`cwd`) and exit code badges (`P3`).
- **Per-Chat Changesets, Diff Operations & Session Management (`#29`, `P3`, `P5`, `#48`, `#59`)**:
  - Inspect per-chat changesets (`Branch`, `Uncommitted`, `Session`, `Working Set`) and perform resource-scoped changeset actions (such as discarding changes) on individual files or full changesets.
  - Rename sessions in place and manage session working directories (`P5`), with automatic filtering of temporary, scratch, and deleted worktree folders from recent folders (`#59`).
- **Canvases, Annotations & Token Telemetry (`#30`, `P4`, `#60`)**:
  - View per-turn and per-chat token usage and cost telemetry (ignoring unitless Copilot premium-request credits unless currency-qualified).
  - Open live interactive **Canvas** channels and inspect/add/remove **Annotations** comment threads from the session details sheet (`P4`).
- **Windowed Delivery for Large Chats (`P9`, `#54`, `#55`)**:
  - Flow-controlled windowed delivery (`channel/snapshot`, `channel/frame`, `channel/credit` over a 4 MiB window / 1 MiB frames) so very long chat transcripts load smoothly without hitting single-message WebSocket limits.
- **Word Wrap & Table Fit Controls (`U6`, `#46`, `#53`)**:
  - Per-view word-wrap toggles remembered across restarts for code blocks, tool output, file viewer, terminal, and file diffs, plus a **Fit columns to width** toggle on wide Markdown tables.

### Bug Fixes & Hardening
- **Model numeric settings & default model selection (`#36`, `#48`)**: Support numeric model configuration options (such as `contextSize` for long-context tiers) and automatically select the host's first listed model with its defaults when none is reported.
- **Multi-host & sign-in improvements (`#29`, `#52`, `#58`)**: Sign in to GitHub in place while connected to a direct host; tap any connected host card in the host sheet to bring it to the front without reconnecting; preserve "Act for me" setting across Pro entitlement round-trips.
- **Read state & accessibility (`#42`, `#50`, `#60`)**: Automatically mark chats and sessions as read when viewing the latest message; clip rows cleanly below the pinned message bar so screen readers never focus 1px slivers; verified UI controls at 200% font scale.

---

## v0.1.0 (`versionCode 2`) — 2026-10-31
- Upgraded Google Play Billing Library to `8.0.0`.
- Initial closed testing release (`com.greenjeans.ahpclient`) with VS Code Remote Tunnel & direct `ahpd` connectivity, live session transcripts, biometric-gated tool approvals, checkpoints/forks/inline turn editing, background monitoring service, and in-app legal/privacy disclosures.
