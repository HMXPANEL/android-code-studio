# Phase 1 Plan — AI Agent Foundation (Tool System)

> STATUS: PLAN ONLY. Nothing here is implemented yet.
> This document is the blueprint another agent will use later to implement Phase 1.
> No source code, Gradle, or GitHub Actions were changed to produce this plan.

## 1. Executive Summary

Today our AI Chat is a **one-shot helper**: the user types a request, the app sends
the whole request plus some project files to an AI provider (Gemini, OpenAI, …),
and the provider answers with either plain text or with full replacement file
contents marked by `FILE_TO_MODIFY:` lines, which the app writes to disk.

That works for small edits, but the AI cannot **look around by itself**. It cannot
list a folder, read one more file, search for a symbol, run a command, or build the
project to check its own work. Everything it sees must be stuffed into a single
giant prompt, which wastes RAM and money and breaks on big projects.

**Phase 1 adds a Tool System**: a small, safe layer that lets the AI say
"I need to read file X" or "run this build", have the app execute that step
locally, and hand the result back so the AI can continue working step by step —
like Claude Code or OpenCode do, but designed for this Android IDE, its existing
providers, its existing file/build APIs, and a phone's limited RAM.

Phase 1 covers **read-only tools + the loop machinery first**
(`list_files`, `read_file`, `search_files`), then adds **write tools**
(`write_file`, `edit_file`), and only then the **powerful tools**
(`delete_file`, `run_command`, `build_project`) behind permissions.
It does NOT include MCP, Skills, sub-agents, vector search, or local models.

---

## 2. Current Architecture

How the app works today (all paths verified in the repository):

```
User types in AIChatFragment (fragment_ai_chat.xml)
        ↓
ArtificialFragment drives AIAgentManager.executeRequest(userRequest, callback)
        ↓  (lifecycleScope coroutine)
AIAgentManager (core/app/.../artificial/agents/AIAgentManager.kt)
  - captures current .kt/.java/.xml/.gradle file contents (walkTopDown)
  - calls currentAgent.generateCode(prompt, ...)
        ↓
Provider (one of: google/Gemini.kt, openai/OpenAI.kt, anthropic/Anthropic.kt,
deepseek/DeepSeek.kt, grok/Grok.kt, local/LocalLLM.kt)
  - builds ONE giant prompt: project tree + file contents + history + rules
  - Gemini: GenerativeModel SDK (generativeai:0.9.0) / others: raw HttpURLConnection
  - returns Result<String> (plain text OR text containing FILE_TO_MODIFY: blocks)
        ↓
AIAgentManager.processModifications()
  - parses FILE_TO_MODIFY:<path> lines, cleans with SnippetParser
  - writes via AIAgent.writeFile → AIFileWriter (backup + permission check)
  - reports via AIAgentCallback: onProcessing / onFileModifying / onFileModified /
    onSuccess / onTextResponse / onError / onRetry
        ↓
AIAgentViewModel (chat bubbles, working spinner, Build/Plan mode, attachment)
```

Key facts the plan builds on:

- **Providers are pluggable**: `AIAgentRegistry` (`artificial/agents/AIAgentRegistry.kt`)
  registers factories per provider; `Agents` (`artificial/agents/Agents.kt`) stores the
  chosen provider/model in SharedPreferences. A tool layer can sit *above* providers
  so all six providers gain tools without rewriting each one.
- **No function-calling is used today**: no `FunctionCall`/`tools` parameters anywhere.
  The AI only ever returns free text. So Phase 1 must teach the app to *ask for* and
  *parse* tool calls (see §8).
- **Conversation state is split**: each provider keeps its own in-memory
  `conversationHistory` (capped at 20, cleared on `clearConversation()`); the chat UI
  keeps its own bubble list in `AIAgentViewModel`. There is **no database** (no Room;
  only SharedPreferences for settings). Tool history must therefore be an explicit
  new in-memory structure, not something we can query from storage.
