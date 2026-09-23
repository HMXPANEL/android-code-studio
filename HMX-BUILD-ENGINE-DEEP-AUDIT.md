# HMX-BUILD-ENGINE-DEEP-AUDIT.md

> **Phase 0 — Existing Build Engine Deep Audit — Android Code Studio**
> Date: 2026-09-21. Method: read-only inspection of repository source, no code
> modified, no Gradle config changed, no full APK built. Every claim cites an
> exact file path. Anything not verifiable from the repository is marked
> **UNKNOWN**. Numeric RAM/CPU figures are NOT invented — impact is described
> qualitatively unless a constant is present in code.

---

## 1. Executive Summary

Android Code Studio does **not** contain its own compiler pipeline. The
**existing build engine** is a 3-hop delegation chain:

1. **Orchestration (IDE process):** `GradleBuildService` (foreground Android
   `Service`, `core/app/.../services/builder/GradleBuildService.kt`)
   serializes build requests and forwards them over JSON-RPC.
2. **Tooling bridge (separate JVM):** `tooling-api-all.jar`, launched as
   `java -jar` by `ToolingServerRunner`, runs `ToolingApiServerImpl`, which
   drives the **Gradle Tooling API** (`GradleConnector` → `ProjectConnection`
   → `newBuild().run()` / `connection.action{}` for sync).
3. **Real build (Gradle daemon + AGP):** compilation (Kotlin/Java),
   AAPT2 resource processing, D8/R8 dexing/shrinking, packaging, zipalign and
   APK signing are **all executed by Gradle + Android Gradle Plugin 8.13.0
   inside the Gradle daemon**. No `javac`/kotlinc/D8/R8/aapt2-compile/link/
   zipalign/apksigner invocation exists in IDE code (verified by repo-wide
   grep — zero hits outside unrelated hex tables).

Consequences for the low-RAM goal:

- Peak RAM is dominated by processes the IDE does **not** directly manage:
  Gradle daemon(s) (`-Xmx2048m` enforced into user projects, daemon idle
  timeout **3 hours**), Gradle workers, Kotlin daemon, AAPT2, D8/R8.
- The IDE's own build-path footprint (tooling-server JVM with **no `-Xmx`**,
  retained `ProjectConnection`/models, per-line log forwarding over RPC +
  UI-thread hop, unbounded executors, never-cleaned temp files) is the part
  directly optimizable without touching capabilities.
- Three conflicting heap settings exist on the user-build path
  (`-Xmx2048m` in rewritten `gradle.properties` vs `-Xmx2g` passed as a
  `-Dorg.gradle.jvmargs` daemon arg in `Main.finalizeLauncher`, which is a
  misuse/no-op, vs IDE self-build `-Xmx4096M` which does **not** affect user
  builds). Unifying these under adaptive profiles is the P0 lever.
- Lowest realistic RAM target is **UNKNOWN without benchmarking** (see §23,
  §25). Structurally, a 4 GB device is "requires benchmark", 3 GB "likely
  impossible" for full AGP 8.13 + Kotlin builds — but that is an *estimate
  pending measurement*, not a verified fact.

---

## 2. Existing Build Engine Architecture

```
┌─ IDE PROCESS (largeHeap=true, AndroidManifest.xml:38) ─────────────┐
│ ProjectHandlerActivity (.initializeProject / RunTasksDialogFragment) │
│   ↓ Lookup(KEY_BUILD_SERVICE)                                        │
│ GradleBuildService : Service, BuildService, IToolingApiClient        │
│   core/app/.../services/builder/GradleBuildService.kt                │
│   ↓ LSP4J JSON-RPC over stdio pipes                                  │
│ ToolingServerRunner ──ProcessBuilder.start()──▶ child process        │
└──────────────────────────────────────────────────────────────────────┘
┌─ TOOLING SERVER JVM (java -jar tooling-api-all.jar, NO -Xmx) ───────┐
│ Main.main → ToolingApiServerImpl + ProjectImpl                       │
│   ↓ GradleConnector.forProjectDirectory → connect()                   │
│ ProjectConnection.newBuild().run()  (builds)                          │
│ ProjectConnection.action{ controller.getModel(...) }  (sync)          │
└──────────────────────────────────────────────────────────────────────┘
┌─ GRADLE DAEMON(S) + WORKERS (AGP 8.13.0 does the real work) ─────────┐
│ configuration → Kotlin/Java compile → AAPT2 → D8/R8 → package → sign │
│ AAPT2 binary overridden: -Pandroid.aapt2FromMavenOverride=<AAPT2>    │
└──────────────────────────────────────────────────────────────────────┘
```

Key architectural facts (all verified):

- Transport is **LSP4J `Launcher` over stdio**, not sockets
  (`tooling/api/.../util/ToolingApiLauncher.kt:208-238`;
  `ToolingServerRunner.kt:139-151`; `Main.kt:66-72`). stderr is excluded
  from RPC and used for server logs.
- The tooling server is a **fat jar** (`shadowJar` + rename to
  `tooling-api-all.jar`, `tooling/impl/build.gradle.kts:31-61`), re-extracted
  from assets on **every app start** (delete + copy,
  `core/common/.../managers/ToolsManager.java:199-206`).
- Single-flight builds enforced **twice**: client
  (`GradleBuildService.onPrepareBuildRequest`, `BuildInProgressException`)
  and server (`ToolingApiServerImpl.runBuild`, `IllegalStateException`).
- `signing/signing-key.jks` + `SigningConfigPlugin.kt` + `core/app`
  `signingConfigs` sign the **IDE's own APK** (self-build). User-project APK
  signing is AGP's default debug key / user's release config — no IDE-side
  signing code exists.

---

## 3. Complete Build Flow

### 3a. Project open → sync (initialize)

