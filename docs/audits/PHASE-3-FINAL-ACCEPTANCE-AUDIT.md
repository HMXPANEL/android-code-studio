# Phase 3 Final Acceptance Audit — Runtime Verification

**Date:** 2026-10-11
**Repository:** https://github.com/HMXPANEL/android-code-studio.git
**Branch:** `main`
**Starting commit:** `ae6d0a0` (Phase 3 implementation + reports, CI green)
**Working tree at start:** clean vs `origin/main`; only pre-existing untracked
`PHASE-*.md` research docs (left untouched throughout).

This audit independently verifies Phases 1–3 against real code, real tests and
real CI output. Evidence levels are kept distinct: source inspection ≠ unit
tests ≠ integration tests ≠ live API ≠ on-device runtime ≠ CI builds.

---

## 1. Baseline and scope check

- `git status --short --branch` → `## main...origin/main`, no modifications.
- Reported Phase 3 commit `8255edd` and follow-ups (`af98f19`, `0e068f6`,
  `c17e759`, `ae6d0a0`) all present in history.
- Phase 3 files present: `artificial/session/{SessionStore,SessionManager,
  ContextEngine,ProjectIndexer,RelevanceEngine}.kt`, 5 session test suites,
  `AgentRun.sessionId`, `AgentController(sessionId)` integration,
  `Tool.@Serializable RunMode`, `FakeProviderCall` native replies.
- Prior reports read: `docs/audits/PHASE-1-2-SOURCE-VERIFICATION.md`,
  `docs/audits/PHASE-3-IMPLEMENTATION-REPORT.md`. **One claim in the Phase 3
  report is wrong** (see §5): no provider implements `generateWithFunctions`.
  Corrected below; history not rewritten.

## 2. Phase 1/2 regression verification — PASS

**Inspected:** `AgentController`, `AgentRun`, `AgentState`, `ToolRegistry`,
`ToolExecutor`, `ToolPermission`, `ProviderCall`/`ProviderCallImpl`, `AIAgent`,
`NativeFunctionCallSource`, `TextProtocolSource`, `PathJail` (via `GuardsTest`),
`AIFileWriter`/`EditApply` (via `WriteVerificationTest`), all 8 built-ins.

**Existing suites (all CI-green, behavior-asserting, not construction-only):**

| Suite | What it actually proves |
|-------|-------------------------|
| `AgentControllerTest` (19) | loop, budgets, doom-loop, batch order, PLAN refusal, prompt contract |
| `PlanModeDiskUnchangedTest` (3) | PLAN mode performs no disk writes |
| `CancellationTest` (2) | cancellation + doom-loop guard |
| `ToolCallParserTest` (6) | text-protocol parsing incl. malformed blocks |
| `GuardsTest`, `ToolExecutorTest`, `ToolRegistryPermissionTest`, `WriteVerificationTest`, `builtins/*` | path jail, permissions, backup/verify, schemas, execution |

- Tool calls reach the existing registry/executor; no parallel path was added.
- Plan Mode write refusal, backup+read-back verification, stale-hash edit
  rejection, command timeout/cancellation, call-ID/result association and
  text-protocol fallback are covered by the above suites — all passing.
- No Phase 1/2 defects found in this audit. No Phase 1/2 code touched
  (except the earlier `@Serializable RunMode`, retained).

## 3. Session persistence — PASS (unit + integration; process-death NOT TESTED)

**Inspected:** `SessionStore`, `FileSessionStore`, `InMemorySessionStore`,
`SessionManager`, `AgentSession`, `PersistedMessage`, `PersistedToolCall`,
`PersistedToolResult`, controller/store integration points.

- **Test A (lifecycle):** PASS via `SessionManagerTest` (10 tests: create, get,
  list, rename, delete, user/assistant messages, tool results + status,
  checkpoints, observe).
- **Test B (disk reload, new store instance):** PASS via new
  `SessionPersistenceTest.testReloadViaNewStoreInstanceReadsFromDisk` — writes
  through store #1, asserts the JSON file exists on disk, loads through a new
  `FileSessionStore` and verifies IDs, ordering, arguments, results, metadata,
  timestamps.
- **Test C (controller recreation + follow-up):** PASS via new
  `AgentControllerSessionTest.testFollowUpWithNewControllerSeesPriorContext` —
  run 1 persists a final answer; a brand-new controller on the same store
  receives it in the follow-up prompt (asserted on the recorded prompt).
  Full Android process-death is NOT TESTED (no emulator/device available);
  file-restore + controller-recreation is the verified substitute.
- **Test D (interrupted tool call):** PASS — persisted call without result stays
  `PENDING`, recovery records exactly one result with no re-execution triggered
  by the store layer.
- **Test E (corruption/storage errors):** PASS — malformed JSON skipped without
  touching valid sessions; missing IDs return null / `false` (never false
  success); unknown fields tolerated (`ignoreUnknownKeys`).
