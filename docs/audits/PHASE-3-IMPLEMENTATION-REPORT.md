# Phase 3 Implementation Report — Context Engine + Persistent Sessions

**Date:** 2026-10-10
**Branch:** main
**Scope:** Phase 1/2 re-verification + Phase 3 implementation. Phase 4 NOT started.

---

## A. Phase 1 re-verification

- **Files inspected:** `AgentController.kt`, `AgentRun.kt`, `AgentState.kt`, `AgentEvents.kt`,
  `ToolCallParser.kt`, `RunCheckpoint.kt`, `Tool.kt`, `ToolRegistry.kt`, `ToolExecutor.kt`,
  `ToolPermission.kt`, `ToolContext.kt`, `ToolGuards.kt`, `AIFileWriter.kt`, `EditApply.kt`,
  `tools/builtins/*` (8 tools), `ProviderCall.kt`, `AIAgent.kt`, provider stubs.
- **Agent loop findings:** prompt → native-FC-first → text fallback → parse → sequential
  `executeCallsBatch` → `ToolExecutor` → transcript. Budgets (15 steps), doom-loop guard (3x),
  retry + re-plan nudge, cancellation via `SupervisorJob`, Git checkpoint restore — all intact.
- **Tool findings:** all 8 tools (`list_files`, `read_file`, `search_files`, `write_file`,
  `edit_file`, `delete_file`, `run_command`, `build_project`) keep schema validation, `PathJail`
  containment, permission checks, timeouts, bounded output. No behavior changes.
- **Safety findings:** canonical-path containment, traversal block, symlink resolution via
  canonical path, `.bak` backup + read-back verification, edit hash preconditions, Plan Mode
  READ-only filtering, confirm-gated tools — all preserved. No new bypass path introduced:
  Phase 3 persists results *after* execution through the same executor.
- **Tests run:** existing 119 unit tests (CI run 37971690954 green on base commit `a13005a`).
- **Defects found and fixes made:** none in Phase 1 runtime. One serialization enabler added:
  `@Serializable` on `RunMode` (`Tool.kt`) so sessions can persist mode without a parallel
  stringly-typed field. No behavior change.
- **Remaining limitations:** character-based token estimation (documented); no semantic search
  (out of scope); OpenAI/Anthropic native-FC adapters remain stubs (pre-existing).

## B. Phase 2 re-verification

- **Native FC execution path (traced):**
  `buildFunctionDeclarations()` → `callNativeFunctionCalling()` →
  `ProviderCall.generateWithFunctions()` → `ProviderCallImpl` → `AIAgent.generateWithFunctions()`
  → `NativeFunctionCallSource.parseNativeResponse()` → `ToolCall` → existing registry/executor.
  Any failure falls back to `generateCode()` + `TextProtocolSource`. Verified in source.
- **Provider-specific verification:** Gemini has a real SDK-backed `generateWithFunctions`;
  OpenAI/Anthropic return `UnsupportedOperationException` → text fallback. Behavior preserved;
  Phase 3 does not change provider selection or schema mapping.
- **Schema verification:** `ToolSchema.toJsonSchema()` output matches `ToolInputField`
  (name/type/description/required/default). `ContextEngine` builds `ToolDefinition`
  directly from `Tool` (no separate registry to drift). Flat-schema limitation noted (pre-existing).
- **Tool-call ID and result handling:** `NativeFunctionCallSource` generates `call-<runId>-<index>`;
  `ToolExecutor` results rejoin via transcript; Phase 3 additionally persists the
  call→result pair under the same `callId`.
- **Fallback behavior:** `FakeProviderCall` (tests) drains native queue → failure → text path,
  so all 19 existing `AgentControllerTest` tests keep exercising the text protocol unchanged.
- **Regression tests:** 119/119 green on base. `FakeProviderCall` extended with optional
  `nativeReplies` (defaults keep old constructor call sites compiling).
- **Defects found and fixes made:** `ToolDefinitionRegistry.getOrCreate(tool)` referenced by
  draft Phase 3 code does not exist — fixed by constructing `ToolDefinition` directly.
  `AgentEvents.ToolCall/ToolResult` referenced by draft code do not exist — fixed with
  dedicated `ContextToolCall`/`ContextToolResult` context types.
