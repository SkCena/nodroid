# NodRoid — PROGRESS.md (SAFE version)

## Safe scope lock (non-negotiable, overrides SPEC.md §2/§3)

- Single app id `dev.nodroid.app`, minSdk 26, targetSdk 35.
- **REMOVED entirely (not a disabled flag):** `:feature-foss-bridge` module does not exist;
  no `READ_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`, `BIND_ACCESSIBILITY_SERVICE`, `QUERY_ALL_PACKAGES`
  in any code, manifest or doc; no Shizuku; no SMS/Call triggers; no Accessibility UI automation nodes.
- SPEC.md §2/§3 describe a dual-flavor foss bridge — that part is intentionally NOT implemented.
  Reason: those capabilities let an app read another person's messages/calls and drive their UI,
  which is a surveillance/misuse risk even when "optional". The user agreed these permissions stay
  disabled; the safe build implements that as removal, documented here and in `AUDIT_REPORT.md`.
- The only place the forbidden permission names appear is this file, `AUDIT_REPORT.md`, `README.md`
  and the CI release note — each time as an explicit statement that they do NOT exist in the build.

## Architecture

- `:core-model` (pure JVM): `WorkflowGraph` JSON v1, `Node`, `Edge`, `PortType`
  (ANY/STRING/NUMBER/BOOLEAN/JSON/FILE_URI), `NodeDescriptor`, `ConfigFieldSchema`.
- `:core-engine` (pure JVM): Kahn cycle detector, port type validator, bounded concurrent DAG runner
  (`SupervisorJob` + `Semaphore(4)`), branching via `_nextPort`, Merge wait_all/pass_first,
  ForEach batcher (cap 1000), Subflow (depth cap 5), ErrorCatch routing, durable checkpoint SPI
  (in-memory + Room implementations), recursive-descent `{{node.path[0] ?? "default" | filter}}`
  expression parser (trim, lower, upper, number, round, jsonStringify, jsonParse, base64Encode,
  base64Decode, urlEncode, regexExtract, dateFormat).
- `:core-database` (Room, WAL): Workflows, RunLogs (pruned to latest 500), RunCheckpoints, KeyValueState.
- `:core-security` (Tink AES-256-GCM + Android Keystore vault `{{vault.KEY}}`, safe export scrubber).
- `:core-nodes` — 30 safe executors (see `AUDIT_REPORT.md` node ledger).
- `:feature-canvas` — AMOLED `#000000` canvas, `#1A2230` dot grid, smoked-glass cards
  `#0B0F17` @ 86%, category accents (Emerald/Amber/Cyan/Violet/Coral), Bezier wires, 48dp port
  hit-zones, magnetic snap (20u), Undo/Redo, Sugiyama auto-layout, Live Execution Inspector,
  5 starter templates.
- `:feature-runtime` — WorkManager schedules + checkpoint resume, foreground service, trigger receiver
  (boot / power), permissions helper. Connectivity triggers use a WorkManager network constraint
  because `CONNECTIVITY_CHANGE` is no longer delivered to manifest receivers.
- `:app` — `dev.nodroid.app` v1.0.0, R8 + ProGuard keep rules, release signing only when
  `NODROID_KEYSTORE_*` env vars are present (CI generates a CI keystore).

## Verified dependency coordinates (live lookups 2026-11-23/24, Maven Central + Google Maven)