| # | File | Class / Method | Responsibility | Caller → Callee |
|---|------|----------------|----------------|-----------------|
| 1 | `core/app/.../activities/editor/ProjectHandlerActivity.kt:270` | `startServices()` | bind `GradleBuildService` (`BIND_AUTO_CREATE\|BIND_IMPORTANT`), `initLspClient()` | Activity lifecycle → `GradleBuildService` |
| 2 | `ProjectHandlerActivity.kt:462` (subagent-verified; `onGradleBuildServiceConnected`) | service-connected callback | `setEventListener`, `startToolingServer{…initializeProject()}`, `memoryUsageWatcher.watchProcess(pid)`, extra `metadata()` round-trip | ServiceConnection → `GradleBuildService.startToolingServer` |
| 3 | `core/app/.../services/builder/GradleBuildService.kt:543` | `startToolingServer(listener)` | builds env via `TermuxShellEnvironment().getEnvironment`, creates `ToolingServerRunner` | Activity → `ToolingServerRunner.startAsync` |
| 4 | `core/app/.../services/builder/ToolingServerRunner.kt:72-114` | `startAsync(envs)` | `ProcessBuilder.start()` `Environment.JAVA -jar TOOLING_API_JAR` (+3 `--add-opens`); captures pid via reflection; `ToolingApiLauncher.newClientLauncher` over `process.inputStream/outputStream` | Runner → OS process + LSP4J `Launcher` |
| 5 | `GradleBuildService.kt:316` | `onListenerStarted(server, projectProxy, errorStream)` | stores LSP proxy, `Lookup.update(KEY_PROJECT_PROXY)`, `startServerOutputReader(errorStream)` | Runner observer callback |
| 6 | `ProjectHandlerActivity.kt:362` | `initializeProject(buildVariants)` | guards (dir exists, not initializing/building, cache check), `createProjectInitParams` → `buildService.initializeProject(params)` | UI/sync action → `GradleBuildService.initializeProject (:479)` |
| 7 | `GradleBuildService.kt:500` | `performBuildTasks → onPrepareBuildRequest` | `ensureTmpdir()`, single-flight guard | internal |
| 8 | `tooling/impl/.../ToolingApiServerImpl.kt:121` | `initialize(params)` | validate dir → `ensureProjectGradleProperties` (**rewrites user's `gradle.properties`**) → `GradleConnector.forProjectDirectory` + `setupConnectorForGradleInstallation` → `connect()` → `RootModelBuilder.build` → `project.setFrom` → `isInitialized=true` | LSP request |
| 9 | `tooling/impl/.../sync/RootModelBuilder.kt:46-131` | `build(params)` | `connection.action{ controller.getModel(IdeaProject) }`, per-module `ModuleProjectModelBuilder`, `-Pandroid.build.model.only=true -Pandroid.invoked.from.ide=true` | server → Gradle daemon |
| 10 | `core/projects/.../internal/ProjectManagerImpl.kt:96-134` | `setupProject(project)` | `WorkspaceModelBuilder.build` (re-models proxy → `WorkspaceImpl`), `updateBuildVariants`, per-module `indexSourcesAndClasspaths()` + `AndroidModule.readResources()` **concurrently** | `onProjectInitialized` |
| 11 | `GradleBuildService.kt:360/365` + `EditorBuildEventListener` | `onBuildSuccessful/onBuildFailed` | notification update, `analyze()` current file, status flash | LSP notification → UI |

### 3b. Build request → APK

| # | File | Class / Method | Responsibility |
|---|------|----------------|----------------|
| 1 | `core/app/.../fragments/RunTasksDialogFragment.kt:174` | task dialog | `buildService.executeTasks(*toRun)` fire-and-forget |
| 2 | `core/app/.../actions/build/QuickRunWithCancellationAction.kt:134` | run-and-install action | task name = `"${module.path}:${variant.mainArtifact.assembleTaskName}"` (assemble task name comes from the AGP builder-model via Tooling API, e.g. `:app:assembleDebug`) → `:177` `buildService.executeTasks(taskName).get()` (blocking, cancellable) |
| 2b | `QuickRunWithCancellationAction.kt:238-245,282-344` | APK resolution + install | output listing = `mainArtifact.assembleTaskOutputListingFile` → `ApkMetadata.findApkFile()` → install via Shizuku `SilentInstaller.install()` or `ApkInstaller.installApk()` (+ optional auto-launch) |
| 2c | `core/app/.../fragments/RunTasksDialogFragment.kt:174` + `actions/build/RunTasksAction.kt`, `actions/build/ProjectSyncAction.kt` | user task picker / sync triggers | `executeTasks(*toRun)` with user-selected tasks sourced from `workspace.getSubProjects().flatMap{it.tasks}` (`IGradleProject.getTasks()` ← `GradleProjectImpl.kt:48`); sync actions call `activity.initializeProject()` |
| 3 | `GradleBuildService.kt:489` | `executeTasks(vararg tasks)` | `TaskExecutionMessage` → `server.executeTasks` |
| 4 | `ToolingApiServerImpl.kt:277-329` | `executeTasks(message)` | `connection.newBuild()`, stdio→`LoggingOutputStream`, `forTasks(*)`, `Main.finalizeLauncher(builder)` (JVM args, env, proxy, client args, progress listener), cancellation token, `builder.run()` blocking |
| 5 | Gradle daemon (AGP 8.13.0) | `assembleDebug/assembleRelease` etc. | Kotlin+Java compile, AAPT2 link, D8/R8, package, zipalign, sign → APK in user project's `build/` outputs (debug key auto-generated by AGP; release = user's own config; IDE injects no signing) |
| 6 | `LoggingOutputStream.kt` → `logOutput` RPC → `GradleBuildService.logOutput` → `EditorBuildEventListener.onOutput` → `BuildOutputFragment` | streaming log path, per line, UI-thread hop |

### 3c. Cancellation

`cancelCurrentBuild()` → LSP → `ToolingApiServerImpl.cancelCurrentBuild (:370-390)`:
`token.cancel()` via fair `ReentrantLock`-guarded `CancellationTokenSource`;
`(false, NO_RUNNING_BUILD)` when idle; `initialize()` preempts a running
build (`:128-130`). NOTE: `executeTasks` failure path returns **without**
nulling the token (success path nulls at `:321`) — minor leak/robustness
issue, P3.

---

## 4. Build Engine Module Map

**A. Direct build execution** — `tooling/impl` (server, `GradleConnector`
usage, launchers), Gradle daemon + AGP 8.13.0 (external, version-pinned in
`gradle/libs.versions.toml:2-4`).

**B. Build orchestration** — `core/app/.../services/builder/`
(`GradleBuildService.kt`, `ToolingServerRunner.kt`, `GradleServiceBinder.kt`,
`GradleBuildServiceConnnection.kt`, `util.kt`),
`core/projects/.../builder/BuildService.kt` (interface),
`core/projects/.../internal/ProjectManagerImpl.kt` (setup/cache),
`core/app/.../handlers/EditorBuildEventListener.kt`,
`core/app/.../activities/editor/ProjectHandlerActivity.kt` (init flow),
`core/app/.../fragments/RunTasksDialogFragment.kt`,
`core/app/.../actions/build/QuickRunWithCancellationAction.kt` (assemble +
install), `actions/build/RunTasksAction.kt`, `actions/build/ProjectSyncAction.kt`,
`core/app/.../models/ApkMetadata.kt` (`findApkFile`), installers
(`ApkInstaller`, Shizuku `SilentInstaller`),
`core/app/.../fragments/output/BuildOutputFragment.kt`.

**C. Gradle/Tooling API** — `tooling/api` (LSP interfaces + messages +
`ToolingApiLauncher`), `tooling/impl` (server + `sync/` model builders +
`progress/` + `logging/` + `net/SimpleHttpProxy`), `tooling/model`
(API model interfaces), `tooling/events` (progress-event DTOs),
`tooling/builder-model-impl` (~25 `Default*` AGP-model copies),
`tooling/plugin` (`AndroidIDEGradlePlugin`, `AndroidIDEInitScriptPlugin`,
`LogSenderPlugin`), `tooling/plugin-config` (`ToolingConfig`,
`LogSenderConfig`).

**D. Android project model** — `tooling/model/.../IAndroidProject`,
`tooling/impl/.../internal/AndroidProjectImpl.kt`,
`core/projects/.../android/AndroidModule.kt`,
`core/projects/.../internal/WorkspaceModelBuilder.kt`,
`core/projects/.../internal/WorkspaceImpl.kt`.

**E. Java compilation** — delegated to AGP/Gradle `javac` tasks. IDE-side:
`java/javac-services`, `java/lsp`, `java/lsp-setup` (editing support, not
build path). `IJavaProject` / `JavaProjectImpl` / `JavaProjectModelBuilder`
carry compiler settings for indexing only.

**F. Kotlin compilation** — delegated to Kotlin Gradle plugin inside user
builds (Kotlin 2.1.0 pins the IDE self-build; user projects use their own
KGP). No kotlinc invocation in repo. `kotlin-compiler-embeddable` dep is for
Kotlin LSP, not builds.