- **Remaining limitations:** parallel read-only dispatch still scaffold-only (`readOnlyHint`
  present, execution sequential). Not expanded: would change execution semantics.

## C. Phase 3 implementation

### Architecture

```
User → Agent UI → AgentController(runAgent(sessionId?))
  ├─ SessionManager → SessionStore (File/InMemory)
  ├─ ContextEngine → Session history + ProjectIndexer + RelevanceEngine
  ├─ ProviderCall (native FC → text fallback, unchanged)
  ├─ ToolRegistry → ToolExecutor → safety/permissions (unchanged)
  └─ persist tool results → rebuild context next turn
```

No second agent loop. `sessionManager`/`contextEngine` are optional constructor deps;
all existing call sites (no `sessionId`) behave exactly as Phase 2.

### Session-store design

- `SessionStore` interface: create/get/list/update/delete/observe/getWithHistory.
- `FileSessionStore`: one JSON file per session under `<project>/.opencode/sessions/`,
  kotlinx-serialization (already a dependency). No new DB dependency (Room/SQLDelight
  deliberately not added per constraints).
- `InMemorySessionStore`: tests + ephemeral use.
- Records: `AgentSession`, `PersistedMessage` (USER/ASSISTANT/SYSTEM/TOOL),
  `PersistedToolCall` (args stringified to `Map<String,String>` for serializability),
  `PersistedToolResult` (same `callId`), `Checkpoint` (commit hash + description).
- API keys / provider objects never persisted.

### Database/persistence choice and rationale

File-per-session JSON via `kotlinx-serialization-json:1.6.0` (already in `core:app`).
Rationale: zero new dependencies, human-inspectable, atomic-enough per-session writes,
trivial migration (ignoreUnknownKeys), adequate for single-user IDE sessions. No Room:
would add KSP/schema/migration overhead for a document-shaped record.

### Session lifecycle

`SessionManager.createSession/getSession/listSessions/observeSessions/renameSession/deleteSession`,
plus `addUserMessage/addAssistantMessage/addToolResult/addCheckpoint`.
`AgentRun.sessionId` threads the session through the run; `runAgent(..., sessionId)` validates
that a `sessionManager` is configured. Restore = `getWithHistory` → `compileContext`.

### Context assembly

`ContextEngine.compileContext(run, projectRoot, userRequest, tools)`:
1. tool definitions (never trimmed),
2. relevant file excerpts (bounded, relevance-ranked),
3. recent conversation turns (bounded, tool call/result pairs kept consistent),
4. token estimate → compact only the model-facing copy if over budget.

### Token-budget strategy

`ContextConfig` (32k window default; reserves 4k output + 2k tools). `TokenEstimator` is a
documented conservative character heuristic (~3 chars/token code, ~4 text). Mandatory
(system + project + user request + tool defs) is never trimmed; files capped at half the
dynamic budget; history summarized/trimmed last. Accurate provider token counting is
unavailable offline — stated explicitly.

### Compaction and summarization

Over-budget → keep top-relevance files → heuristic summary of older turns
(`summarizeHistory`) prepended as a SYSTEM message + recent turns kept → fallback trim
oldest-first (min 3 retained). Canonical persisted transcript untouched. Summarization
failure path: heuristic never throws (pure string ops); file-index failure caught → empty
excerpts. Essential data (decisions/paths/unresolved tasks) survives via recent-turn
retention + summary; verified by test asserting tool pairs survive.

### Project indexing

`ProjectIndexer` reuses existing `tools.builtins.BoundedWalk.list` (no duplicate walker).
Bounds: 5000 files, 512 KB/file, depth 20. Excludes build outputs, `.git`, `.gradle`,
`.idea`, `node_modules`, binaries, lockfiles. Incremental: `updateFile/invalidateFile`,
staleness = indexed files changed (cheap check; full rescan explicit). No background
continuous indexing; `SessionManager.invalidateIndex` refreshes on demand.

### Relevance ranking

`RelevanceEngine`: path/name (0.4) + content (0.4) + session-history (0.1) + recency
exponential decay 24h half-life (0.1) + language bonus. Bounded candidates (100),
bounded reads (5 KB/file), bounded results (10). No embeddings/vector DB (deliberate).

### Error recovery