| Artifact | Verified version | Source |
|---|---|---|
| AGP `com.android.application` | 8.13.0 exists (200); **pinned 9.3.1** — proven CI-green in NexTv in this workspace | Google Maven + NexTv |
| Kotlin | stable **2.4.21** (2.5.0-RC excluded); **pinned 2.4.20** — NexTv-proven | Maven Central metadata |
| KSP plugin | **2.3.14** (latest) | Maven Central metadata |
| Gradle wrapper | **9.7.0** — NexTv-proven with AGP 9.3.1 | NexTv `gradle-wrapper.properties` |
| Compose BOM | **2026.11.01** | Google Maven metadata |
| Room | **2.8.5** | Google Maven metadata |
| WorkManager | **2.12.0** | Google Maven metadata |
| kotlinx-serialization-json | **1.13.0** | Maven Central metadata |
| kotlinx-coroutines | **1.11.0** | Maven Central metadata |
| Koin | stable **4.2.2** (4.3.0-Beta1 excluded) | Maven Central metadata |
| OkHttp | latest 5.5.1; **pinned 4.12.0** (verified 200, proven stable API for MockWebServer tests) | Maven Central |
| Jsoup | **1.24.1** | Maven Central metadata |
| Google Tink Android | **1.24.0** | Maven Central metadata |
| ML Kit text-recognition | **16.0.1** (Latin) | Google Maven |
| ML Kit text-recognition-devanagari | **16.0.1** | Google Maven |
| QuickJS-kt (`io.github.dokar3:quickjs-kt`) | **1.0.18** | Maven Central metadata + source read of `QuickJs.kt` (`memoryLimit`, `evaluationTimeoutMillis`, suspend `evaluate<T>`, `quickJs {}` DSL) |
| core-ktx | **1.19.1** | Google Maven metadata |
| activity-compose | **1.13.0** | Google Maven metadata |
| lifecycle-runtime-compose | **2.11.1** | Google Maven metadata |
| JUnit | **4.13.2** | Maven Central metadata |

## Test counts (grep `@Test`)

- `:core-model` 20, `:core-engine` 47, `:core-nodes` 27, `:core-security` 6, `:feature-canvas` 18 — **118 total**, zero `@Ignore`.

## Root-cause / debugging log

- 2026-11-24 01:19 IST — Generator quoting clash: Kotlin raw-string `"""` inside the Python
  generator's `"""` block broke `gen2.py` (SyntaxError at the vault regex line). Root cause: the
  Kotlin `Regex("""...""")` literal terminated the Python triple-quoted string. Fix: rewrote the
  line with an escaped Kotlin string literal via a `chr(92)`-built patch; re-ran generator, pure-JVM
  grep clean.
- 2026-11-24 01:20 IST — Engine test `jsonParse then path` asserted a map equals a string.
  Root cause: test bug, not engine bug (`evaluate` correctly returns the parsed map). Fix: assert on
  the map entry.
- 2026-11-24 01:21 IST — Review fixes before first gate: (1) canvas `detectTransformGestures`
  captured a stale `state` snapshot — now reads `viewModel.state.value` fresh; (2) removed
  `fallbackToDestructiveMigration(false)` from the Room builder (default is already non-destructive,
  and the boolean overload is not guaranteed across Room versions); (3) wired the vault resolver
  through `DagRunner` (it was hardcoded to `{ null }`, which would have made `{{vault.KEY}}` silently
  resolve to empty in real runs).
- Local Gradle: see "Local Gradle gate" below.

## Local Gradle gate

- 2026-11-24 ~01:58 IST — `./gradlew :core-model:test :core-engine:test --offline` failed at the
  wrapper distribution download: `javax.net.ssl.SSLException: Unsupported or unrecognized SSL message`
  (Java does not use the sandbox egress proxy). Gradle 9.7.0 was then downloaded with curl (144 MB,
  works through the proxy) and run directly.
- 2026-11-24 ~02:00 IST — Gradle 9.7.0 + `--no-daemon` failed with the known VM blocker:
  `org.gradle.launcher.daemon.client.DaemonConnectionException: Could not dispatch a message to the daemon`,
  caused by `java.io.IOException: Connection reset by peer` writing to `127.0.0.1:42771`
  (`org.gradle.internal.remote.internal.inet.SocketConnection.flush`). Even `--no-daemon` forks a
  single-use daemon and needs raw loopback TCP, which this VM denies (same root cause as in
  `~/AGENTS.md`: raw `other_tcp` denied). **Local Gradle is definitively blocked; GitHub Actions CI is
  the real verification gate.** Exact log: `/tmp/gradle-pure.log`.
- Local kotlinc gate (substitute, run with Kotlin 2.4.20 compiler downloaded from GitHub releases,
  exact pinned runtimes kotlinx-serialization 1.13.0 / coroutines 1.11.0 / JUnit 4.13.2):
  - `:core-model` + `:core-engine`: compile exit 0, **OK (67 tests)**.
  - `:core-security` ExportScrubber: compile exit 0, **OK (6 tests)**.
  - `:core-nodes` pure executors (JsonPath, Descriptors, PureExecutors + NodesTest): compile exit 0,
    **27 tests run, 23 pass, 4 fail — all 4 are MockWebServer tests failing only with
    `java.net.ProtocolException: Unexpected status line: muse: Other TCP connections is turned off`,
    i.e. the same loopback-TCP denial (MockWebServer binds 127.0.0.1). They must be verified in CI.**
  - `:feature-canvas` CanvasMath + CanvasViewModel + Templates: compile exit 0 (with a local-only
    `androidx.lifecycle.ViewModel` stub — see below), **OK (18 tests)**.
  - Local totals: **118 tests, 114 pass, 4 environment-blocked (MockWebServer loopback).**