**G. XML/resource processing** — build path: AAPT2 via AGP with binary
override `-Pandroid.aapt2FromMavenOverride=<AAPT2>`
(`GradleBuildService.kt:390`). IDE-side (NOT build path):
`xml/aaptcompiler` (`ResourceCompiler.kt` etc., used by layout preview via
`AndroidModule.kt` / `PreviewLayoutAction.kt`), `xml/dom`, `xml/lsp`,
`xml/resources-api`, `xml/utils`, `utilities/uidesigner`,
`utilities/xml-inflater`.

**H. AAPT2** — native binary extracted once from `libaapt2.so`
(`ToolsManager.extractAapt2:182-197`) to `ANDROIDIDE_HOME/aapt2`
(`Environment.java:87`); injected per build via project property. No
`aapt2 compile/link` CLI code in repo.

**I. D8/R8** — zero IDE-side code (verified grep). `android.r8.version=8.6.17`
in IDE self-build `gradle.properties:32` pins the IDE's own shrinker only.
User builds use AGP-bundled D8/R8.

**J. Packaging/signing** — zero IDE-side code. `signing/signing-key.jks` +
`SigningConfigPlugin.kt` + `core/app` `signingConfigs` = IDE self-signing.
User APK packaging/signing/zipalign = AGP.

**K. Build logging/events** — `tooling/impl/.../LoggingOutputStream.kt`,
`logging/ToolingApiAppender.kt`, `logging/ToolingLoggingConfigurator.kt`,
`Main.client` volatile fan-out, `GradleBuildService.logMessage/logOutput`,
`BuildOutputFragment` (bounded 10000/8000 lines), `logging/logger`,
`logging/logsender`, `logging/idestats`.

**L. Build cache** — no IDE cache manager. Flags only: `org.gradle.caching=true`
(self-build + enforced into user projects), `--build-cache` CLI when
`BuildPreferences.isBuildCacheEnabled` (`GradleBuildService.kt:409-411`).
Configuration-cache: commented out (`gradle.properties:29`).

**M. SDK/JDK/tool discovery** — `core/common/.../utils/Environment.java:73-166`
(all paths + `putEnvironment`), `ToolsManager.init` (async extraction),
`core/app/.../utils/JdkUtils.kt:42-76` (scans `$PREFIX/lib/jvm` only),
`BuildPreferences.javaHome/gradleInstallationDir` (custom install dir;
javaHome stored but **not plumbed into server launch** — server always uses
`Environment.JAVA`).

**N. Termux runtime required by builds** — `termux/shared`
(`TermuxShellEnvironment`, `AppShell` with `Runtime.exec`),
`termux/application|emulator|view`, bootstrap `$PREFIX` (`usr/`, `bin/`,
`lib/`), `Environment.putEnvironment` (`ANDROID_HOME/ANDROID_SDK_ROOT/
JAVA_HOME/GRADLE_USER_HOME/SYSROOT/LD_LIBRARY_PATH/TMPDIR`), JDK under
`$PREFIX/lib/jvm`, SDK under `$HOME/android-sdk`, `login/bash` shells.
Builds cannot run without this runtime (java binary, env, SDK). SDK/JDK/NDK
provisioning itself is done by the `idesetup` script (binary asset
`termux/application/src/main/assets/data/common/{arm,arm64}/idesetup`,
staged by `IdesetupSession.kt:63-92`, launched from onboarding):
args built by `IdeSetupConfigurationFragment.kt:146-169` +
`models/IdeSetupArguments.kt:27-35`
(`--install-dir HOME --sdk <ver> --jdk <ver> --ndk …`), versions in
`fragments/onboarding/ideSetupConfig.kt:30-84` (SDK up to 35.0.1, JDK 17/21,
NDK 28.2.13676358/Skip); gate `OnboardingActivity.kt:182-184`
(`installedDistributions.isNotEmpty() && ANDROID_HOME.exists()`). JDK
selection prefers 17 (`IJdkDistributionProvider.DEFAULT_JAVA_VERSION`,
`JdkDistributionProviderImpl.kt:46-86`), persisted to
`BuildPreferences.javaHome`. Termux bootstrap base:
`TermuxInstaller.java` + `jniLibs/*/libtermux-bootstrap.so`.

**O. IDE-only components** (do not participate in builds) — `editor/*`,
`core/lsp-*`, `core/indexing-*` (except post-sync indexing, §12),
`java/lsp*`, `xml/lsp`, `utilities/uidesigner`, `utilities/treeview`,
`utilities/flashbar`, `utilities/lookup`, `utilities/templates-*`,
`core/actions` (except run-task actions), `event/*`, `termux/emulator|view`
(UI), `logging/logsender` UI, `ideconfigurations`.

---

## 5. Process Tree

Evidence-supported (repo) vs AGP-internal (marked UNKNOWN):

```
AndroidCodeStudio app process (largeHeap, 1×)
 ├── ToolingServerRunner coroutines (Dispatchers.IO, in-app threads)
 ├── Tooling API server JVM (1× per started server)
 │    java -jar tooling-api-all.jar   [ToolingServerRunner.kt:78-104]
 │    NO -Xmx on this command line — heap UNKNOWN (JVM default)
 │     ├── GradleConnector embedded client threads (in-server)
 │     ├── SimpleHttpProxy threads (accept + 2 pump threads per CONNECT)
 │     ├── LSP4J newCachedThreadPool threads (ToolingApiLauncher.kt:217)
 │     └── Gradle daemon JVM(s) (N×, spawned by Tooling API, outlive server
 │          unless DefaultGradleConnector.close() at shutdown)
 │           ├── Gradle worker processes (count UNKNOWN — AGP/Gradle managed)
 │           ├── Kotlin compile daemon (presence UNKNOWN from repo; KGP managed)
 │           ├── AAPT2 processes (binary = ANDROIDIDE_HOME/aapt2, via AGP)
 │           ├── D8/R8 (in-daemon or workers — UNKNOWN from repo)
 │           └── javac (in-worker, UNKNOWN from repo)
 ├── logcat reader process (IDELogcatReader, IDE-only)
 └── terminal sessions (user shells, not build path unless user builds manually)
```

Verified facts: exactly **one** IDE-spawned build process exists
(`ToolingServerRunner` → `ProcessBuilderImpl` → `ProcessBuilder.start()`).
Everything below the daemon is AGP/Gradle-internal and **cannot be counted
from this repository**. Daemon idle timeout 3 h
(`Main.kt:145`, `ToolingApiServerImpl.kt:241`) means daemons (and their heap)
persist long after builds. `shutdown()` calls `DefaultGradleConnector.close()`
("stopping all daemons") but client `onDestroy` only waits **1 s**
(`GradleBuildService.kt:205`); daemon stop is best-effort.

---

## 6. Gradle Configuration

### 6a. IDE self-build (`gradle.properties`, root)

| Setting | Value | Affects user builds? |
|---|---|---|
| `org.gradle.jvmargs` | `-Xmx4096M -Dkotlin.daemon.jvm.options="-Xmx4096M" … --add-opens…` | **No** — self-build only |
| `org.gradle.parallel/caching/daemon` | `true/true/true` | No (self-build) |
| `android.r8.version=8.6.17` | shrinker for IDE APK | No |
| `# org.gradle.configuration-cache` | commented = disabled | No |
| `android.nonTransitiveRClass=false` + TODO Migrate | legacy R classes | No |

### 6b. `composite-builds/build-logic/gradle.properties`

`parallel=true, caching=true, configureondemand=true` — **only here, not in
root** (inconsistency; self-build only).

### 6c. Versions (pinned)