- **No DI framework**: no Hilt/Koin. The app uses manual singletons (`object`),
  direct construction (`AIAgentManager(context)`), and a `Lookup` service locator
  (`utilities/lookup`). New agent classes should follow the same pattern: plain
  classes constructed with what they need, `object` registry for tools.
- **Everything is coroutine-based** (`lifecycleScope`, `Dispatchers.IO`); tool
  execution must be `suspend` functions with cancellation and timeouts, matching
  the existing style.

---

## 3. What Already Exists

| Feature | Already exists? | Where? | Can we reuse it? |
|---|---|---|---|
| AI provider (6 providers) | YES | `artificial/agents/{google,openai,anthropic,deepseek,grok,local}/` | YES — keep providers as-is; add tool support around them |
| AI request system | YES | `AIAgentManager.executeRequest()` + `AIAgentCallback` | YES — extend, don't replace; the agent loop reuses the callback pattern |
| Conversation (UI bubbles) | YES | `AIAgentViewModel`, `ChatMessageAdapter`, `fragment_ai_chat.xml` | YES — add a future activity-card slot only (already has file-activity rows) |
| Conversation history (AI memory) | PARTIAL | Per-provider in-memory `conversationHistory` (cap 20) | YES for chat; tool-call history needs a NEW list alongside it |
| Project context (tree + file read) | YES | `artificial/project/awareness/ProjectData.kt`, `ProjectTreeResult` | YES — `list_files`/`read_file` wrap these |
| File reading | YES | `ProjectTreeResult.readFileContent()`, `File.readText()` in manager | YES |
| File writing (+ backup) | YES | `artificial/file/AIFileWriter.kt` (`FileWriteResult`: Success / PermissionDenied / Error) | YES — `write_file` wraps it, backups stay free |
| File editing (partial replace) | PARTIAL | `SnippetParser.cleanFileContent()` + full-file overwrite only | PARTIAL — `edit_file` needs NEW targeted-replace logic (read → replace snippet → write) |
| File search (content grep) | NO | Only ad-hoc `walkTopDown` filename lookups exist | NO — `search_files` must be created (simple line scanner, bounded) |
| File listing | PARTIAL | `GeneralFileUtils.listFilesInDirectory()`, `ProjectData` tree walk | YES — `list_files` wraps these with depth/limits |
| Terminal / run commands | PARTIAL | Headless: `core/common/.../shell/shellUtils.kt` (`executeProcess`); interactive Termux sessions exist but are UI-bound | PARTIAL — `run_command` uses the headless shell helpers, NOT the Termux UI |
| Build the project | YES (API exists) | `BuildService.executeTasks(vararg)` (`core/projects/.../builder/BuildService.kt`) via `Lookup`; example: `actions/build/QuickRunWithCancellationAction.kt` | YES — `build_project` calls `executeTasks("assembleDebug")`, honors `isBuildInProgress`/`cancelCurrentBuild()` |
| Git | YES | `GitManager` (`git/GitManager.kt`, JGit: status, diff, commit, branches…) | YES (later phases; Phase 1 needs none, but it is available) |
| Permissions for AI file writes | YES | `artificial/permission/AIPermissionManager.kt` + `dialogs/AIPermissionDialog.kt` | YES — extend allow-list + confirmation to tools |
| Tool abstraction | NO | — | Must be created (the core of Phase 1) |
| Tool execution | NO | — | Must be created |
| Agent loop (multi-step) | NO | Today's flow is single request → single response (+ text retry loop) | Must be created |
| MCP | NO | — | Explicitly out of scope |
| Skills | NO | — | Explicitly out of scope |
| Sub-agents | NO | — | Explicitly out of scope |

---

## 4. What Is Missing

Simple answer to *"what do we need to add to turn the chat into an agent?"*:

1. **A Tool interface** — one common shape every tool follows (name, description,
   inputs, `execute()`, result). Without it, each capability would be wired
   ad-hoc and the AI could never discover them uniformly.
