# NodRoid — Visual Node Workflow & Automation Studio for Android

Open-source, offline-first visual node automation for Android (`dev.nodroid.app`), similar in spirit to
Node-RED / n8n but designed natively for mobile. Pure Kotlin + Jetpack Compose, AMOLED Technical-Glass UI.

## Safe scope (locked)

NodRoid **does not contain** SMS triggers, call-log access, Accessibility UI automation, `QUERY_ALL_PACKAGES`,
Shizuku, or any foss-bridge module. Those permissions and features were removed entirely — not disabled by a
flag — and are not present in code, manifest or docs. The original SPEC.md sections describing a dual-flavor
foss bridge are intentionally not implemented; see `AUDIT_REPORT.md` for the exact rationale.

## Modules

- `:core-model` — pure JVM DAG graph schema (JSON v1), Node/Edge/Port, NodeDescriptor, ConfigFieldSchema.
- `:core-engine` — pure JVM Kahn cycle detector, port validator, bounded concurrent DAG runner
  (SupervisorJob + Semaphore(4)), branching/merge/ForEach/Subflow/ErrorCatch, checkpoint SPI,
  `{{node.path[0] ?? "default" | filter}}` expression parser.
- `:core-database` — Room (WAL): Workflows, RunLogs (pruned to latest 500), RunCheckpoints, KeyValueState.
- `:core-security` — Google Tink AES-256-GCM + Android Keystore vault (`{{vault.KEY}}`), safe export scrubber.
- `:core-nodes` — 30 safe node executors: triggers, logic, data transform, HTTP/LLM, offline OCR, local actions.
- `:feature-canvas` — AMOLED #000000 canvas, smoked-glass cards, Bezier wires, Undo/Redo, Sugiyama auto-layout,
  Live Execution Inspector, 5 starter templates.
- `:feature-runtime` — WorkManager schedules, checkpoint resume, foreground service, permissions helper.
- `:app` — `dev.nodroid.app`, minSdk 26, targetSdk 35, R8 release.

## Build

```sh
./gradlew :core-model:test :core-engine:test
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # needs NODROID_KEYSTORE_* env vars for signing
```

CI (`.github/workflows/android-ci.yml`) runs JVM + Android unit tests, lint, debug + R8 release APKs.