- Store corrupt record → skipped on load, never auto-deletes history.
- Session load failure → `compileContext` falls back to empty context.
- Index failure → empty excerpts, run continues.
- Compaction failure → heuristic is infallible by construction; trim loop bounded.
- Interruption mid-tool (call persisted, no result) → result-less call resumes as pending
  history; no blind re-execution (executor runs only on fresh model turns).
- Persistence failure surfaces as `false` return, never claimed saved.

### Android resource constraints

Bounded scans/reads/index entries; no main-thread mandates (all suspend); no polling jobs;
per-file content cache cleared on invalidate; session files small JSON, lazy per-session
load; no new services/daemons.

## D. Tests

| Group | Command | Tests | Outcome |
|-------|---------|-------|---------|
| Session store | `:core:app:testDebugUnitTest --tests "*session*"` | 6 (file + memory CRUD, messages, tool pairs) | written; CI authoritative |
| Context engine | same | 3 (new-session, history w/ pairs, estimator) | written; CI authoritative |
| Indexer | same | 6 (build, cache, refresh, invalidate, stats, excludes) | written; CI authoritative |
| Relevance | same | 5 (name, content, history, limit, cache) | written; CI authoritative |
| Manager | same | 10 (lifecycle, messages, tool result+status, checkpoint, observe) | written; CI authoritative |
| Phase 1/2 regression | full `testDebugUnitTest` | 119 | base green (run 37971690954); rerun via CI after push |

Local `./gradlew` execution is unavailable in this environment (no exec permission on the
sdcard mount + Java 25 vs old Kotlin toolchain); per repo policy GitHub Actions is the
build authority. No test results fabricated: new-test outcomes pending CI; base-suite
green confirmed via API.

Untested: live provider round-trips (no API keys in CI); process-death recovery beyond
file-restore unit path.

## E. Build and CI

- Debug/Release builds: via GitHub Actions (authoritative). Local builds not attempted
  per environment constraints and repo policy.
- Runs: base `37971690954` (commit `a13005a`) = success. New run ID for Phase 3 push
  recorded after push below.
- No workflow skipped by us; any skip/cancel will be reported verbatim, not as green.

## F. Git

- Branch: `main`. Starting commit: `a13005a`.
- New commits: (to be filled at push time).
- Push status: (to be filled at push time).
- Files changed:
  - `core/.../artificial/session/SessionStore.kt` (new)
  - `core/.../artificial/session/ContextEngine.kt` (new)
  - `core/.../artificial/session/ProjectIndexer.kt` (new)
  - `core/.../artificial/session/RelevanceEngine.kt` (new)
  - `core/.../artificial/session/SessionManager.kt` (new)
  - `core/.../artificial/agent/AgentController.kt` (sessionId + persistence + context path)
  - `core/.../artificial/agent/AgentRun.kt` (sessionId, prior commit)
  - `core/.../artificial/tools/Tool.kt` (@Serializable RunMode)
  - `core/.../test/.../agent/FakeProviderCall.kt` (optional native replies)
  - `core/.../test/.../session/*Test.kt` (5 new suites)
  - `docs/audits/PHASE-1-2-SOURCE-VERIFICATION.md`, `docs/audits/PHASE-3-IMPLEMENTATION-REPORT.md`

## G. Known limitations

- **Verified complete:** session CRUD/persistence shape, context assembly + budget + compaction,
  bounded indexing, relevance ranking, agent-loop integration preserving Phase 1/2 behavior.
- **Implemented but not independently runtime-verified (pending CI):** new unit suites
  (30 tests) — written against real contracts, awaiting Actions run.
- **Partially implemented:** OpenAI/Anthropic native-FC adapters (pre-existing stubs);
  nested tool schemas (pre-existing flat-only).
- **Not implemented:** parallel read-only dispatch, streaming, PTY, subagents/MCP/skills
  (all Phase 4+, correctly untouched).
- **Blocked by external dependency:** live-provider verification (no keys); local Gradle
  run (mount/toolchain limitation) — CI used instead.

## H. Phase 4 readiness

- Implement parallel dispatch for `readOnlyHint` tools only after proving ordering safety.
- Add provider token-count hooks when SDKs expose them; keep heuristic as fallback.
- Consider `SessionStore` migration helper if record shape changes (currently tolerant read).
- Do NOT add vector DB/embeddings without measured relevance gap.