Gradle wrapper **8.13** (`gradle-wrapper.properties:4`); AGP **8.13.0**,
`agp-tooling` 8.13.0, Gradle Tooling API fork **8.9**
(`com.github.AndroidIDE-Rv2Official:tom-gradle-tooling-api`,
`libs.versions.toml:2-4,189`); `builder-model:8.13.0`; Kotlin **2.1.0**
(KSP `2.1.0-1.0.28`; serialization plugin `1.9.10` hardcoded in
`build.gradle.kts:34` — version skew risk); compileSdk 34, buildTools 35.0.0,
minSdk 26, target 28, NDK 26.1.10909125, Java 11 (self-build)
(`build-logic/.../BuildConfig.kt:32-46`); AGP floor 7.2.0 enforced
(`AbstractModelBuilder.checkAgpVersion`); ABI filters arm64-v8a + armeabi-v7a.
**Mismatch:** NDK existence check hardcodes `ndk/28.2.13676358/ndk-build`
(`EditorBuildEventListener.kt:172`, `BaseEditorActivity.kt:1171`) vs
configured NDK 26.1.10909125 — false "NDK missing" dialog risk.

### 6d. Runtime enforcement into USER projects (the settings that matter)

1. `ToolingApiServerImpl.ensureProjectGradleProperties (:223-256)` rewrites
   `<user-project>/gradle.properties` on **every** `initialize()`: appends
   UTF-8 flags to `org.gradle.jvmargs`, replaces whole value with
   `-Xmx2048m -XX:MaxMetaspaceSize=512m …` when no `-Xmx` present, forces
   `org.gradle.daemon=true`, `idletimeout=10800000`, `parallel=true`.
   Side effect: silently mutates user files; `-Xmx2048m` contradicts…
2. `Main.finalizeLauncher (:137-179)`: `setJvmArguments(…,
   -Dorg.gradle.daemon.idletimeout=10800000,
   -Dorg.gradle.jvmargs=-Xmx2g …)` — passing `org.gradle.jvmargs` as a
   daemon `-D` is a misuse (that property is read from `gradle.properties`,
   not as a system property) and `2g` contradicts `2048m` only by notation
   (same size, two sources of truth); `setEnvironmentVariables(env)` with a
   4-key map **replaces** the daemon env (only `JAVA_TOOL_OPTIONS,
   GRADLE_OPTS, LANG, LC_ALL` + proxy vars survive).
3. Per-build CLI from `GradleBuildService.getBuildArguments (:374-417)`:
   `-Pandroid.aapt2FromMavenOverride`, `-P<logsender>`, `--stacktrace/--info/
   --debug/--scan/--warning-mode all/--build-cache/--offline` per prefs,
   optional `--init-script ide-logger-init.gradle`.
4. Sync-only `-Pandroid.build.model.only=true
   -Pandroid.invoked.from.ide=true` (`RootModelBuilder:134-141`).
5. No worker caps (`org.gradle.workers.max` — zero hits repo-wide), no
   `kotlin.daemon.jvmargs`, no toolchains (zero hits), no
   `org.gradle.configureondemand` for user builds, no build-cache node config.

---

## 7. Tooling API Analysis

| Question | Finding |
|---|---|
| Server start | `java -jar tooling-api-all.jar`, no `-Xmx`, 3 `--add-opens`, 1 logback `-D`. `ProcessBuilder` wrapper (`ProcessBuilderImpl.kt:37-53`). Working dir = HOME (null). Env = Termux shell env. |
| Client connect | LSP4J `Launcher`, stdio pipes, `newCachedThreadPool`, Gson `gsonType` polymorphism. No sockets except in-server `SimpleHttpProxy` (127.0.0.1, ephemeral port via `ANDROIDIDE_PROXY_PORT`). |
| Gradle launch | `GradleConnector.newConnector().forProjectDirectory()` → `connect()` → `newBuild().run()`; sync via `connection.action{}` + `BuildController.getModel`. `WRAPPER` default; `useInstallation`/`useGradleVersion` alternatives. |
| JVM args | Via `finalizeLauncher` + rewritten user `gradle.properties` (§6d). Server JVM itself gets none. |
| Extra JVM/process | Yes: 1 tooling-server JVM + N daemon JVMs + AGP children. |
| Model loading | `IdeaProject` → root branch (Android vs plain Gradle) → per-module builders → `ProjectImpl`. `getModelAndLog` timed by `StopWatch`. |
| Model retention | Retained: `connector`, `connection`, `lastInitParams`, live `ProjectImpl` (also served as LSP `IProject`). Reused only when `params == lastInitParams`; else disconnect + full re-sync. Never evicted except `shutdown()`. |
| Progress | `ForwardingProgressListener` (drops file-download events; only TASK + PROJECT_CONFIGURATION subscribed) → `EventTransformer` → `onProgressEvent` notification. |
| Logs | `LoggingOutputStream` (byte-wise, `\n`-flushed) → `logOutput`; slf4j → `ToolingApiAppender` → `logMessage`; stderr → error-stream reader → `ToolingApiErrorStream`. |
| Cancellation | Per-build `CancellationTokenSource` + fair lock; `cancelCurrentBuild` RPC; single-flight on both ends. Minor: token not nulled on build-failure path. |
| Shutdown | `connection.close(); connector.disconnect(); DefaultGradleConnector.close(); proxy.stop(); future.cancel(true)`; `Main.main` finally-block self-heals unclean disconnects; unused `DELAY_BEFORE_EXIT_MS=1000L` constant (dead). Client waits ≤1 s. |

High-priority notes: server JVM heap unbounded; daemon env map replaced
(only 4 keys); `org.gradle.jvmargs`-as-`-D` misuse; user `gradle.properties`
rewritten on every sync.

---

## 8. GradleBuildService Analysis

Lifecycle: `onCreate` (notification + `Lookup` register — minimal, good) →
`onBind` (lazy binder) → `startToolingServer` (from activity) →
`initializeProject/executeTasks` → `onDestroy` (unregister, best-effort
`shutdown().get(1s)`, runner release, client nulling, job cancel).

- `buildServiceScope` (`Dispatchers.Default`) is **never cancelled** in
  `onDestroy` — only the child `outputReaderJob` is; scope leaks per service
  instance (P1).
- Holds no Gradle models/logs (they live in `ProjectManagerImpl` /
  `BuildOutputFragment`); in-flight state = 1 future + `isBuildInProgress` +
  LSP proxies + `System` property for logger init script (cleared after use).
- `isReleaseVariant` field written never; `isDebugBuild()` only gates logger
  injection (dead-ish, P3).
- IDE-level extras on the build path: per-debug-build AAR check + init-script
  write when logsender enabled; `EditorBuildEventListener.prepareBuild` does
  file I/O (`hasNativeFiles`) + modal NDK dialog; activity auto-syncs on
  connect + extra `metadata()` round-trip + `memoryUsageWatcher.watchProcess`.
  Service itself does not init LSP/editors (activity-side `initLspClient`).

---

## 9. Kotlin Pipeline

`Kotlin source → (KGP in user build, inside Gradle daemon) → .class →
D8 → classes.dex`. IDE contributes **nothing** to this pipeline: no kotlinc
flags, no `kotlin.daemon.jvmargs`, no `kotlin.compiler.execution.strategy`
(zero hits repo-wide). Heap for Kotlin daemon derives from
`org.gradle.jvmargs` (`-Xmx2048m` enforced) unless the user project overrides
it. Incremental Kotlin = whatever the user's KGP/Gradle provides (UNKNOWN
from this repo; defaults are incremental-capable in modern KGP but
unverified here — do not claim).

---

## 10. Java Pipeline

