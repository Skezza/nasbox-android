# NASBox Technical Considerations (MVP)

## Purpose
This document defines the architectural and technical decisions for delivering NASBox MVP as a manual-run, archive-only SMB2/3 photo backup app on Android.

## MVP scope boundaries

### In scope
- Configure SMB servers (host/share/base path/auth credentials).
- Create sync plans mapping local image album to SMB destination structure.
- Manual “Run now” backup execution.
- Manual stop/cancel control for active runs with persisted run audit.
- Real-time active-run progress and persisted run logs.
- Local proof-of-backup records to prevent duplicate uploads.

### Out of scope
- Background scheduling or periodic jobs.
- Local or remote deletion logic.
- Two-way sync and conflict resolution.
- Full restore browsing and file retrieval UX.

---

## Product architecture
Adopt a layered architecture to keep implementation incremental and testable.

### UI layer
- Jetpack Compose (Material3) + Navigation Compose.
- Bottom navigation sections:
  - Dashboard
  - Plans
  - Servers
- Additional routes for plan editing, server editing, and run detail.
- ViewModels own UI state via immutable state models and StateFlow.
- Composables are rendering-focused and event-driven.

### Domain layer
- Explicit use cases for:
  - running sync plan
  - stopping active sync run
  - testing SMB connection
  - listing albums
- Domain orchestration owns business rules (archive-only, skip logic, status rules).

### Data layer
- Room for durable entities (servers, plans, backup records, runs, run logs).
- DataStore Preferences for lightweight UI preferences (selected plan and similar state).
- MediaStore adapter for albums and media items.
- SMB adapter wrapping SMBJ to isolate protocol details.
- Credential store abstraction backed by Android Keystore.

---

## Technical stack
- Kotlin
- Compose Material3
- Navigation Compose
- Coroutines + StateFlow
- Room + KSP
- DataStore Preferences
- SMBJ (SMB2/3)
- Android Keystore-backed secret management

---

## Repository structure guidance
Organize by feature/layer with clear boundaries:
- UI modules/packages for dashboard, plans, server management, and navigation.
- Domain modules/packages for use cases and domain models.
- Data modules/packages for db, repositories, SMB, media, and security adapters.
- Core modules/packages for logging, error mapping, utilities.

Guideline: UI must not depend directly on SMB or Room implementation classes.

---

## Data model decisions

### Core entities
- SMB server entity: connection fields, credential alias reference, test status metadata.
- Sync plan entity: source album, destination server, path template, filename pattern, enablement.
- Backup record entity: proof-of-upload per plan and media item.
- Run entity: run lifecycle, status, aggregate counters, summary error.
- Run log entity: timestamped event log lines with severity and optional detail.

### Persistence constraints
- Enforce uniqueness for backup records by plan + media item.
- Use foreign keys to preserve referential integrity.
- Index run history for fast “latest runs” and dashboard rendering.

### DataStore usage
- Persist selected plan identifier.
- Optionally persist last-run plan identifier for UX continuity.

---

## Security and credential strategy
- Passwords must never be stored in plaintext database fields.
- Store credentials via Keystore-backed encryption workflow.
- Persist only a password alias/reference in Room.
- Support secret lifecycle operations (save/load/delete) for edit/remove flows.

---