2. **A Tool Registry** — a central list ("here are the tools you may call, with
   their descriptions") that is shown to the AI on every step. Mirrors the existing
   `AIAgentRegistry` idea, but for tools instead of providers.
3. **A Tool Executor** — the piece that validates a tool call (right tool? allowed
   inputs? inside project? within budget?), runs it with timeout + cancellation,
   and converts success/failure into a uniform result. This is also where the
   safety rules live.
4. **A way for the AI to request tools** — today providers return free text. Phase 1
   needs a small protocol: either native function-calling (Gemini SDK supports it;
   OpenAI-style HTTP needs a `tools` parameter added) or a strict text format
   (e.g. a `TOOL_CALL:` block, mirroring today's `FILE_TO_MODIFY:` convention),
   plus a parser. Recommendation for Phase 1: start with the text protocol for ALL
   providers (uniform, no per-provider SDK work), add native function-calling later.
5. **An Agent Controller + loop** — replaces the single `generateCode → done` step
   with: ask AI → parse reply (final answer OR tool call) → execute tool → append
   result → ask AI again, up to a max-step limit (e.g. 15), until a final answer.
6. **A Tool Result model** — success flag, human-readable output text, optional
   structured data, execution time, error message. Fed back into the AI prompt and
   shown in the chat activity card.
7. **Safety layer** — path allow-list ( reuse `AIPermissionManager`), read-only vs
   write vs destructive classification, user confirmation for destructive/powerful
   tools, command block-list for `run_command`, per-tool timeouts, whole-run
   cancellation (reuse the existing `executionJob?.cancel()` pattern).
8. **Agent state + history** — the ordered list of (AI thought → tool call → result)
   for the current run: kept in memory in the controller, summarized into the chat
   bubble, and appended (trimmed) to provider history so follow-up questions keep
   context without blowing RAM.
9. **Build/Plan wiring** — PLAN = same loop but write/destructive tools are
   registered as "disabled" (AI is told they are unavailable, so it plans instead);
   BUILD = full registry. The existing `AgentMode` in `AIAgentViewModel` already
   stores the choice; Phase 1 only needs the controller to *read* it.

What we do NOT need in Phase 1: MCP servers, Skills files, sub-agents, embeddings,
vector search, a database, new AI SDKs, local models, or a new backend.

---

## 5. Phase 1 Goal

> Give the existing chat a safe, step-by-step tool loop, starting with
> read-only tools, reusing every existing provider, file, build, shell, and
> permission API — without changing how the chat looks or how providers are
> configured.

Success = the user can say *"find where the login error is thrown and show me
the file"*, and the AI lists files, searches, and reads — step by step, with
each step visible — instead of needing everything crammed into one prompt.

---

## 6. Simple Architecture

Adapted to this repository (existing pieces in [brackets]):

```
User types in AIChatFragment
        ↓
[AIAgentViewModel] (bubbles, working state, AgentMode BUILD/PLAN)
        ↓
NEW: AgentController  ← owns the run: history, step budget, cancellation
        ↓ ① "here is the request + tool descriptions + past results"
[AI Provider] (Gemini/OpenAI/… via AIAgent.generateCode — unchanged signature)
        ↓ ② AI replies: FINAL ANSWER  —or—  TOOL_CALL(name, inputs)
   ┌────┴─────────────────────────────┐
   │ final answer                     │ tool call
   ↓                                  ↓
chat bubble                    NEW: ToolRegistry → finds tool by name
done                                  ↓
                               NEW: ToolExecutor → validates, permission-checks,
                               runs with timeout, builds ToolResult
                                      ↓
                               tool wraps EXISTING code:
                               list_files → [ProjectData/GeneralFileUtils]
                               read_file → [ProjectTreeResult/File.readText]
                               search_files → NEW bounded scanner
                               write/edit → [AIFileWriter + SnippetParser]
                               run_command → [shell/shellUtils headless]
                               build_project → [BuildService.executeTasks]
                                      ↓
                               ToolResult → AgentController appends to run
                               history + chat activity card → back to ①
                               (max ~15 steps, then forced final answer)
```

Why this shape: providers stay untouched (tools work with all six at once);
all powerful APIs already exist and are only being *wrapped*, not rewritten;
the UI keeps talking to one controller through the existing callback style.

---

## 7. Tool Architecture

One common shape for every tool (described, not coded):

- **Tool = a small named unit with five things:**
  1. `name` — e.g. `read_file` (the exact word the AI must output).
  2. `description` — one or two sentences telling the AI *when* to use it.
  3. `input schema` — the named fields it needs (e.g. `path`, plus optional
     `maxLines`), with types and which are required. Kept as simple data, not a
     new library.
  4. `execute()` — a `suspend` function doing the work with cancellation support;
     it may ONLY touch the current project directory and must respect timeouts.
  5. `result` — always the same envelope: ok/failed + short output text + error
     text if failed (+ optional numbers like lines read / files found).

- **How the four example tools fit:**
  - `read_file { path, maxLines?, offset? }` → checks path is inside project →
    reads (at most ~200 lines by default) → returns text. Never loads huge files.
  - `write_file { path, content }` → permission check → backup via `AIFileWriter`
    → write → returns success + backup note. Blocked entirely in PLAN mode.
  - `search_files { query, fileTypes?, maxResults? }` → walks project files
    (skipping `build/`, `.gradle/`, binaries), matches lines, returns
    `path:line: snippet` rows, capped (e.g. 50). New code, ~100 lines.
  - `run_command { command, workDir?, timeoutSec? }` → rejects anything on the
    block-list (e.g. `rm -rf`, `su`, …) → runs headless via `shellUtils` with a
    captured-output cap (e.g. last 100 lines) → returns exit code + output.
    Requires explicit user confirmation in Phase 1.

- **Registry rules:** tools self-register in a central `object ToolRegistry`
  (same pattern as `AIAgentRegistry`); the controller asks it for (a) the
  description block pasted into each AI prompt and (b) lookup by name. Unknown
  tool names from the AI are returned *to the AI as an error result* ("no such
  tool, available tools are: …"), never executed.

---

## 8. Agent Loop

Very simple version of how one user message is handled (the "loop"):

1. User: *"Find the build error and fix it."*
2. Controller builds a prompt: original request + project tree (small now — big
   file contents are NO LONGER pasted; the AI fetches what it needs) + tool
   descriptions + mode rules (BUILD vs PLAN) + recent tool results.
3. AI replies. Two cases:
   - **Final answer** (no tool call) → show it in the bubble, done.
   - **Tool call**, e.g. `list_files { dir: "app/src" }` → executor validates and
     runs it → result (file list) goes back into the conversation → go to step 2.
4. Repeat: AI reads `build.gradle`, searches for the error text, edits the file
   (BUILD mode only), runs `build_project`, reads the build result.
5. Stop conditions: final answer received, OR step budget reached (~15) → the
   controller asks for a summary answer, OR user cancels, OR a tool fails
   fatally → the error is shown and the run ends safely.
6. Every step is appended to the visible activity card ("Reading … ✓, Searching …
   ⟳") reusing the existing `FileActivity` rows, and a trimmed transcript is kept
   for follow-up questions.

How the AI "calls" a tool in Phase 1: a strict text block in its reply
(`TOOL_CALL: read_file { "path": "…" }`), parsed with the same line-parsing
style as today's `FILE_TO_MODIFY:` convention — uniform across all six providers,
no SDK changes. (Native function-calling per provider is a documented future
upgrade, not Phase 1.)

---

## 9. Build/Plan Design

The UI selector already exists (`AIAgentViewModel.AgentMode`, persisted per chat).
Phase 1 only connects it to the loop:

- **PLAN mode:** the controller registers ONLY safe tools
  (`list_files`, `read_file`, `search_files`) and tells the AI *"you cannot modify
  files; produce a numbered plan instead."* If the AI still emits a write call,
  the executor refuses it and returns an error result (*"writes are disabled in
  PLAN mode"*), so the AI corrects itself. Nothing on disk ever changes.
- **BUILD mode:** the full registry is available; writes go through backup +
  allow-list + (for destructive/powerful tools) user confirmation.
- The mode travels with the run state (one mode per user message), so switching
  mid-conversation only affects new runs, never a run in flight. No autonomous
  behavior is added beyond what the loop above describes.

---

## 10. Safety Design

Simple rules, enforced in ONE place (the Tool Executor), reusing
`AIPermissionManager` + `AIFileWriter` backups + the existing permission dialog:

| Tool class | Examples | Phase 1 rule |
|---|---|---|
| READ | `list_files`, `read_file`, `search_files` | Always allowed inside the project dir; paths outside are rejected |
| WRITE | `write_file`, `edit_file` | Allowed in BUILD only; allow-listed dirs; auto-backup before every write; undo restores backup |
| DESTRUCTIVE | `delete_file` | Phase 1: confirmation dialog every time (reuse `AIPermissionDialog` pattern); moves to trash/backup first if cheap, else explicit double-confirm |
| POWERFUL | `run_command`, `build_project` | `run_command`: block-list (`rm -rf /`, `su`, `mkfs`, …) + confirmation + timeout (default ~60 s) + output cap; `build_project`: allowed in BUILD, honors the existing single-build lock (`isBuildInProgress`) and `cancelCurrentBuild()` |
| Cross-cutting | all tools | Per-tool timeout; whole-run cancel button (existing `executionJob?.cancel()`); every failure returns a structured error to the AI (which then explains or retries, max ~2 retries per step); nothing secret (API keys) is ever pasted into tool inputs/outputs |

---

## 11. Mobile/RAM Considerations

The phone is the constraint; Phase 1 must be *lighter* than today, not heavier:

- **No more giant prompts**: today the whole project context is pasted every request.
  The loop pastes only the tree + tool descriptions + recent results → smaller
  prompts, less RAM, lower cost.
- **Bounded reads**: `read_file` defaults to ~200 lines with offset paging;
  `search_files` caps results (~50) and skips `build/`, `.gradle/`, binaries, and
  files over ~1 MB. Never `readText()` a whole project.
- **Bounded history**: run transcript kept in memory only, trimmed (e.g. keep last
  ~10 tool exchanges, older ones summarized to one line); provider's 20-message cap
  stays.
- **No new processes by default**: tools run in existing coroutines; `run_command`
  and `build_project` reuse the existing process/tooling-server machinery instead
  of spawning private daemons. One build at a time (existing lock).
- **No new heavy dependencies**: JSON parsing via existing `gson`; no new SDKs,
  no embeddings, no local model, no database.
- **Battery/CPU**: file walks are capped by depth + ignore rules; searches run on
  `Dispatchers.IO` and are cancellable; timeouts kill runaway tools.

---

## 12. Files to Create/Modify

Based on the real package `com.tom.rv2ide` (app module). NOTHING below is created
yet — this is the shopping list for the implementing agent:

**Create** (new package `artificial/agent/` + `artificial/tools/`):

- `artificial/tools/Tool.kt` — Tool interface + `ToolInput`/`ToolResult` models.
- `artificial/tools/ToolRegistry.kt` — `object` registry (register/lookup/describe).
- `artificial/tools/ToolExecutor.kt` — validation, permissions, timeout,
  cancellation, result building.
- `artificial/tools/builtins/ListFilesTool.kt` — wraps `GeneralFileUtils`/`ProjectData`.
- `artificial/tools/builtins/ReadFileTool.kt` — bounded read with paging.
- `artificial/tools/builtins/SearchFilesTool.kt` — NEW bounded line scanner.
- `artificial/tools/builtins/WriteFileTool.kt` — wraps `AIFileWriter` (+ backup).
- `artificial/tools/builtins/EditFileTool.kt` — NEW targeted snippet replace
  (read → match → replace → write via `AIFileWriter`).
- `artificial/tools/builtins/DeleteFileTool.kt` — confirmation + backup-first delete.
- `artificial/tools/builtins/RunCommandTool.kt` — wraps `shell/shellUtils`
  headless execution + block-list + output cap.
- `artificial/tools/builtins/BuildProjectTool.kt` — wraps `BuildService.executeTasks`
  via `Lookup`, honors build lock/cancel.
- `artificial/agent/AgentController.kt` — the loop: prompt building, reply parsing,
  step budget, cancellation, run history.
- `artificial/agent/ToolCallParser.kt` — strict `TOOL_CALL:` block parser
  (mirrors today's `FILE_TO_MODIFY:` parsing style).
- `artificial/agent/AgentRun.kt` — run state model (mode, steps, transcript, status).

**Modify** (small, surgical):

- `artificial/agents/AIAgentManager.kt` — add an agent-loop entry point alongside
  (not instead of) `executeRequest()`; keep the old path until the loop is proven.
- `fragments/sidebar/AIAgentViewModel.kt` — surface run status
  (step count, current tool) for the activity card; `AgentMode` already exists.
- `adapters/ChatMessageAdapter.kt` — render generic tool-activity rows
  (the `FileActivity` slot already exists; generalize it).
- `artificial/rules/WritingRules.kt` — append the tool-protocol instructions
  (tool descriptions are injected per-run by the controller).

**Reuse untouched:** all six providers, `AIAgentRegistry`, `Agents`,
`AIPermissionManager`, `AIFileWriter`, `ProjectData`, `SnippetParser`,
`BuildService`/`GradleBuildService`, `shellUtils`, `AIHistoryFragment`,
`AIPreferencesFragment`, chat layouts/theme.

---

## 13. Implementation Steps

Small, ordered, each independently testable:

- **Phase 1.1 — Tool model.** Create `Tool.kt` (interface + input/result models).
  Done when: the file compiles and is documented; no behavior yet.
- **Phase 1.2 — Registry.** Create `ToolRegistry` (register/lookup/prompt-text).
  Done when: a unit-test-style check can register a fake tool and get its
  description block back.
- **Phase 1.3 — Executor skeleton.** Create `ToolExecutor` with validation +
  timeout + cancellation + structured errors, using 1 stub tool.
  Done when: unknown tool / bad input / timeout each return clean errors.
- **Phase 1.4 — Read-only tools.** Implement `list_files`, `read_file`,
  `search_files` on top of existing file APIs with bounds (depth, lines, results).
  Done when: each returns correct small outputs on the real project, huge files
  stay capped.
- **Phase 1.5 — Parser.** Implement `ToolCallParser` for the strict text protocol
  (valid call, multi-line JSON-ish args, malformed input → error, no false
  positives on normal chat text).
- **Phase 1.6 — AgentController + loop.** Wire controller → provider →
  parser → executor → history, with step budget (~15) and cancel.
  Done when: a scripted fake provider ("call list_files, then answer") completes
  a 2-step run end-to-end.
- **Phase 1.7 — Write tools.** `write_file` (via `AIFileWriter`), `edit_file`
  (targeted replace), gated by mode + allow-list + backup.
- **Phase 1.8 — Power tools.** `delete_file` (confirm + backup-first),
  `run_command` (block-list + confirm + timeout + output cap),
  `build_project` (via `BuildService`, honors lock/cancel).
- **Phase 1.9 — Chat wiring.** Connect controller to `AIAgentViewModel` run status
  + `ChatMessageAdapter` activity rows; PLAN disables write/destructive tools;
  keep old `executeRequest()` path as fallback until proven.
- **Phase 1.10 — Hardening + tests.** Retry limits, error paths, cancellation,
  RAM caps verification, then full CI (Debug + Release APK).

---

## 14. Testing Plan

(No unit-test framework exists in the repo today — see §15 for how these run.
Each test is small and manual-or-CI-verifiable:)

- **Test 1 — List.** User: *"list the files in app/src"*. Expected: one
  `list_files` call, correct bounded listing in the result bubble, no crash.
- **Test 2 — Read.** User: *"read build.gradle.kts"*. Expected: `read_file`
  returns the first ~200 lines with a "truncated, use offset" note if longer.
- **Test 3 — Chain.** User: *"find where X is defined and show it."* Expected:
  `search_files` → `read_file` → final answer quoting the file. Two+ steps,
  one continuous run.
- **Test 4 — Failure.** Point a tool at a missing path / failing command.
  Expected: structured error goes back to the AI, which explains it calmly
  instead of crashing or looping forever.
- **Test 5 — Cancel.** Start a long run, press cancel. Expected: run stops,
  partial activity stays visible, no half-written files (writes are atomic
  via backup-then-write).
- **Test 6 — PLAN mode.** In PLAN mode ask for a change. Expected: AI returns a
  numbered plan; any emitted write call is refused by the executor; disk
  byte-identical before/after (verify with Git status/diff).
- **Test 7 — Budget.** Ask something unanswerable. Expected: run stops at the
  step budget with a summary, no infinite loop, memory flat.
- **Test 8 — Regression.** Old-style request (*"add a button that…"*) with the
  legacy path still works identically (providers, history, undo, prefs).

---

## 15. CI/CD Validation

GitHub Actions is NOT modified in this task or in Phase 1. Phase 1 uses the
existing workflow (`Build Android Code Studio` → `assembleDebug` +
`assembleRelease` → `debug-apk`/`release-apk` artifacts) as its gate:

```
Phase 1 code change
        ↓
(push to main)
        ↓
GitHub Actions: assembleDebug + assembleRelease (JDK 21)
        ↓
Debug APK ✓  +  Release APK ✓  (both must stay green every step)
        ↓
Manual install of debug-apk on device → run Tests 1–8 above
```

Because the repo has no unit-test harness, each Phase 1.x step must keep both
APK builds green (compilation = the automated test), with behavior verified by
installing the debug APK. If any step breaks either build, fix-forward before
continuing — same loop used for the CI baseline. Rule of thumb: many small
green commits, never one giant unbuildable change.

---

## 16. Risks

| Risk | Why | Mitigation in the plan |
|---|---|---|
| AI ignores the tool protocol / hallucinates calls | Free-text parsing is fragile | Strict parser + unknown-tool error goes back to AI; retry limit; native function-calling later |
| Prompt bloat returns via tool results | Big `read_file`/`search` dumps | Hard caps everywhere (lines, results, output); trim old steps |
| Destructive mistake (`delete`, `rm`) | Agent + powerful tools | Block-list, confirmations, backups, PLAN default-off for danger; executor is the single chokepoint |
| Runaway loop / cost | Model keeps calling tools | Step budget (~15) + per-step retry cap + user cancel + timeouts |
| RAM spikes on big projects | Walks/loads | Depth caps, ignore rules (`build/`, `.gradle`), size caps, `Dispatchers.IO`, no persistent indexes |
| Build tool fights the IDE | Two builds at once / phone overheats | Reuse `isBuildInProgress` lock + `cancelCurrentBuild()`; build only on explicit request |
| Provider differences | 6 providers, 6 prompt quirks | One uniform text protocol first; per-provider prompt tweaks only if proven necessary |
| Scope creep (MCP/Skills/agents) | Tempting to build everything | Explicitly out; registry is designed so they can plug in later without rewrites |

---

## 17. Future Phases

- **Phase 2 — Native function-calling**: per-provider `tools` parameters
  (Gemini SDK first), replacing the text protocol where reliable; richer result
  types (diffs, file trees).
- **Phase 3 — Skills**: reusable markdown skill files the agent can load
  (e.g. "how to add a screen"), discovered via a `load_skill` tool.
- **Phase 4 — Sub-agents**: the controller spawning scoped child runs
  (researcher, builder) with their own budgets, reusing the same executor.
- **Phase 5 — MCP**: external tools over a client protocol, registered into the
  SAME `ToolRegistry`, so local and remote tools look identical to the agent.
- **Later**: embeddings/project index, background watchers, local-model option.

Nothing in Phase 1 blocks any of these: the registry, result envelope, executor
chokepoint, and mode plumbing are the exact seams they will attach to.

---

## 18. Beginner-Friendly Explanation

### What are we building?
A helper layer that lets the AI do small jobs inside your project step by step —
look at files, read them, search them, change them, build the app — instead of
guessing everything in one giant message.

### Why do we need it?
Right now the AI is like a cook who must write the whole recipe without opening
the fridge. Tools let it open the fridge, taste, and adjust — so it works on big
real projects without running out of memory.

### What is a tool?
One small job with a name and clear inputs, like `read_file` ("give me this
file's text"). The AI asks for it by name; the app does it and reports back.

### What is Tool Registry?
The menu of available tools: their names and when to use them. The app shows
this menu to the AI with every request.

### What is Tool Executor?
The careful waiter: it checks the AI's order (known tool? allowed? safe?),
runs it with a time limit, and brings back the result — or a clean error.

### How does the AI use a tool?
It writes a strict request block in its reply (like today's `FILE_TO_MODIFY:`
lines). The app parses it, runs the tool, pastes the result back, and asks the
AI "now what?" — repeating until the job is done (max ~15 rounds).

### What happens when a tool fails?
Nobody crashes. The failure becomes a normal message back to the AI
("file not found", "command timed out"), which then explains it to you or tries
something else. Dangerous tools additionally ask YOU for confirmation first.

### What is Build mode?
The AI may use every tool, including changing files and running builds — with
backups and confirmations for the dangerous parts.

### What is Plan mode?
Look-but-don't-touch: only reading/searching tools exist. The AI investigates
and returns a numbered plan. The app physically refuses any write, so your
project cannot change by accident.

### What will Phase 1 NOT include?
No MCP servers, no Skills files, no sub-agents, no new AI providers or SDKs, no
database, no local AI models, no redesign of the chat screen. Just the tool
foundation, reusing everything that already exists.

---

## 19. Phase 1 Definition of Done

- [ ] `Tool` interface + `ToolResult` envelope created (no behavior yet).
- [ ] `ToolRegistry` lists tools and renders their descriptions for prompts.
- [ ] `ToolExecutor` validates, permission-checks, times out, cancels, and
      returns structured errors — proven with a stub tool.
- [ ] `list_files`, `read_file`, `search_files` work on-device with all bounds
      respected (depth, lines, results, skips).
- [ ] `ToolCallParser` handles valid / malformed / chat-text inputs correctly.
- [ ] `AgentController` completes a scripted 2-step run (fake provider) and a
      live read-only run against a real provider.
- [ ] `write_file` / `edit_file` work in BUILD (backup + allow-list) and are
      refused in PLAN (disk unchanged, verified via Git).
- [ ] `delete_file` / `run_command` / `build_project` work behind confirmation,
      block-list, timeout, output cap, and the existing build lock.
- [ ] Chat shows each step in the activity card; cancel stops cleanly; step
      budget stops runaways; RAM stays flat on a large project.
- [ ] Old `executeRequest()` path still works as fallback (regression Test 8).
- [ ] Every step kept GitHub Actions green: Debug APK + Release APK artifacts.
- [ ] No MCP / Skills / sub-agents / new SDKs / DBs were added.
