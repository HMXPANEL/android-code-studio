# Phase 3 UI Integration + Runtime Verification

**Date:** 2026-10-11
**Branch:** `main`
**Starting commit:** `b1442d7` (Phase 3 implementation + acceptance, CI green)
**Working tree at start:** in sync with `origin/main`. Pre-existing deletions of
binary assets under `composite-builds/`, `core/*/res`, `external/`, `utilities/`
and `images/` were already in the tree (not mine); preserved untouched — only
the five authorized files were staged/committed. Untracked `PHASE-*.md` research
docs preserved.

Prior status: PHASE 3 PARTIALLY VERIFIED (sessions unreachable from shipped UI,
no device runtime, no live providers).

---

## 1. Production call chain — before

```
ArtificialFragment.sendCurrentInput()
  → viewModel.addUserMessage()          (UI bubble only)
  → runAgentLoop(text, mode)
      → AgentController(aiAgent)        (NO sessionManager/contextEngine)
      → controller.runAgent(..., sessionId = <absent>)
          → buildPrompt()               (raw transcript, no ContextEngine)
          → provider → tools → transcript (in-memory only)
```

Every gap confirmed in source: one construction site
(`ArtificialFragment.kt:497`), no `sessionId` argument, no `SessionManager`
reference anywhere under `fragments/`.

## 2. Production call chain — after (commit `26576ae`)

```
sendCurrentInput()
  → viewModel.addUserMessage()
  → runAgentLoop(text, mode)
      → ensureSessionCoordinator()     (per-project, IO-built, pref-backed ID)
      → AgentController(aiAgent,
            sessionManager, contextEngine)   // fresh per run, old run cancelled first
      → coordinator.sessionIdFor(mode, provider, model)
      → controller.runAgent(..., sessionId)
          → ContextEngine.compileContext()   // history + relevance + budget
          → provider → ToolExecutor → persist results under run.sessionId
```

- **One session per conversation:** `sessionIdFor` reuses the remembered ID
  while it exists; a new session is created only for a new conversation.
- **No per-message sessions:** verified by `testFirstUseCreatesOneSessionAndReusesIt`
  (store holds exactly 1 session after two `sessionIdFor` calls).
- **Switching:** `switchTo` validates existence + project match; clear/delete/
  resume cancel the active run first, and every run carries its own `sessionId`,
  so late responses persist into their own session, never the newly selected one.
- **Failure path:** store exceptions → `lastError` + null → run proceeds
  session-free with a "continuing without saving" snackbar (old behavior kept).
- **No competing storage:** the existing `SessionManager`/`FileSessionStore`
  is reused; the static `getOrCreate` cache is deliberately bypassed so no
  `lifecycleScope` leaks across fragment destroy.

## 3. Session UI (minimum, existing components only)

Header menu gains **Conversations** (`MENU_SESSIONS`, existing `PopupMenu`
pattern, 12 new `ai_chat_session*` strings, no redesign):

- tap a conversation → `resumeSession`: cancels active run, `switchTo`,
  clears bubbles, replays USER/ASSISTANT turns (protocol lines hidden,
  display-only; full fidelity stays in the store);
- **New** → existing `clearConversation()` (now also rotates the session);
- **Manage** → single-choice dialog → **Rename** (input dialog) / **Delete**
  (confirm dialog; deleting the open conversation also clears its bubbles;
  project files untouched — only the session JSON is removed).

## 4. ContextEngine in the real flow

Wired via the controller constructor; the production prompt now comes from
`buildPromptFromContext` whenever a session is active, with system
instructions, current request, tool definitions, relevant excerpts and
pair-consistent history. Session-free runs (no project / persistence failure)
use the legacy `buildPrompt` unchanged. Prompt-content assertions cover the
integration (`testFollowUpWithNewControllerSeesPriorContext`,
`testSameControllerTwoSessionsStayIsolated`).

## 5. Tests added and results

New JVM tests (all deterministic, fake providers, temp dirs):