- Tool-call/result `callId` pairing survives restoration (asserted end-to-end
  through `runAgent` in `testSessionPersistsUserToolResultAndFinalAcrossTurn`).

## 4. Session data security — PASS with one fix

- Session records contain messages, tool args/results, checkpoints, metadata.
  No API keys, auth headers or provider objects are written (verified by
  inspection of `AgentSession` and all `add*` paths).
- **Defect (fixed):** session files live under `<project>/.opencode/sessions/`
  and no ignore rule existed — user sessions could be committed accidentally.
  Fix: `.opencode/` appended to root `.gitignore` (commit `f58cd39`).
  Limitation: this covers repos that include this rule; user projects opened in
  the IDE carry their own `.gitignore`. The store deliberately does **not**
  inject ignore rules into user repos. Documented, not silently worked around.
- Test projects use isolated `createTempDirectory` dirs; no user data touched.

## 5. Provider verification — CORRECTED, live tests NOT TESTED

- Source check (`agents/` tree): the **only** `generateWithFunctions`
  implementation is the `AIAgent` interface default, which throws
  `UnsupportedOperationException`. **Neither Gemini, OpenAI, Anthropic, nor any
  other agent overrides it** (`Gemini.kt` verified: 408 lines, no override).
- Consequence: in production today, `ProviderCallImpl` always fails the native
  call and the controller always takes the text-protocol fallback. The Phase 3
  report's "Gemini real SDK-backed native FC" claim is **retracted**; the native
  path (declarations → `NativeFunctionCallSource` → executor) is verified only
  through `FakeProviderCall` native replies in unit tests.
- Unsupported providers fall back safely (no crash path; covered by every
  existing controller test, which all run with an empty native queue).
- Live round-trips: NOT TESTED (no credentials in this environment; no spend).

## 6. Context engine — PASS

**Inspected:** `ContextEngine`, `ContextConfig`, `TokenEstimator`,
`buildPromptFromContext`, compaction paths.

- New `ContextCompactionTest` (4 tests), all passing in CI:
  - over-budget → `wasCompacted`, history shrinks, system instructions / user
    request / tool definitions intact, every surviving result keeps its call;
  - short conversation → no compaction, `compactionSummary == null`;
  - orphaned result → synthetic call re-attached (provider-safe sequences);
  - severe pressure (budget < mandatory) → keeps last ≤3 messages + mandatory.
- Canonical history retention asserted against the store after compaction.
- Token strategy is the documented character heuristic (~3 chars/token code,
  ~4 text) — verified as documented, not as exact provider counting.
- Summarization is a pure-string heuristic (infallible by construction);
  failure fallbacks covered by the severe-pressure path and the
  index-failure → empty-excerpts catch in `compileContext`.

## 7. Indexing and relevance — PASS (no semantic search, as designed)

**Inspected:** `ProjectIndexer`, `RelevanceEngine`, `tools.builtins.BoundedWalk`,
cache/invalidation/refresh paths, `ContextEngine` integration.

- New `ProjectIndexRefreshTest` (6 tests): modified-file auto-detect without
  refresh; new-file invisibility until explicit refresh (documented stale-index
  behavior — no background watcher claimed); deleted-file removal;
  `invalidateFile`/`updateFile` proven to add/remove real entries;
  `id_rsa`/`.pem` excluded while `.txt` indexed (allowlist-based);
  outside-root files never indexed.
- Relevance is keyword/path/recency ranking (`RelevanceEngineTest`, 5 tests) —
  no embeddings/vector claims made or needed.
- Index failure → empty excerpts with the run continuing (verified by code path
  + `compileContext` catch); flagged in diagnostics via `wasCompacted=false`
  empty-excerpt context rather than invented facts.

## 8. Real UI and DI integration — GAP (reported, not rewired)

- Traced production path: `ArtificialFragment.runAgentLoop` is the **only**
  production `AgentController` construction site
  (`AgentController(aiAgent)`), and its `runAgent(...)` call passes **no**
  `sessionId`, `SessionManager` or `ContextEngine`.
- Therefore persistent sessions and context-aware prompts are **not reachable
  from the shipped UI today**; every production run uses the Phase 1/2
  transcript path unchanged. Session support is optional by design, so this is
  reported as an integration gap, not a regression: no user-facing persistent
  sessions may be claimed from unit tests alone.
- Deliberately **not** auto-wired: silently creating `.opencode/sessions` in
  user projects on every run (disk writes, new lifecycle ownership, no UI
  affordances for list/restore) would be a behavior change requiring on-device
  verification that is unavailable here. Wiring + session UI belongs to a
  follow-up with device testing.
- Flows without `sessionId` verified unaffected
  (`testRunWithoutSessionIdLeavesStoreUntouched` + all 119 legacy tests).

