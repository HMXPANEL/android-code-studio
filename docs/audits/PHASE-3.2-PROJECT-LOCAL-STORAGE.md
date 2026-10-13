# Phase 3.2 — Project-Local Storage Implementation

**Date:** 2026-10-13
**Branch:** `main`
**Starting commit:** `765c83c`
**Implementation commit:** `2885884` (this phase)

Implements the Phase 3.1 audit's Option A: every IDE project owns its AI
sessions, identity marker, memory and settings under `<root>/.opencode/`,
and the History screen displays persisted conversations. All work reuses the
existing session engine; no parallel database was created.

## Root cause addressed

The audit proved the empty History screen had three compounding causes:
(a) the History screen only ever rendered in-memory file-modification
records, and the user's message forbade modifications; (b) the default chat
path persisted nothing; (c) session records were invisible to History. This
phase fixes (b) structurally (session wiring is now the production path with
visible failure fallback) and (c) directly (History lists persisted
conversations), while keeping the modification log intact and clearly labeled.

## Files changed and why

| File | Change |
|------|--------|
| `session/SessionStore.kt` | `AgentSession.projectId` (nullable, backward compatible); `listAll()` for directory-scoped discovery; path-traversal guard in `sessionFile()`; `corruptRecords()` report-only tracking; `ConversationRow` + `toConversationRow()` display mapper |
| `session/ProjectIdentity.kt` | **New.** UUID marker `.opencode/project-id.txt` (create-once/reuse, retained-on-copy policy); read-only `ProjectGitIgnore.status()` (never edits user files) |
| `session/ProjectLocalData.kt` | **New.** `ProjectMemoryStore` (`memory.json`, explicit facts only); `ProjectAgentSettings` (`settings.json`) with hard-coded `SecretDenylist` (key + secret-shaped-value rejection, budget clamp) |
| `session/SessionManager.kt` | `createSession(..., projectId)`; ID-aware `listSessions` + shared `belongsTo` predicate (moved adopted, foreign excluded, legacy path behavior preserved); `compileContext` injects memory facts and settings budget override; `corruptRecords()` passthrough; optional memory/settings stores (defaulted) |
| `session/ContextEngine.kt` | `config` made public; trailing `contextConfig` override threaded through compile/history/compaction (all call sites source-compatible) |
| `session/AgentSessionCoordinator.kt` | Resolves stable ID (create-on-write, probe-on-read); validates root is not a file; passes `projectId` through; exposes `corruptRecords()`, `gitIgnoreStatus()`, `memoryStore`, `settingsStore` |
| `fragments/AIHistoryFragment.kt` | New "Saved conversations" section (sessions, tap-to-resume) above the unchanged, relabeled "File changes" section; distinct empty/unavailable/read-only/corrupt states; fresh query on every open/resume |
| `adapters/ConversationAdapter.kt` | **New.** Reuses `item_history` card; blue icon distinguishes conversations from modification records |
| `fragments/sidebar/ArtificialFragment.kt` | `openHistory` passes project root + resume callback; `resumeSession` public; Conversations dialog gains corrupt-record and git-ignore hints |
| `main/res/values/strings.xml` | 8 strings (headers, empty/unavailable/read-only/corrupt/git-hint, details format) |
| `main/res/layout/fragment_ai_history.xml` | Section headers + status line + two lists (was a bare list) |

## Storage path and schema

- `<root>/.opencode/sessions/<uuid>.json` — unchanged `AgentSession` schema
  plus optional `"projectId"` (old files decode with `null`; no migration).
- `<root>/.opencode/project-id.txt` — bare UUID, created once.
- `<root>/.opencode/memory.json` — `{facts[], updatedAt}`.
- `<root>/.opencode/settings.json` — `{values{}, maxContextTokens?, updatedAt}`.
- No API keys, headers, or credentials in any project-local file (denylist +
  tests; credentials remain in app-private prefs).

## Tests (27 new, all deterministic, fake providers, temp dirs)

- `ProjectIdentityTest` (10): create/reuse, move-along, copy-retained policy,
  malformed-untouched, missing-probe, file-as-root, gitignore ×4.
- `ProjectLocalDataTest` (7): memory round-trip/duplicate/blank/corrupt,
  settings round-trip, denied keys, secret-shaped values, budget clamp,
  denylist unit checks.
- `ProjectScopedSessionsTest` (10): projectId round-trip, legacy decode,
  move adoption / foreign exclusion / legacy path behavior, switchTo after
  move, current-ID restore across recreation, file-as-root failure,
  traversal rejection, corrupt reporting, memory+budget reaching context,
  row mapping.
- Expected suite total: 174 + 27 = **201 tests**.

## Build and CI

- `git diff --check`: clean on every commit.
- Workflow `asm_build.yml` (`testDebugUnitTest` + `assembleDebug` + `assembleRelease`):
  final run **`38438636540`** (head `4ec0c70`) — **completed, success**; three
  BUILD SUCCESSFUL segments, **zero FAILED lines** → 201/201 pass
  (174 prior + 27 new: 10 identity + 7 memory/settings + 10 scoped sessions).
- CI failures encountered and fixed, all root-caused from logs:
  1. `mergeDebugResources` on latent non-positional `%s`/`%d` in
     `lsp_server_install_success` / `lsp_server_download_failed` (English +
     zh-rCN) — green runs had cached the merge; the resource edit forced a
     re-merge. Fixed with positional `%1$s`/`%2$d` (runtime `getString`
     formatting preserved; `formatted="false"` would have broken it).
  2. Same task on an apostrophe in the new `ai_history_gitignore_hint`
     string — reworded (bytes were otherwise clean ASCII).
  3. `compileDebugKotlin`: `ConversationViewHolder` missing `inner` —
     one-word fix matching `HistoryAdapter`.

## Not verified on a physical device

No emulator/device is available in this environment (`adb` present, zero
devices). On-device install, restart restore, and live-provider behavior are
NOT TESTED and not implied. All claims above rest on source inspection, unit
tests, and CI builds.