- Lifecycle KMP note (researched 2026-11-24): `lifecycle-viewmodel(-ktx):2.11.1` AARs downloaded
  directly are near-empty because Lifecycle 2.9+ ships ViewModel as a Kotlin Multiplatform artifact;
  Gradle resolves the real Android variant via the `.module` metadata, so CI (Gradle) is unaffected.
  The local canvas gate used a throwaway `ViewModel` stub in `/tmp` only — never in the repo.

## Test-debug loop — root causes fixed locally (all re-verified green)

1. `WorkflowGraph("g", "U", …)` positional args put the id into `schemaVersion: Int` — fixed to named
   args in EngineTest/CanvasMathTest (10 + 6 call sites). Found by kotlinc, not by review.
2. Single-backslash regex escapes (`\{`, `\s`) are illegal Kotlin string escapes — fixed by doubling
   lone backslashes (19 sites); the fixer initially missed `\s` (Python `\s` inside a character class
   means whitespace, not the letter s) and broke `trim('\'')` — both corrected by hand.
3. `ConfigReader.string` used `JsonPrimitive.toString()` (JSON-encoded, `\"` escapes intact) instead of
   `.content` — broke any config value containing quotes (found by the JsonPath node test).
4. `JsonPath.eval` treated `tags[1]` as one map key — rewrote the tokenizer into Field/Key/Index steps.
5. Template Formatter CSV mode pre-rendered the row template against row-less inputs (erasing row
   fields) — CSV mode now uses the raw template and renders per row.
6. `DagRunner`: a failed node's error never reached its Error Catcher (catcher read `inputs["error"]`
   from an empty map), and a missing executor returned `success=true`. Fixed: the failed node's output
   is published as `{error, failedNode}` so the catcher receives it via its normal edge, and a missing
   executor sets `runError` (unless a catcher absorbs it).
7. Engine tests called `evaluate("{{…}}")` — `evaluate` takes a bare expression; `render` takes the
   `{{ }}` template. Test bug, fixed in the tests.
8. Review fixes: canvas gesture lambda read a stale captured `state` (now reads `viewModel.state.value`);
   `CanvasViewModel` Undo/Redo buttons in MainActivity read `.value` without subscribing (now
   `collectAsState`); `ShareActivity` used API-33-only `getParcelableExtra<T>` (now the pre-33 call
   with `@Suppress("DEPRECATION")`); removed `fallbackToDestructiveMigration(false)` from Room builder.

## CI run 1 — FAILED (2026-11-24 ~10:43 IST, run 45331714556, ~1 min)
- Failed step: "Pure JVM tests" (`gradle :core-model:test :core-engine:test`).
- Root cause (from job log): `Failed to apply plugin 'org.jetbrains.kotlin.android'`
  > "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
  > support since AGP 9.0" — AGP 9.3.1 enables built-in Kotlin by default and
  > fatally rejects the kotlin-android plugin.
- Research: official docs (developer.android.com/build/migrate-to-built-in-kotlin)
  offer two paths: (a) migrate — remove kotlin-android plugin + migrate
  kotlinOptions to kotlin.compilerOptions; (b) opt out with
  `android.builtInKotlin=false` (documented AGP 9 opt-out, removed in AGP 10).
  AGP 9.3.1 bundles KGP 2.2.10 (verified from its POM), so path (a) would also
  force downgrading kotlin-compose/serialization/KSP from 2.4.20/2.3.14 to
  2.2.x — unproven matrix, several CI cycles of risk.
- Fix chosen: path (b) — added `android.builtInKotlin=false` and
  `android.newDsl=false` to gradle.properties. This is the exact NexTv-proven
  combo in this workspace (same AGP 9.3.1 + Kotlin 2.4.20 + KSP 2.3.14, CI-green).
  Built-in Kotlin migration deferred to a later AGP 10 readiness task.
