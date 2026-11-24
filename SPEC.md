# SPEC.md — NodRoid Technical & Product Specification

## 1. Product Identity & Targets
- **App Name:** NodRoid
- **Play Flavor Application ID:** `dev.nodroid.app`
- **FOSS Flavor Application ID:** `dev.nodroid.app.foss` (allows side-by-side installation for testing)
- **Min SDK:** 26 (Android 8.0) | **Target / Compile SDK:** 35 (verify latest stable installed via `sdkmanager`)
- **Language:** Kotlin 2.x, Coroutines, StateFlow/SharedFlow, Jetpack Compose (Material 3)
- **DI:** Koin (fast compilation, zero heavy kapt overhead)
- **Serialization:** `kotlinx.serialization` (strict JSON, schema versioned)
- **Local DB:** Room (SQLite with WAL mode enabled)
- **Networking:** OkHttp 4.x (connection pooling, timeouts, multipart)

---

## 2. Multi-Module Gradle Topology
```text
nodroid/
├── app/                     # App shell, Koin wiring, Navigation, Dual-flavor Manifest merging
├── core-model/              # PURE JVM: WorkflowGraph, Node, Edge, Port, NodeDescriptor, ConfigFieldSchema, JSON v1
├── core-engine/             # PURE JVM: GraphCompiler, Kahn Cycle Detector, Port Type Checker, DAG Runner, Expression AST, Checkpoint SPI
├── core-database/           # Android Room (WAL): Workflows, RunLogs (auto-pruned to 500), RunCheckpoints, KeyValueState
├── core-security/           # Android Keystore + Google Tink AES-256-GCM Credential Vault + Export Secret Scrubber
├── core-nodes/              # Pluggable NodeExecutors (28+ production nodes across Triggers, Logic, Data, Net, AI/OCR, QuickJS, System)
├── feature-canvas/          # 60fps Jetpack Compose 2D Node Canvas, Bezier Wires, Undo/Redo, Sugiyama Auto-Layout, Live Inspector
├── feature-runtime/         # Dynamic Trigger Multiplexer, ForegroundService, NotificationListener, WorkManager, BootReceiver, Reliability Doctor
├── feature-foss-bridge/     # FOSS FLAVOR ONLY: Shizuku Shell Node, Accessibility UI Automation, Direct SMS/Call Trigger
└── .github/workflows/       # CI/CD: play + foss builds, pure JVM tests, R8 release artifact pipeline
```

---

## 3. Dual-Flavor Separation Rule
- **play** flavor MUST NOT declare `READ_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`, `BIND_ACCESSIBILITY_SERVICE`, or `QUERY_ALL_PACKAGES`. It MUST NOT depend on `:feature-foss-bridge`.
- **foss** flavor includes `:feature-foss-bridge` via `fossImplementation(project(":feature-foss-bridge"))` and binds privileged nodes cleanly through a runtime `NodePackRegistry` interface.