## Media access strategy
- Support Android 13+ source-aware media permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`) and legacy `READ_EXTERNAL_STORAGE` for older Android versions.
- Query albums from MediaStore bucket metadata.
- Query image items by selected album.
- Support folder-source scans from either SAF tree URIs or filesystem paths, with stream opening by stable source item identifier.
- Support full-device shared-storage scans across public roots (`DCIM`, `Pictures`, `Movies`, `Download`, `Documents`, `Music`) with inaccessible-root reporting.
- Handle missing metadata defensively (missing display name/date/mime).

---

## SMB strategy (SMB2/3)
- Wrap SMB operations behind a small interface to isolate SMBJ.
- Required operations:
  - connection test
  - session lifecycle
  - directory ensure/create
  - remote existence check (optional in MVP)
  - upload stream with progress callbacks
- All paths should be normalized and share-relative where applicable.

---

## Sync behavior (MVP authoritative)

### Archive-only semantics
- Never delete remote files.
- Never delete local files.
- Upload new files only.

### New-item detection
- Primary skip mechanism: local backup record exists for plan + media item.
- Optional enhancement: remote existence check by computed path for reinstall recovery.

### Path construction
Remote path combines:
- server base path
- rendered directory template tokens
- rendered filename pattern tokens

Default token behavior should include:
- device model token
- album token
- calendar tokens (year/month/day/time)
- media identifier token
- extension token

All path and filename segments must be sanitized for SMB/Windows-illegal characters.

### Run lifecycle and status
- Create run entry at start.
- Scan media set.
- For each item: skip or upload.
- Honor stop requests cooperatively between item boundaries/major IO operations.
- Persist proof record after successful upload.
- Log important events throughout, including stop request and stop completion.
- Finalize run with SUCCESS, PARTIAL, FAILED, or CANCELED based on counters/errors/stop intent.

### Error resilience
- Continue-on-error for per-file failures.
- Preserve aggregate stats even on partial completion.
- Store user-friendly summary and technical detail in logs.

---

## Error taxonomy requirements
Normalize low-level failures into user-friendly categories such as:
- host unreachable
- authentication failed
- share not found
- remote permission denied
- timeout/network interruption
- local media read error
- unknown

Each mapped error should provide:
- concise user message
- optional recovery hint
- optional technical detail for run logs

---

## UX-state rules tied to data
- Dashboard should derive status from selected plan, server test recency, and latest run.
- Backup health indicator should use last test outcome and age thresholds.
- Dashboard should split live `Current runs` (active statuses) from historical `Recent runs` (terminal statuses only).
- `Current runs` should auto-hide when no run is active and expose stop control + live counters/progress.
- `Recent runs` should be audit-focused and need not stream active-run updates in real time.
- Run detail should show live per-file activity for active runs using run-log events.
- If prerequisites are missing (no server/plan/permissions), show actionable CTA.

---

## Performance and reliability
- Stream uploads; avoid full file buffering.
- Run media and SMB operations on IO dispatcher.
- Keep progress updates throttled to practical UI frequency.
- Support cancellation safely, idempotently, and finalize run state correctly.
- Prefer defensive null and exception handling around content resolver streams.

---

## Testing strategy expectations
- Unit tests for template rendering, filename sanitization, and skip/upload decision logic.
- Unit tests for error mapping.
- DAO tests for key persistence constraints and queries.
- End-to-end manual QA flow: add server -> test -> create plan -> run -> re-run skip behavior.

## Phase 1 implementation notes
- Persistence foundation now includes Room entities for servers, plans, backup records, runs, and run logs.
- Referential integrity is enforced with foreign keys between plans/servers, records/plans, runs/plans, and logs/runs.
- Duplicate backup proof prevention is enforced with a unique index on `(plan_id, media_item_id)`.
- Baseline persistence tests are expected to remain in place as Phase 2+ introduces secure credential and sync logic.

## Phase 2 implementation notes
- Server management now uses persisted `ServerEntity` rows with create, update, and delete UI actions in Compose.
- Server editor enforces required-field validation for server name, host, share, base path, username, and password.
- Secret values are encrypted via Android Keystore-backed AES/GCM keys and stored outside Room.
- Room persists only `credentialAlias` references, preserving the plaintext password prohibition.
- VaultViewModel factory now supports both classic and `CreationExtras` creation paths, and server flows are guarded with user-visible error handling to avoid coroutine crash propagation during editor operations.

---

## Key implementation risks and mitigations
- SMB server variability: keep wrapper thin and error mapping consistent.
- Credential complexity: isolate security concerns in a dedicated store abstraction.
- OEM MediaStore differences: treat metadata as partially unreliable.
- Long run interruption: persist logs and counters incrementally.


## Phase 3 implementation notes
- SMB connectivity now runs through a dedicated `SmbClient` abstraction with an SMBJ-backed implementation for SMB2/3 handshake/auth/share access checks.
- Connection tests are orchestrated by a domain use case that supports both persisted servers and in-editor draft credentials.
- Host normalization accepts either plain host input or URI-style values such as `smb://host/share`, including share extraction when provided in host field.
- The server list includes optional SMB discovery on the local network (port 445 probing on local subnet with mDNS service lookups (`_smb`, `_workstation`, `_device-info`) plus NetBIOS node-status lookups for host-name enrichment (with Android multicast lock during mDNS scanning) and merged common `.local` fallback probes that can replace IP-only discovery labels) to assist server setup; discovered hosts are advisory and still require credential validation.
- Share/root folder browsing over SMB is intentionally deferred beyond current MVP Phase 3 UX scope despite discovery enhancements.
- Phase 5.5.1 introduces SMBJ RPC share enumeration (`IPC$` + SRVSVC `NetShareEnum`) as the primary browse path, with SMBJ `listShares` as a secondary fallback when RPC returns empty.
- SMB failures are normalized into MVP error taxonomy categories and surfaced as concise user-facing messages with recovery hints.
- Server persistence now records test telemetry (`status`, `timestamp`, `latency`, and mapped error category/message) for backup health and upcoming dashboard status aggregation.
- Room schema was incremented to version 2 with an explicit migration adding test metadata columns to `servers` to preserve upgrade compatibility.


