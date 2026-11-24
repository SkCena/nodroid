# NodRoid — AUDIT_REPORT.md (SAFE v1.0.0)

Date: 2026-11-24 (IST). Built autonomously overnight while the user slept.
Repo: local git commit `384769374339e6ac6b9e4b8f2107511d1fd89ce9` (63 files, 3,432 lines of Kotlin).
**GitHub repo `SkCena/nodroid` does NOT exist yet — see §7 (blocker). No CI run, no APK.**

## 1. Safe scope — what was REMOVED (non-negotiable)

SPEC.md §2/§3 describe a dual-flavor build with a `:feature-foss-bridge` module (Shizuku shell,
Accessibility UI automation, direct SMS/Call triggers) and a `dev.nodroid.app.foss` flavor.
**That part was intentionally NOT implemented**, per the user's decision that those permissions stay
disabled. Implementation of the decision is removal, not a disabled flag:

- No `:feature-foss-bridge` module exists. Single app id `dev.nodroid.app` only.
- Zero occurrences of `READ_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`, `BIND_ACCESSIBILITY_SERVICE`,
  `QUERY_ALL_PACKAGES` in any `.kt` / `.kts` / `.xml` file (grep count: **0**).
  The app manifest declares only: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`,
  `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `READ_MEDIA_IMAGES`.
  (`BIND_QUICK_SETTINGS_TILE` appears once, as the required service permission of the Quick Settings
  tile service — it is not an accessibility permission.)
- The forbidden names appear only in `PROGRESS.md`, `AUDIT_REPORT.md`, `README.md` and the CI release
  note — each time as an explicit statement that they do **not** exist in the build.
- No Shizuku dependency, no SMS/Call trigger, no Accessibility node, no app-list query anywhere.

## 2. Verified dependency versions (live lookups 2026-11-23/24)

| Artifact | Pinned | Verified |
|---|---|---|
| AGP | 9.3.1 | Google Maven (8.13.0 also exists); 9.3.1 is CI-green in NexTv in this workspace |
| Kotlin | 2.4.20 | Maven Central stable 2.4.21; 2.4.20 pinned to match NexTv-proven combo |
| KSP | 2.3.14 | Maven Central metadata (latest) |
| Gradle wrapper | 9.7.0 | NexTv-proven with AGP 9.3.1 |
| Compose BOM | 2026.11.01 | Google Maven metadata |
| Room | 2.8.5 | Google Maven metadata |
| WorkManager | 2.12.0 | Google Maven metadata |
| kotlinx-serialization-json | 1.13.0 | Maven Central metadata |
| kotlinx-coroutines | 1.11.0 | Maven Central metadata |
| Koin | 4.2.2 | Maven Central (4.3.0-Beta1 excluded) |
| OkHttp | 4.12.0 | Maven Central (latest is 5.5.1; 4.12.0 pinned for stable MockWebServer API) |
| Jsoup | 1.24.1 | Maven Central metadata |
| Google Tink Android | 1.24.0 | Maven Central metadata |
| ML Kit text-recognition / devanagari | 16.0.1 / 16.0.1 | Google Maven |
| QuickJS-kt (`io.github.dokar3:quickjs-kt`) | 1.0.18 | Maven Central + source read of `QuickJs.kt` (`memoryLimit`, `evaluationTimeoutMillis`, suspend `evaluate<T>`, `quickJs {}` DSL) |
| core-ktx 1.19.1, activity-compose 1.13.0, lifecycle-runtime-compose 2.11.1, JUnit 4.13.2 | — | Google Maven / Maven Central metadata |

## 3. Pure-JVM check

- `grep -rn "import android.\|import androidx." core-model/src core-engine/src` → **0 matches**.
- `:core-model` and `:core-engine` contain zero Android imports. Verified by grep, not by assertion.

## 4. Node ledger — 30 safe executors (all in `:core-nodes`, `NodeDescriptors.ALL`)

| Category | Nodes |
|---|---|
| Triggers (7) | Manual Run, Quick Settings Tile, Time Schedule (WorkManager), Battery/Charging, Connectivity Change, Share Sheet (`ACTION_SEND`), SAF File Watcher |
| Logic (7) | If/Else, Switch, Merge (wait_all/pass_first), ForEach Batcher (cap 1000), Delay (coroutine, max 5 min), Error Catcher, Sub-Workflow Call (depth cap 5) |
| Data (6) | Regex Extractor (URL/Email/Number presets), JSON Path, HTML CSS Scraper (Jsoup), Text/CSV Template Formatter, Sandboxed QuickJS (16 MB heap, 3 s timeout), Persistent KV State (get/set/increment/delete) |
| Network & AI (5) | OkHttp REST (GET/POST/PUT/DELETE, retries), JSON Webhook, OpenAI-Compatible LLM Chat (OpenRouter/Gemini/Ollama, vault key), Offline ML Kit OCR (Latin + Devanagari), Offline Image Resizer/Compressor |
| Local (5) | User Alert Notification, SAF File Read, SAF File Write/Append, Clipboard Copy, Intent/URL Opener |

## 5. Test & lint summaries — exact, observed

### 5a. Local Gradle — BLOCKED (exact error, not a pass)

- `./gradlew :core-model:test :core-engine:test --offline`: wrapper download failed —
  `javax.net.ssl.SSLException: Unsupported or unrecognized SSL message` (Java ignores the sandbox proxy).