`Java source → javac (AGP task, in worker) → .class → D8 → classes.dex`.
Same delegation as §9. IDE-side `java/*` modules are editor/LSP support.
`IJavaProject`/`JavaProjectModelBuilder` capture `languageLevel → 
JavaModuleCompilerSettings` for indexing only. Desugar:
`coreLibraryDesugaringEnabled=true` forced by both the IDE self-build
(`AndroidModuleConf`) and the injected `ide-logger-init.gradle` for user
debug builds (+ `desugar_jdk_libs:2.0.4` dep) — extra classpath/dex cost on
every logsender-enabled debug build (P2).

---

## 11. XML/AAPT2 Pipeline

`res/ + manifest + assets → AAPT2 (compile+link, AGP-driven) → compiled
resources + R classes → packaging`. IDE role: supplies the AAPT2 **binary**
(`libaapt2.so` → `ANDROIDIDE_HOME/aapt2`, executable-bit enforced) and the
override property `-Pandroid.aapt2FromMavenOverride`. `aapt2ThreadPoolSize`
exists as `IntegerOption.AAPT2_THREAD_POOL_SIZE` in builder-model-impl but is
never set by the IDE (UNKNOWN effective value). `xml/aaptcompiler` is
IDE-side resource compiling for preview/indexing — **not** on the build path
(verified usages: `AndroidModule.kt`, `PreviewLayoutAction.kt`).

---

## 12. D8/R8 Pipeline

Fully AGP-internal. `D8: .class → classes.dex`; `R8: shrink/optimize/obfuscate
→ dex` for minified release builds. IDE evidence: none (no references).
`android.r8.version=8.6.17` pins only the IDE self-build shrinker.
Concurrency/heap for D8/R8 derive from daemon/worker settings (§6d) —
UNKNOWN specifics from this repo. R8 is the expected peak-RAM task in release
builds (general AGP knowledge; **low-memory R8 behavior must be benchmarked,
not assumed**).

---

## 13. Packaging/Signing Pipeline

`resources + dex + assets + manifest → APK (AGP package task) → zipalign →
sign`. All AGP-internal; no IDE code. Debug signing = AGP default debug key;
release = user's own config. IDE self-signing (`signing-key.jks`) is
unrelated to user builds — must not be conflated or removed (§27).

---

## 14. Memory Analysis

Format per finding: Problem / Evidence / File / Class-Method / Current /
Impact / When / Why / Direction / Risk.

**M1. Tooling-server JVM has no heap cap.**
Evidence: command list `ToolingServerRunner.kt:78-104` contains no `-Xmx`.
Current: JVM-default heap (device-dependent). Impact: unknown (no cap ⇒ GC/
heap compete with daemon on low RAM). When: whole IDE session once server
starts. Why: long-lived process holding LSP + GradleConnector + models.
Direction: add adaptive `-Xmx` to server launch (P0). Risk: low (server does
no compilation; needs headroom for large multi-module models — benchmark).

**M2. Daemon heap fixed at 2 GB + 3 h idle retention.**
Evidence: `ToolingApiServerImpl.kt:244` (`-Xmx2048m`), `:241`
(`idletimeout=10800000`); `Main.kt:145-146`. Current: daemon stays alive 3 h
holding heap + Gradle model cache. Impact: unknown absolute, structurally the
largest resident. When: after first build until idle timeout/shutdown.
Direction: adaptive `-Xmx`/idle-timeout profiles; `DefaultGradleConnector.close()`
reliability (P0). Risk: medium (too-low Xmx ⇒ OOM build failures; too-short
idle ⇒ cold-start every build).

**M3. Duplicate/conflicting heap sources.**
Evidence: `2048m` (rewritten props) vs `-Xmx2g`-as-`-D` (`Main.kt:146`,
likely no-op) vs self-build `4096M` (irrelevant but confusing).
Direction: single source of truth via profile (P0). Risk: low.

**M4. `LoggingOutputStream` unbounded when client null.**
Evidence: `LoggingOutputStream.kt:31-42` — `lineBuilder.clear()` only inside
`if (clientRef != null)`. Current: output bytes accumulate if client drops.
Impact: unknown, bounded by build output volume during disconnect windows.
Direction: always clear after newline; drop when client null (P1). Risk: none.

**M5. `BuildOutputFragment.unsavedLines` unbounded pre-view.**
Evidence: `:28-38, :75-77` — `ArrayList` staged before view exists; bounded
(10000/8000) only after. Direction: cap pre-view staging (P2). Risk: none.

**M6. Unbounded executors.**
Evidence: `ToolingApiLauncher.kt:217`, `SimpleHttpProxy.kt:36`,
`TerminalSession.kt:65-72` (`newCachedThreadPool`); `LogReceiverService`,
`ExtraKeysView` single-thread schedulers. Current: thread count grows with
load; each thread reserves stack + work queue. Direction: bounded pools +
named threads (P1). Risk: low.

**M7. `buildServiceScope` never cancelled.**
Evidence: `GradleBuildService.kt:103-104` scope; `onDestroy:188-223` cancels
only child job. Direction: `buildServiceScope.cancel()` in `onDestroy` (P1).
Risk: none.

**M8. Long-lived model/connection retention.**
Evidence: `ToolingApiServerImpl.kt:78-83,144-172,210`; `ProjectImpl.setFrom`;
`ProjectManagerImpl._workspace + cachedInitResult`. Current: full project
model held in **two** processes (server `ProjectImpl`, app `WorkspaceImpl`)
plus `CachingProject` wrappers. Direction: evict-on-background, single-model
audit, `cachedInitResult` size accounting (P1). Risk: medium (re-sync cost).

**M9. `SimpleHttpProxy` lives from first `initialize()` to `shutdown()`.**
Evidence: `:80,184-193,406-409`. Current: threads + buffers always on, even
for offline builds. Direction: lazy-start only when proxy actually needed
(P2). Risk: low.

**M10. Temp/log accumulation.**
Evidence: `Environment.createTempFile (:173-185)` no deleter;
`ide-logger-init.gradle` rewritten per build; `gradle-wrapper.zip` left in
TMP; `tooling-api-all.jar` delete+recopy every launch; `HeapDumpOnOutOfMemoryError`
with unknown `*.hprof` rotation; log dir retention UNKNOWN. Direction: cleanup
pass + rotation (P2). Risk: none-low.

**M11. `android:largeHeap="true`** (`AndroidManifest.xml:38`). Masks pressure
instead of managing it; interacts with every profile. Direction: keep for now
(removal risks OOM in editor), revisit after measurements (P3). Risk: medium.

GC strategy, native memory (AAPT2/D8), duplicated Gradle-model byte cost:
UNKNOWN from repo.

---

## 15. CPU Analysis

- **C1. `org.gradle.parallel=true` forced, no worker cap** (zero
  `org.gradle.workers.max` hits). Peak CPU/threads unbounded on many-core…
  on phones this means thermal throttling + contention with the UI process.
  Direction: `org.gradle.workers.max` + `maxParallelForks` under profiles
  (P0/P1). Risk: medium (slower builds if capped too hard).
- **C2. Verbose flags multiply log/RPC/UI work**: `--info` defaults to
  first-build state (`BuildPreferences.kt:99`), `--debug/--scan/--warning-mode
  all` per prefs; each line = `LoggingOutputStream` → RPC → UI-thread hop
  (`GradleBuildService.wrap:566-591`, no batching at service layer).
  Direction: default off, batch UI appends (P1). Risk: none.