## Phase 4 implementation notes
- Plan source mode now supports MediaStore album-backed plans, general folder-based plans, and a full-device preset for shared-storage roots, enabling broader “raw file” sync preparation workflows before phase-5 engine work.
- Album plans now expose an include-videos option and make directory/filename templating explicitly optional (hidden when disabled), while full-device mode shows user guidance that backups can be long-running and battery-intensive.
- Plan management is now implemented in Compose with a full Plans list and Plan editor flow (create/edit/delete and enabled toggle).
- Media source integration now uses a dedicated `MediaStoreDataSource` abstraction to list albums and image items without leaking `ContentResolver` operations into UI/domain code.
- Runtime media permission handling is implemented by Android SDK level (`READ_MEDIA_IMAGES` for Android 13+, `READ_EXTERNAL_STORAGE` for legacy versions) with clear blocked-state guidance in the plan editor.
- Plan editor now validates required fields for name, source album, destination server, directory template, and filename pattern before persistence.
- First-plan UX defaults are applied when no plans exist by auto-selecting a camera-like album (when present) and seeding default template/pattern values.

## Proposed design extension: Guided SMB Browse Assist (Server setup UX)

### Product intent
- During server creation/editing, users should be able to “look around” the SMB destination at a high level, then prefill **share** and **base path** inputs confidently.
- This is intentionally **not** a general-purpose file manager. It is a setup assistant that reduces typing and configuration mistakes.

### UX interaction model
- Add a **Browse destination** action in Server Editor next to Share/Base path fields.
- Opening browse launches a lightweight guided flow:
  1. **Authenticate** using current editor fields (host/user/password).
  2. **Select share** from discovered accessible shares.
  3. **Select folder** by browsing directories only (no files, no upload/download actions).
  4. **Apply selection** to Share + Base path and return to editor.
- Interaction should stay quick and playful:
  - breadcrumb trail for backtracking,
  - obvious “Use this folder” CTA,
  - friendly empty-state suggestions (e.g., suggest `backup` when root is empty).

### Domain and data boundaries
- Keep browse behavior in domain use cases to avoid protocol details in UI.
- Proposed domain surfaces:
  - `ListSmbSharesUseCase`
  - `ListSmbDirectoriesUseCase`
  - or a combined `BrowseSmbDestinationUseCase` with stateful navigation helpers.
- Proposed data-model payloads:
  - `SmbShareCandidate(name, isWritable?, note?)`
  - `SmbDirectoryNode(path, displayName, depth, isSelectable)`
  - `SmbBrowseSnapshot(host, share, currentPath, directories, breadcrumb)`

### SMB adapter evolution
- Extend `SmbClient` abstraction with browse-only operations:
  - list shares for authenticated session,
  - list immediate child directories for a share-relative path.
- Preserve existing error taxonomy mapping so browse failures share language with connection testing:
  - auth failure,
  - host unreachable,
  - share not found/inaccessible,
  - permission denied,
  - timeout.

### Guardrails (important)
- Directory listing depth should be intentionally capped for responsiveness (for example, no recursive tree walk).
- Do not expose file-level operations in this flow.
- Keep manual override: user can always type share/base path even when browse is unavailable.
- Cache last successful browse snapshot per host for short-lived UX acceleration, but avoid persistent sensitive metadata.

### Validation and normalization rules
- On apply:
  - share value is trimmed and slash-normalized,
  - base path is normalized to share-relative segments,
  - leading/trailing separator noise is removed.
- Reuse existing input validation rules after prefill so editor still enforces canonical server constraints.

### Suggested implementation sequence
1. Add SMB browse methods to `SmbClient` + `SmbjClient`.
2. Add domain browse use case with mapped errors and normalized outputs.
3. Add browse state to `VaultViewModel` (`isBrowsing`, current share/path, nodes, errors).
4. Add Server Editor browse sheet/dialog and prefill action.
5. Add unit tests for path normalization, breadcrumb navigation, and error mapping.