## 9. Test strategy and counts

- Local `./gradlew` remains blocked in this environment (sdcard mount denies
  exec permission; local JDK 25 vs project Kotlin toolchain) — diagnosed, not
  re-attempted blindly. GitHub Actions is the execution authority per repo
  policy.
- New acceptance tests added (18): `SessionPersistenceTest` (4),
  `ContextCompactionTest` (4), `ProjectIndexRefreshTest` (6),
  `AgentControllerSessionTest` (4).
- Expected total: 149 existing + 18 new = **167 tests** in
  `:core:app:testDebugUnitTest`. CI outcome: run `38142089080` — **completed,
  success**; log shows three BUILD SUCCESSFUL segments (assembleDebug,
  testDebugUnitTest, assembleRelease) and **zero FAILED lines**, i.e. 167/167
  pass (18 new-test count verified in source: 4+4+6+4).

## 10. APK and runtime verification — CI builds PASS; device NOT TESTED

- Debug + Release APK assemblies run in CI (workflow `asm_build.yml`).
- No emulator/device exists in this environment: install, launch, restart and
  log-inspection steps were **not executed** and are marked NOT TESTED, not
  implied.

## 11. GitHub and CI

- Acceptance commit `f58cd39` pushed to `origin/main` (5 files, `diff --check`
  clean). No user changes discarded (only pre-existing untracked docs remain).
- CI run for this audit: **`38142089080` — completed, success** (head `f58cd39`,
  job `114477849667`). Debug + Release assemblies BUILD SUCCESSFUL; unit-test
  task green with no failures.
- Prior runs: `38071562097` success (`8255edd`, 149/149, Debug+Release green);
  three earlier Phase 3 failures root-caused and fixed (see implementation report).

## 12. Acceptance checklist

Phase 1/2
- [x] Agent loop regression verified.
- [x] Eight built-in tools regression verified.
- [x] Permission and file-safety tests pass.
- [x] Native function-calling mapping verified (test-level; providers stubbed).
- [x] Text-protocol fallback verified.
- [x] Provider limitations accurately documented (stub status corrected).

Phase 3 persistence
- [x] Session lifecycle verified.
- [x] File-backed persistence verified with a new store instance.
- [x] Tool-call/result associations survive restoration.
- [x] Controller recreation and follow-up conversation verified.
- [x] Interrupted tool calls handled safely.
- [x] Corrupt-record and storage-failure behavior verified.
- [x] No false success reported for failed persistence.

Phase 3 context
- [x] Context budget behavior verified.
- [x] Mandatory instructions and current request preserved.
- [x] Tool-call/result consistency preserved.
- [x] Compaction behavior verified.
- [x] Canonical history retained.
- [x] Summarization failure behavior verified (heuristic + fallbacks).

Phase 3 indexing and relevance
- [x] Traversal and size bounds verified.
- [x] Exclusions verified.
- [x] Refresh and invalidation verified.
- [x] Stale index behavior verified.
- [x] Relevance results are bounded and useful.
- [x] Actual context integration verified.

Actual application
- [ ] Real application construction path inspected — DONE, gap found (UI never
  supplies `sessionId`; sessions unreachable from shipped UI).
- [ ] APK installed and runtime tested — NOT TESTED (no device/emulator).
- [ ] Live provider round-trips — NOT TESTED (no credentials).
- [x] Debug build result recorded — BUILD SUCCESSFUL (run `38142089080`).
- [x] Release build result recorded — BUILD SUCCESSFUL (same run).
- [x] Full regression suite result recorded — 167/167 pass, zero FAILED lines.
- [x] GitHub Actions result verified — `38142089080` completed/success.

## 13. Defects discovered and fixes

1. `.opencode/` sessions committable (no ignore rule) → added to root
   `.gitignore` (commit `f58cd39`). Smallest safe fix.
2. Phase 3 report falsely claimed Gemini native-FC support → corrected in §5
   of this report (no code change; providers verified stub-only).

## 14. Changed files and commits (this audit)

- Commit `f58cd39`: `SessionPersistenceTest.kt`, `ContextCompactionTest.kt`,
  `ProjectIndexRefreshTest.kt`, `AgentControllerSessionTest.kt` (new),
  `.gitignore` (2-line addition).
- No production-code changes; no Phase 4 work.

## 15. Verdict: PHASE 3 PARTIALLY VERIFIED

All in-scope behavior is verified at source, unit, integration and CI-build
levels (checklist above). Full ACCEPTED is withheld for three explicit,
evidence-backed reasons: (1) persistent sessions are not wired to the shipped
UI — no user-facing session behavior may be claimed; (2) no on-device runtime
(process death, restart, logs) was possible here; (3) no live provider
round-trip was possible. No critical defect remains in the implemented scope.
