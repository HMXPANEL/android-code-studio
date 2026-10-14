# Phase 3.2.2 — Missing Project-Local Session Writes: Diagnosis & Fix

**Date:** 2026-10-14
**Branch:** `main`
**Starting commit:** `2aadb1a`
**Fix commit:** `5d32551`
**Device evidence (user-reported, OPPO A38):** AI answer renders; after app
reopen History shows "No saved conversations yet"; inspected
`.opencode/sessions/` holds 0 files while `.opencode/` dirs exist.
APK provenance on that device is UNKNOWN (could predate current `main`).

## Confirmed root cause

**The default chat path never created a session.** `sendCurrentInput()`
(`fragments/sidebar/ArtificialFragment.kt:730-766`) routes BUILD-mode messages
to the legacy one-shot `executeRequest()` whenever the agent-loop toggle is
off — and the toggle defaults to off
(`isAgentLoopEnabled()`, same file:121-129, `getBoolean(KEY_LOOP_ENABLED, false)`).
The legacy path contained zero session calls: no coordinator, no session ID,
no store writes. A first message on a default install therefore leaves no
session file — exactly the reported symptom.

**Contributing defect:** `FileSessionStore` ran `mkdirs()` in its constructor
(`session/SessionStore.kt`, old line 164), so merely *opening* History (which
constructs a coordinator to list sessions) fabricated empty
`.opencode/sessions/` directories — exactly the reported "dirs exist, 0 files".

Ruled out by code evidence: the loop path already awaited persistence before
this fix; History queries the same per-project store the chat writes;
stable-ID filtering cannot reject records that were never written.

## Files and functions changed

| File | Change |
|------|--------|
| `fragments/sidebar/ArtificialFragment.kt` (`executeRequest`) | Resolve coordinator + `sessionIdFor(BUILD,…)`, await `addUserMessage` before the provider call, capture the delivered final text in `onSuccess`/`onTextResponse`, await `addAssistantMessage` after return; failures fall back to session-free + snackbar + sanitized `Log.d` (exception type only) |
| `session/SessionStore.kt` (`FileSessionStore`) | Directories created lazily on first write (`ensureSessionsDir`, clear error on failure); construction is side-effect-free; traversal guard and corrupt-reporting unchanged |
| `session/AgentSessionCoordinator.kt` | `lastErrorType` (exception simple name) tracked next to `lastError`; new `diagnostics()` snapshot: root SHA-256, dir exists/writable, file count, current-session message counts by role, error type — no content, paths, keys, or secrets |
| `test/.../session/LegacyChatPersistenceTest.kt` | **New (4 tests):** no dirs until first write; legacy-shaped flow round-trips through a new store instance with ordered roles; diagnostics accuracy + sanitization; failure-type reporting via throwing store double |

## Before/after path formulas

- Before: `executeRequest` → provider only. Write path: none. Read path (History):
  `<root>/.opencode/sessions/*.json` (missing when loop off) + in-memory mods.
- After: `executeRequest` → `sessionIdFor` → `<root>/.opencode/sessions/<uuid>.json`
  (`addUserMessage`, awaited) → provider → `addAssistantMessage` (awaited).
  Failed writes degrade visibly to the old behavior; nothing is claimed saved.

## Evidence the file contains both messages

`testLegacyFlowPersistsUserAndDeliveredAnswer`: session created, user record
awaited, final text recorded, then reloaded through a **new**
`FileSessionStore` instance — asserts 2 messages, `[USER, ASSISTANT]` order,
exact contents, listing count 1, row counts (2 msgs / 0 calls).

## Tests and CI

- New: 4 tests. Expected suite total: 201 + 4 = **205 tests** in
  `:core:app:testDebugUnitTest` (all deterministic, temp dirs, no credentials).
- Workflow `asm_build.yml` (unit tests + Debug + Release APKs): run
  **`38579358916`** (head `5d32551`) — **completed, success**; three BUILD
  SUCCESSFUL segments, **zero FAILED lines** → 205/205 pass
  (201 prior + 4 new).

## Remaining device verification (not performed — no device access here)

1. Install a build containing `5d32551` on the OPPO A38 (verify commit in
   About/build info first — the old APK may predate all session code).
2. With the loop toggle OFF (default), send a message; confirm a file appears
   under the open project's `.opencode/sessions/` containing both messages.
3. Reopen the app → History → Conversations lists it; resume restores bubbles.
4. Toggle the loop ON and repeat (loop path, unchanged behavior).
5. Toggle airplane mode mid-run: chat must still answer-or-error visibly, and
   must never claim a save that failed.

Do NOT mark this fixed on-device until steps 1–3 pass there.