## Phase 5 implementation notes
- Core backup orchestration is now implemented via `RunPlanBackupUseCase`, which creates a `RUNNING` entry, scans source media, applies backup-record skip logic, uploads new files, persists proof records, and finalizes run status/counters.
- Archive-only semantics are enforced: no local/remote delete operations are performed and only new items are uploaded.
- Per-item failures are isolated (continue-on-error) and mapped into persisted run logs plus run-level `summaryError` values for user-facing diagnostics.
- SMB upload now uses the `SmbClient.uploadFile(...)` operation with directory ensure/create behavior before writing file streams.
- Manual execution is now surfaced from the Plans list with a per-plan **Run now** action and in-place running indicator.
- Phase 5 shipped with album-backed execution first; Phase 5.5 extends the same pipeline to folder and full-device source modes.


## Phase 5.5 implementation notes
- `RunPlanBackupUseCase` now executes `ALBUM`, `FOLDER`, and `FULL_DEVICE` sources with a shared archive-only upload/proof pipeline.
- Folder execution now scans either document-tree URIs (`content://...`) or direct filesystem folder paths, then preserves relative source subfolders in remote path rendering.
- Full-device execution now scans shared-storage roots (`DCIM`, `Pictures`, `Movies`, `Download`, `Documents`, `Music`) and records inaccessible roots as explicit run failures without aborting the whole run.
- Source item streams now resolve from numeric MediaStore IDs, content URIs, or file URIs via the media data-source abstraction.
- Plans **Run now** permission requests are source-aware on Android 13+: album runs request image (and optional video) access, full-device runs request image/video/audio, and folder runs rely on folder-level URI grants when applicable.
- Backup-proof deduplication remains keyed by `(plan_id, media_item_id)` and now uses stable source identifiers for folder/full-device items.

## Phase 6 implementation notes
- Dashboard mission control is now implemented with a dedicated `DashboardViewModel` and Compose screen wired to the top-level dashboard route.
- Dashboard status card derives backup health from persisted server test telemetry and test-age thresholds, and summarizes latest run status/counters/error context.
- Dashboard primary actions now support selecting a plan for **Run now** and selecting a server for **Test connection**, with guardrails for disabled plans and in-flight actions.
- Run observability now uses flow-backed repository queries:
  - latest run stream (`runs ORDER BY started_at DESC LIMIT 1`)
  - latest timeline stream (run-log rows joined to runs, newest first)
- `RunPlanBackupUseCase` now persists incremental `RUNNING` snapshots (scanned/uploaded/skipped/failed/summary) and emits explicit progress log events so UI progress and timeline update during active runs.
- Dashboard now surfaces missing-prerequisite CTAs when no server or no plan exists, and displays a live progress strip while latest run status is `RUNNING`.

## Phase 6.5 implementation notes
- `StopRunUseCase` is now implemented as the dashboard stop-command entrypoint and terminalizes active rows immediately to `CANCELED` with persisted run-log audit messages.
- `RunPlanBackupUseCase` now handles external cancellation safely:
  - avoids reviving terminal `CANCELED` rows during progress snapshots,
  - checks for stop intent between item operations,
  - returns deterministic canceled results with persisted counters/summary (`Run canceled by user.` fallback).
- Run-status handling now treats cancellation as first-class across domain and UI mapping (`RUNNING`, `CANCEL_REQUESTED`, `CANCELED`), with legacy `CANCEL_REQUESTED` rows retained only for stale-recovery compatibility.
- Dashboard data contracts are split into explicit streams:
  - `Current runs`: `RUNNING` only.
  - `Recent runs`: terminal statuses only (`SUCCESS`, `PARTIAL`, `FAILED`, `INTERRUPTED`, `CANCELED`).
- Dashboard UI now includes:
  - conditional `Current runs` section with per-run progress bars, live counters, and stop controls,
  - stop confirmation dialog + in-flight guard state,
  - dedicated live detail drill-in for active runs.
- App startup/dashboard initialization now runs a stale-active reconciliation pass:
  - legacy stale `CANCEL_REQUESTED` runs are finalized to `CANCELED` on a shorter timeout window,
  - stale `RUNNING` runs are marked `INTERRUPTED` on a more conservative timeout window.
- Live run detail route/state now shows:
  - active/terminal status summary with aggregate counters,
  - current-file indicator derived from rolling processing log events,
  - newest-first run-log feed for live troubleshooting.
- Health-card redesign remains out of scope; only layout compatibility changes were made for `Current runs`.