- **C3. Repeated work**: full re-sync on any `InitializeProjectParams` change
  (`:144` equality check is all-or-nothing); `checkGradleWrapper()` RPC per
  init **and** per build; init-script file rewrite per logsender build;
  `tooling-api-all.jar` recopy per launch; post-build `analyze()` re-parse.
  Direction: cache wrapper check per session, write init script only on
  change, skip recopy when hash matches (P1/P2). Risk: low.
- **C4. Progress subscription already minimal** (TASK + PROJECT_CONFIGURATION
  only) — good; `FileDownload` events dropped explicitly. Keep.
- **C5. Post-sync indexing** (`ProjectManagerImpl.setupProject`) runs
  per-module `indexSourcesAndClasspaths + readResources` concurrently on
  `Dispatchers.Default` while the daemon may still be warm — CPU contention
  window after every sync. Direction: defer/limit concurrency during builds
  (P2). Risk: low-medium (slower code insight until indexed).
- Kotlin/Java worker counts, dexing/R8 concurrency: UNKNOWN (AGP-internal).

---

## 16. Disk Analysis

| Location | Content | Lifecycle (verified) | Reusable? | Safe to clean? |
|---|---|---|---|---|
| `$HOME/.gradle` (`GRADLE_USER_HOME`) | wrapper dists, caches, daemon logs | process env only; no IDE cleanup | Yes (dists, caches) | Only via Gradle (`--stop`, cache cleanup); **do not** hand-delete — unverified |
| `<project>/.gradle`, `<project>/build` | AGP intermediates, transforms, dex, reports, APKs | created by daemon; no IDE deletion found | Yes between builds (incremental) | Only `clean` task; policy UNKNOWN from repo |
| `$PREFIX/tmp` | `ide-logger-init.gradle`, `gradle-wrapper.zip`, `temp_UUID` files | written, never deleted | No (regenerable) | Yes with care (P2 cleanup), except while builds run |
| `$HOME/.androidide/tooling-api/tooling-api-all.jar` | server fat jar | delete+recopy every app start | Yes | managed by `ToolsManager`; hash-check instead (P2) |
| `$HOME/.androidide/aapt2` | binary from `libaapt2.so` | extract-once | Yes | No |
| `$HOME/.androidide/init/init.gradle` (+`.bak`) | init script | write-once | Yes | No |
| `$HOME/plugins/logger/logger-runtime.aar` | logger runtime | extract-once | Yes | No (while logsender used) |
| `<project>/gradle.properties` | **mutated by IDE** (`ensureProjectGradleProperties`) | every `initialize()` | User-owned! | Never "clean"; must prompt before modify (P0 correctness) |
| `<project>/gradle/wrapper/*` | wrapper auto-installed from assets zip | on missing wrapper; pollutes project | Project-owned | No (user's build correctness) |
| Heap dumps `*.hprof`, IDE logs | on OOM / logback | rotation UNKNOWN | No | Needs rotation policy (P2) |
| `.acside/` per project | IDE project cache | `getProjectCacheDir` | Yes | UNKNOWN contents — audit before touching |

---

## 17. Incremental Build Analysis

| Area | Supported? | Where/How | Default | Problem | Direction |
|---|---|---|---|---|---|
| Gradle incremental tasks | Yes (AGP/Gradle built-in) | daemon executes; up-to-date checks | On | 3 h daemon needed to keep it warm; low-RAM kill ⇒ cold builds | adaptive idle timeout (P0) |
| Incremental Kotlin | UNKNOWN (KGP-internal; repo has no flags) | user's KGP version decides | UNKNOWN | none IDE-side | do not touch; document min KGP later (P3) |
| Incremental Java | UNKNOWN (same) | same | UNKNOWN | — | — |
| Incremental resources | UNKNOWN (AAPT2/AGP-internal) | same | UNKNOWN | — | — |
| Incremental dexing | UNKNOWN (D8-internal) | same | UNKNOWN | — | — |
| Build cache | Partially | `org.gradle.caching=true` enforced; `--build-cache` only when pref on | Local cache on; task cache flag off by default | flag off ⇒ less reuse across projects | default-on evaluation (P2, benchmark) |
| Configuration cache | No | commented out (`gradle.properties:29`) | Off | enabling on-device AGP 8.13 untested | experiment only post-benchmark (P3) |

Objective "avoid recompiling unchanged work" is currently carried entirely by
Gradle/AGP defaults + warm daemon. The IDE adds no de-optimizations except
killing warmth (process death) and the per-build logger init-script (forces
re-configuration surface; content-stable so up-to-date checks survive —
verify by benchmark).

---

## 18. Cache Analysis

- Gradle local cache: on (enforced). Hit rate on device UNKNOWN (no metrics).
- Daemon in-memory cache: 3 h window; biggest warmth lever.
- `ProjectConnection` reuse: yes on identical params.
- `cachedInitResult` (`ProjectManagerImpl:79`): skips re-init when
  `isFromSavedInstance && initialized && !shouldInitialize` — but staleness
  detection is flag-based, not content-based (P2: hash `gradle.properties`/
  settings to invalidate).
- `tooling-api-all.jar` recopy: anti-cache (every launch). Hash-gate (P2).
- No IDE-managed artifact cache exists; adding one is out of scope for
  Phase 1 (P3 proposal only).

---

## 19. Bottleneck Table

| Rank | Bottleneck | Evidence | Resource | Confidence |
|---|---|---|---|---|
| 1 | Gradle daemon heap (2 GB) + 3 h retention | `ToolingApiServerImpl.kt:240-248`; `Main.kt:145-148` | RAM | High (structural) |
| 2 | Uncapped parallelism/workers | `parallel=true` enforced; zero worker caps repo-wide | RAM+CPU | High (structural) |
| 3 | Tooling-server JVM uncapped | `ToolingServerRunner.kt:78-104` (no `-Xmx`) | RAM | High |
| 4 | AGP-internal peak (Kotlin daemon, D8/R8, AAPT2) | delegation verified; sizes UNKNOWN | RAM/CPU | Medium (external) |
| 5 | Per-line log RPC + UI hop | `LoggingOutputStream`, `wrap()` | CPU | High |
| 6 | Dual process model retention | server `ProjectImpl` + app `WorkspaceImpl` | RAM | High |
| 7 | Temp/artifact accumulation | `TMP_DIR` writers, no deleters | Disk | High |
| 8 | Redundant I/O per build/sync | wrapper RPC, init-script rewrite, jar recopy, re-`analyze()` | CPU/time | High |
| 9 | Post-sync concurrent indexing | `ProjectManagerImpl.setupProject` | CPU | High |
| 10 | Verbose flags default surface | `--info`↔first-build default | CPU | High |

---

## 20. P0/P1/P2/P3 Optimization Table

**P0 — Critical (peak RAM / process count).**

- **P0-1 Unify daemon heap under profiles.** Files:
  `tooling/impl/.../ToolingApiServerImpl.kt:223-256` (`ensureProjectGradleProperties`),
  `tooling/impl/.../Main.kt:130-200` (`finalizeLauncher`). Current: 2048m vs
  no-op `-D` 2g. Direction: one adaptive `org.gradle.jvmargs` (+
  `MaxMetaspaceSize`) written by profile; remove the `-Dorg.gradle.jvmargs`
  misuse. Expected: controls the single largest heap. Risk: medium (OOM if
  too low — benchmark per project size).
- **P0-2 Cap workers/parallelism under profiles.**
  Files: same two + `gradle.properties` handling. Current: `parallel=true`
  forced, no `max`. Direction: `org.gradle.workers.max` (+ Kotlin/R8-relevant
  caps as discovered) per profile. Expected: lower peak threads/RAM, less
  thermal throttle. Risk: medium (build-time increase).
- **P0-3 Cap tooling-server JVM.** File:
  `core/app/.../services/builder/ToolingServerRunner.kt:78-104`. Direction:
  adaptive `-Xmx` on the `java -jar` command. Expected: bounds bridge-process
  heap. Risk: low (benchmark large multi-module sync).
- **P0-4 Stop mutating user `gradle.properties` blindly / shorten daemon
  retention per profile.** Files: `ToolingApiServerImpl.kt:223-256`
  (prompt-or-overlay instead of rewrite; adaptive `idletimeout`);
  `GradleBuildService.kt:205` (reliable daemon stop on destroy).
  Expected: correctness + cold/warm control. Risk: medium (UX + warmth).

**P1 — High impact.**

- **P1-1 Fix `LoggingOutputStream` clear + batch UI appends.**
  `LoggingOutputStream.kt:31-42`; `GradleBuildService.kt:566-591`;
  `BuildOutputFragment.kt`. Risk: none.
- **P1-2 Cancel `buildServiceScope` in `onDestroy`.**
  `GradleBuildService.kt:103-223`. Risk: none.
- **P1-3 Bounded executors (LSP launcher, proxy, terminal).**
  `ToolingApiLauncher.kt:217`; `SimpleHttpProxy.kt:36`;
  `TerminalSession.kt:65-72`. Risk: low.
- **P1-4 Model lifecycle: evict-on-background, audit dual retention.**
  `ToolingApiServerImpl.kt`; `ProjectManagerImpl.kt`;
  `WorkspaceModelBuilder.kt`. Risk: medium.
- **P1-5 Cache wrapper-check per session; write init script only on change.**
  `GradleBuildService.kt:233-259,310-314,419-461`. Risk: low.
- **P1-6 Verbose flags default-off (keep user override).**
  `BuildPreferences.kt:56-102`; `GradleBuildService.kt:393-414`. Risk: none.

**P2 — Medium.**

- P2-1 Lazy-start `SimpleHttpProxy` only when needed.
- P2-2 Hash-gate `tooling-api-all.jar` extraction.
- P2-3 Cap `unsavedLines`; TMP cleanup pass; `*.hprof`/log rotation.
- P2-4 `--build-cache` default-on evaluation (benchmark-gated).
- P2-5 Content-based `cachedInitResult` invalidation.
- P2-6 Defer/limit post-sync indexing during active builds.
- P2-7 Remove desugar/logger injection cost when logsender disabled (already
  conditional — verify no residual `coreLibraryDesugaring` forcing via init
  script on non-logsender builds; init script only added when enabled —
  confirmed `:377-387`).
- P2-8 Fix NDK path mismatch (26.1 vs hardcoded 28.2.13676358).

**P3 — Optional / future.**

- P3-1 Null token on build-failure path; remove dead `isReleaseVariant` /
  `DELAY_BEFORE_EXIT_MS`; `largeHeap` revisit post-metrics.
- P3-2 Configuration-cache experiment (post-benchmark only).
- P3-3 `configureondemand` consistency for self-build; serialization-plugin
  version alignment (`1.9.10` vs Kotlin 2.1.0).
- P3-4 Document minimum KGP/AGP for incremental behavior; benchmark matrix.

---

## 21. Low-Memory Architecture Proposal (conceptual, not implemented)

```
Existing Android Code Studio
        │
        ▼
Existing Build Engine (GradleBuildService → Tooling API → Gradle/AGP)
        │
        ▼
Optimized Build Engine (same chain + managers, no capability removed)
        ├── Resource Manager   (profiles: heap/workers/daemon/idle)
        ├── Gradle Executor    (single-flight queue already exists; add caps)
        ├── Tooling API        (capped server JVM, fixed env/JVM-arg passing)
        ├── Compiler Manager   (KGP/javac settings passthrough, no replacement)
        ├── AAPT2 Manager      (binary provision + thread-pool sizing)
        ├── D8/R8 Manager      (mode-aware: debug vs release budgets)
        ├── Cache Manager      (Gradle cache flags, jar/hash gates, TMP janitor)
        ├── Process Manager    (daemon lifecycle, reliable stop, watcher data)
        ├── Memory Manager     (budgets per profile, eviction, log bounds)
        └── Artifact Manager   (APK outputs, no re-implementation of signing)
```

Rule: every manager tunes **Gradle/AGP knobs or IDE-side resource handling**;
none re-implements compilation, dexing, packaging, or signing.

---

## 22. Adaptive Build Profile Proposal (not implemented; values TBD by benchmark)

| Knob | LOW_MEMORY | BALANCED | PERFORMANCE |
|---|---|---|---|
| Daemon `-Xmx` / Metaspace | TBD-min (fits small apps) | current 2048m class | higher (large apps) |
| `org.gradle.workers.max` | TBD (e.g. low) | TBD | uncapped-ish |
| `org.gradle.parallel` | keep true (measure false) | true | true |
| Kotlin daemon opts | TBD | current | higher |
| Daemon idle timeout | short (reclaim fast) | medium | 3 h (warmth) |
| Tooling-server `-Xmx` | TBD-min | TBD | higher |
| `--build-cache` | on if helps | on if helps | on |
| `--info/--debug` | off | off | user choice |
| D8/R8 budget | debug-first; release warns | per-project | full |
| Proxy/pool sizes | lazy/bounded | bounded | bounded |

No final numbers are given: safe values must come from the benchmark plan
(§25) across small/medium/multi-module projects. Selection signal: device RAM
class + user override in build settings.

---

## 23. Compatibility Analysis

Preservable (current architecture delegates all of these to Gradle/AGP, and
nothing proposed removes them): Java projects, Kotlin projects, Compose
(KGP + Compose plugin run inside user builds untouched), XML layouts &
resources (AAPT2 path unchanged), Gradle Android projects, multi-module
(`IdeaProject`-based sync kept), libraries (`com.android.library` handled in
init script + model builders), debug builds, release builds, APK generation,
APK signing (debug + user release configs), R8/ProGuard where AGP supports
it (no IDE interference today), existing SDK/build-tools (env provision
unchanged), existing project structures (model builders untouched).

Risk points (not capability removals): lowering `-Xmx`/workers too far can
fail large builds (mitigation: profiles + fallback retry with higher budget
— future design); changing `gradle.properties` handling needs UX care;
capping server JVM needs large-project sync test. If any capability cannot be
preserved, the reason will be benchmark-measured OOM — none identified in
Phase 0 by code inspection.

---

## 24. Risks

1. Daemon/AGP internals are opaque from this repo — all worker/peak claims
   need on-device measurement.
2. Overwriting user `gradle.properties` is already a correctness risk; any
   change here needs migration + consent UX.
3. `setEnvironmentVariables` env replacement may already drop needed vars
   for some builds — verify before extending.
4. `largeHeap` removal without metrics risks editor OOM — keep until measured.
5. NDK version skew (26.1 vs 28.2 check) can block native builds spuriously.
6. Benchmarking itself is heavy on low-RAM devices — plan small-matrix first.

---

## 25. Benchmark Plan

Measure per scenario (small Java app, small Kotlin app, Compose app, XML-heavy
app, multi-module app × debug/release × clean/incremental):

- peak RSS per process (app, tooling server, each daemon/worker — via
  `memoryUsageWatcher` pid tracking already present + system dumps),
- average RSS, CPU time/%, wall build time, process count,
- Gradle user-home size, project `build/` size, cache size,
- incremental (single-file Kotlin/Java/XML change) vs clean time,
- sync (initialize) time + model size proxy.

Do NOT run the full matrix in Phase 0. Start tiny: one small project,
`assembleDebug`, record daemon peak + time at current 2048m vs a lower Xmx
candidate. Gate every P0 value change on this data.

---

## 26. Exact Files To Modify (Phase 1 candidates)

1. `tooling/impl/src/main/java/com/tom/rv2ide/tooling/impl/ToolingApiServerImpl.kt`
   Reason: unified heap/idle/parallel enforcement (`ensureProjectGradleProperties`),
   token-null fix, proxy lifecycle. Priority: P0.
2. `tooling/impl/src/main/java/com/tom/rv2ide/tooling/impl/Main.kt`
   Reason: fix `-Dorg.gradle.jvmargs` misuse, env-map replacement, profile
   passthrough in `finalizeLauncher`. Priority: P0.
3. `core/app/src/main/java/com/tom/rv2ide/services/builder/ToolingServerRunner.kt`
   Reason: adaptive `-Xmx` for server JVM. Priority: P0.
4. `core/app/src/main/java/com/tom/rv2ide/services/builder/GradleBuildService.kt`
   Reason: scope cancel, log batching, init-script/write caching, wrapper-check
   caching, reliable shutdown wait. Priority: P1.
5. `tooling/api/src/main/java/com/tom/rv2ide/tooling/api/util/ToolingApiLauncher.kt`
   Reason: bounded executor. Priority: P1.
6. `tooling/impl/src/main/java/com/tom/rv2ide/tooling/impl/LoggingOutputStream.kt`
   Reason: unconditional buffer clear. Priority: P1.
7. `tooling/impl/src/main/java/com/tom/rv2ide/tooling/impl/net/SimpleHttpProxy.kt`
   Reason: lazy start, bounded pool. Priority: P2.
8. `core/app/src/main/java/com/tom/rv2ide/fragments/output/BuildOutputFragment.kt`
   Reason: cap pre-view staging. Priority: P2.
9. `core/common/src/main/java/com/tom/rv2ide/managers/ToolsManager.java`
   Reason: hash-gated jar extraction. Priority: P2.
10. `utilities/preferences/src/main/java/com/tom/rv2ide/preferences/internal/BuildPreferences.kt`
    Reason: profile storage + flag defaults. Priority: P0/P1 (new keys only,
    no behavior removal).
11. `core/app/src/main/java/com/tom/rv2ide/handlers/EditorBuildEventListener.kt`
    Reason: NDK path constant fix (28.2→configured), avoid blocking I/O on
    build path. Priority: P2.
12. `core/projects/src/main/java/com/tom/rv2ide/projects/internal/ProjectManagerImpl.kt`
    Reason: model/indexing lifecycle cooperation. Priority: P1/P2.

---

## 27. Exact Files To Preserve (must NOT be modified/removed in Phase 1)

1. `tooling/model/**`, `tooling/events/**`, `tooling/builder-model-impl/**`
   — project-model contracts; AGP-bump-sensitive.
2. `tooling/impl/.../sync/*ModelBuilder*.kt`, `internal/*Impl.kt`
   — sync correctness for all project types.
3. `tooling/plugin/**`, `tooling/plugin-config/**`
   — user-build plugins (LogSender/desugar wiring).
4. `tooling/api/.../IToolingApiServer.kt`, `IToolingApiClient.kt`,
   message/result/model DTOs — RPC contract.
5. `core/projects/.../builder/BuildService.kt` — orchestration interface.
6. `signing/signing-key.jks`, `SigningConfigPlugin.kt`, `core/app` signingConfigs
   — IDE self-signing (unrelated to user builds; do not conflate).
7. `xml/aaptcompiler/**`, `java/**`, `editor/**`, LSP/indexing modules —
   IDE features outside the build path.
8. `settings.gradle.kts` module list, `gradle/libs.versions.toml` versions —
   no dependency removal.
9. User projects' `gradle.properties`/wrapper — user-owned; modify only with
   consent + backup.

**Modules that must NOT be removed:** `tooling/api|impl|model|events|
builder-model-impl|plugin|plugin-config`; `core/projects`; `core/app` builder
services; `termux/*` runtime; `java/*` + `xml/*` language support; Gradle
itself (no build-system replacement); Kotlin/Java/XML/Compose support
throughout.

---

## 28. Recommended Implementation Phases

- **Phase 1 — Measure + safe fixes:** tiny benchmark harness (§25 minimal);
  P1 correctness fixes (M4/M7 scope, executors, wrapper/init-script caching,
  jar hash-gate); no heap/worker changes yet except instrumentation.
- **Phase 2 — Profiles:** implement LOW_MEMORY/BALANCED/PERFORMANCE behind
  settings; unify `org.gradle.jvmargs`/workers/idle/server-Xmx; fix env-map
  and `-Dorg.gradle.jvmargs` misuse; consent UX for `gradle.properties`.
- **Phase 3 — Validate:** benchmark matrix per profile × project type; set
  default profile per RAM class; fix NDK skew; TMP/log janitor.
- **Phase 4 — Harden:** model eviction, indexing cooperation, build-cache
  default evaluation, config-cache experiment, fallback-to-higher-budget retry.

---

## 29. Final Technical Conclusions

1. The "build engine" is `GradleBuildService → ToolingServerRunner →
   ToolingApiServerImpl → GradleConnector → Gradle daemon + AGP 8.13.0`.
   Everything that turns source into an APK lives in Gradle/AGP, not in this
   repo.
2. Optimizable RAM without capability loss concentrates in: daemon heap +
   idle retention (2 GB / 3 h), uncapped workers, uncapped tooling-server JVM,
   dual-process model retention, log pipeline, executors, temp accumulation.
3. Three heap truths must become one profile-driven source; the
   `-Dorg.gradle.jvmargs` usage and env-map replacement are latent bugs on
   the hot path.
4. No Java/Kotlin/XML/Compose/Gradle/resource/build feature needs removal —
   the engine delegates all of them, and all P0–P2 directions preserve them.
5. No RAM target can be honestly claimed before benchmarking; the report's
   hardware classification (§23/P0 table inputs) is structural, and §25
   defines how to convert it into numbers.
6. Phase 0 stops here: no code, config, or deletion changed — only this file
   is added.

---

*Evidence index (primary): `core/app/.../services/builder/GradleBuildService.kt`,
`ToolingServerRunner.kt`, `handlers/EditorBuildEventListener.kt`,
`activities/editor/ProjectHandlerActivity.kt`, `fragments/RunTasksDialogFragment.kt`,
`fragments/output/BuildOutputFragment.kt`, `core/common/.../utils/Environment.java`,
`managers/ToolsManager.java`, `core/projects/.../internal/ProjectManagerImpl.kt`,
`builder/BuildService.kt`, `tooling/impl/.../Main.kt`, `ToolingApiServerImpl.kt`,
`LoggingOutputStream.kt`, `progress/ForwardingProgressListener.kt`,
`sync/RootModelBuilder.kt`, `net/SimpleHttpProxy.kt`, `tooling/api/.../IToolingApiServer.kt`,
`IToolingApiClient.kt`, `util/ToolingApiLauncher.kt`, `util/ToolingProps.kt`,
`tooling/plugin/...` (3 plugins), `gradle.properties`, `settings.gradle.kts`,
`build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`,
`utilities/preferences/.../BuildPreferences.kt`, `core/app/.../AndroidManifest.xml`,
`composite-builds/build-logic` configs, `signing/` + `SigningConfigPlugin.kt`.*