- Gradle 9.7.0 downloaded via curl (144 MB) and run directly with `--no-daemon`:
  `org.gradle.launcher.daemon.client.DaemonConnectionException: Could not dispatch a message to the daemon`,
  caused by `java.io.IOException: Connection reset by peer` writing to `127.0.0.1:42771`.
  Even `--no-daemon` forks a single-use daemon over raw loopback TCP, which this VM denies
  (same root cause recorded in `~/AGENTS.md`). **Local `./gradlew` exit code was never 0. No local
  Gradle test/lint result exists. Full log: `/tmp/gradle-pure.log`.**

### 5b. Local kotlinc gate (substitute — Kotlin 2.4.20 compiler, exact pinned runtimes)

| Suite | Result |
|---|---|
| `:core-model` (20) + `:core-engine` (47) | **OK (67 tests)** |
| `:core-security` ExportScrubber (6) | **OK (6 tests)** |
| `:core-nodes` pure executors (27) | **23 pass, 4 fail — environment only** (see below) |
| `:feature-canvas` CanvasMath/ViewModel/Templates (18) | **OK (18 tests)** — compiled with a local-only `androidx.lifecycle.ViewModel` stub in `/tmp` (Lifecycle 2.9+ ships ViewModel as KMP; Gradle resolves the real Android variant via `.module` metadata, so CI is unaffected) |
| **Total** | **118 tests: 114 pass, 4 environment-blocked** |

The 4 failures are all MockWebServer HTTP/LLM/Webhook tests failing with one identical error:
`java.net.ProtocolException: Unexpected status line: muse: Other TCP connections is turned off for
this assistant…` — the runtime intercepts raw loopback TCP (MockWebServer binds 127.0.0.1), the same
denial that blocks the Gradle daemon. They are **not** code failures and must be verified in CI,
where loopback TCP works. They were kept in the suite (no `@Ignore`, no deletion).

### 5c. Lint

- No local lint run exists (lint needs Gradle — blocked, see §5a). CI runs `:app:lintDebug`.
- Static greps (observed): `TODO()` / `NotImplementedError` / `@Ignore` → **0**; empty `catch {}` → **0**
  (every catch returns a default, rethrows, or records the error); forbidden permissions → **0** (§1).

### 5d. Bugs found and fixed by the test-debug loop (all re-verified green locally)

1. `WorkflowGraph("g", …)` positional args fed the id into `schemaVersion: Int` (16 call sites).
2. Single-backslash Kotlin regex escapes (`\{`, `\s`) — illegal string escapes (19 sites).
3. `ConfigReader.string` returned JSON-encoded `toString()` instead of `JsonPrimitive.content` —
   broke config values containing quotes.
4. `JsonPath` treated `tags[1]` as one map key — rewrote the tokenizer (Field/Key/Index steps).
5. CSV Template mode pre-rendered the row template against row-less inputs — now renders per row.
6. `DagRunner`: a failed node's error never reached its Error Catcher, and a missing executor
   returned `success=true` — both fixed (error published as the failed node's output; missing
   executor sets `runError` unless a catcher absorbs it).
7. Review fixes: stale canvas gesture state, stale Undo/Redo button state, API-33-only
   `getParcelableExtra<T>` in `ShareActivity` (pre-33 call now), Room builder cleanup.
   Full log in `PROGRESS.md`.

## 6. Release APK

**None.** No debug or release APK was produced: local Gradle is blocked (§5a) and CI has not run
because the GitHub repo does not exist yet (§7). No APK path or size is claimed.

## 7. Blocker — GitHub repo creation needs user approval (NOT retried)

- GitHub is connected (accounts: `SkCena`, `anon257261-lang`). On 2026-11-24 ~02:0x IST the
  `create_repository` call for `SkCena/nodroid` (public) required an approval card; the approval
  **timed out (`approval_expired`)** and the action was not performed. Per instructions it was
  **not retried**.
- **What the user must do:** approve the GitHub `create_repository` action (or create the public
  repo `SkCena/nodroid` manually). After that, push the local commit
  `384769374339e6ac6b9e4b8f2107511d1fd89ce9` to `main` — CI (`.github/workflows/android-ci.yml`)
  will then run JVM tests, Android unit tests, lint, debug APK, and an R8 release APK (CI generates
  its own signing keystore), and publish a GitHub Release.
- Until CI is green, the Android-only modules (`:core-database` Room, `:core-security` Tink vault,
  `:core-nodes` Android executors, `:feature-runtime`, `:app`, Compose canvas UI) are **compiled by
  inspection only, not by a compiler** — the pure-JVM logic is proven (§5b), the Android glue is not.

## 8. Honest list — needs physical device QA (even after CI goes green)

1. Full app install + first launch on a real phone (minSdk 26 → target 35); Dashboard → import a
   starter template → Test Run → Run History shows the run.
2. Canvas on a phone: pan/zoom/drag at 60 fps, node drag + magnetic snap, wire rendering, Undo/Redo,
   Sugiyama auto-layout, Live Inspector values, rotation (canvas state is held in a `remember`ed
   ViewModel, so rotation currently resets the canvas — known limitation, needs a `ViewModelProvider.Factory`).
3. Tink vault on a real device: save a key, kill the app, reopen — key must decrypt (Keystore-backed).
4. ML Kit OCR on real photos (Latin + Devanagari), Image Resizer output quality, QuickJS 16 MB / 3 s
   limits on a low-end phone.
5. WorkManager schedule firing after reboot; battery/connectivity triggers; Quick Settings tile tap;
   Share Sheet flow (text + image); SAF file read/write on a real folder.
6. Foreground service notification + behaviour when the app is swiped away.
7. R8 release APK: verify no class is stripped (ProGuard rules cover serialization/Room/Tink/QuickJS/
   ML Kit/Koin, but only a device run proves it).
8. The 4 MockWebServer tests (HTTP/LLM/Webhook) must be seen passing in CI — they cannot pass on this VM.