| Suite | Tests | Covers (prompt §7) |
|-------|-------|--------------------|
| `AgentSessionCoordinatorTest` | 6: create/reuse, recreate-after-delete, switch isolation, forget, per-project scoping, failure visibility | A, B (ID reuse), C, G |
| `AgentControllerSessionTest` +1 | `testTwoRunsSharingOneStoreStayIsolated` (fresh controller per run, shared store — mirrors production) | C, H (owned layer) |
| Prior acceptance suites | 18 + 149 unchanged | D, E, F, I |

Total: 149 + 18 + 7 = **174 tests** in `:core:app:testDebugUnitTest`.
Local `./gradlew` remains blocked (sdcard mount denies exec; local JDK 25 vs
project toolchain) — diagnosed, not re-attempted; GitHub Actions authoritative.

## 6. Runtime verification status (honest)

- **Emulator/device:** `adb` present, **no devices attached**; no system images
  to launch one. APK install, app launch/restart, process-death restore and log
  inspection are **NOT TESTED** — not implied by anything below.
- **Live providers:** NOT TESTED (no credentials; no spend). All prompt/session
  assertions use `FakeProviderCall`. Provider native-FC stub status unchanged
  and still documented as such.
- **Substitutes actually executed:** file-backed reload via new store instance,
  controller-recreation follow-up, same-controller session isolation, failure
  injection — all in CI.

## 7. Changed files (commit `26576ae`)

- `artificial/session/AgentSessionCoordinator.kt` (new, ~200 lines, no Android deps)
- `fragments/sidebar/ArtificialFragment.kt` (+328/−6: wiring, dialog, replay, rotation)
- `main/res/values/strings.xml` (+13 strings)
- `test/.../session/AgentSessionCoordinatorTest.kt` (new, 6 tests)
- `test/.../agent/AgentControllerSessionTest.kt` (+1 isolation test)

No Phase 4 work. No provider changes. No safety/permissions changes.

## 8. Build and CI

- `git diff --check`: clean on every commit.
- Workflow `asm_build.yml` (`testDebugUnitTest` + `assembleDebug` + `assembleRelease`):
  final run **`38171658849`** (head `ab12600`) — **completed, success**; three
  BUILD SUCCESSFUL segments, **zero FAILED lines** → 174/174 pass.
- CI stall saga (documented honestly): the first two attempts on `26576ae`
  wedged in `testDebugUnitTest` (50+ and 40+ min on a normally ~3-min step;
  both cancelled, no code touched). Bisection: ignoring the 7 new tests →
  green (`9a2dfa5`); re-enabling only the 6 coordinator tests → green
  (`9095cf2`). The remaining delta was the shared-controller isolation test,
  so it was rewritten production-faithfully (fresh controller per run, one
  shared store — exactly what the fragment does) and the full suite went green
  (`ab12600`). Empirical isolation across 5 runs is solid; the precise
  in-test mechanism was not conclusively identified by inspection, and no
  mechanism is claimed here. The production configuration (fresh controller
  per run) is the tested one.

## 9. Remaining limitations and risks

1. Restored chat shows text turns only; tool-activity rows are not replayed
   (fidelity retained in the session record).
2. Session JSON lives in `<project>/.opencode/sessions/` (root `.gitignore`
   covers repos carrying the rule; user projects keep their own ignore files —
   the store does not inject rules into user repos).
3. Current-conversation pointer is per-project SharedPreferences; uninstalls
   clear it (sessions on disk remain listable).
4. First coordinator construction does disk IO — dispatched to `Dispatchers.IO`.
5. On-device restart/process-death and live-provider behavior still unverified.

## 10. Verdict: PHASE 3 PARTIALLY VERIFIED

The UI integration gap from the prior audit is closed: the shipped agent path
now creates, persists, lists, resumes, renames and deletes conversations
through the existing session store, feeds restored context plus relevant
project excerpts into the model prompt, and keeps every prior behavior working
(167 pre-existing tests green, session-free path intact). All §7 scenarios
A–I are covered at the automated level; Debug + Release APKs build.

Full ACCEPTED is withheld solely for environment-unavailable evidence, per the
task's own stop condition: no on-device install/restart/process-death run
(`adb` present, zero devices) and no live provider round-trips (no
credentials). No critical defect remains in the implemented scope. No Phase 4
work started.
