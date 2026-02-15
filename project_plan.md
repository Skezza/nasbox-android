# VaultPilot MVP Project Plan (Phase 0 to Phase 8)

This plan sequences delivery into reviewable phases while keeping the app functional after every phase.

## Delivery principles
## Current execution status
- ✅ Phase 0 — Navigation and UI scaffolding: **Completed**.
- ✅ Phase 1 — Persistence foundation: **Completed**.
- ✅ Phase 2 — Credential security and Vault management: **Completed**.
- ✅ Phase 3 — SMB connectivity and test flow: **Completed**.
- ✅ Phase 4 — Media source integration and Plan management: **Completed**.
- 🧭 Phase 4.5 — Guided SMB destination browse assist: **Proposed**.
- ✅ Phase 5 — Core sync engine (manual-run, archive-only): **Completed**.
- ✅ Phase 5.5 — Source expansion execution (folder + full-device): **Completed**.
- ✅ Phase 5.5.1 — Share discovery fallback: **Completed**.
- ✅ Phase 6 — Dashboard mission control: **Completed**.
- 🧭 Phase 6.5 — Dashboard improvements (run control + live current runs): **Proposed**.
- Keep each phase mergeable and testable.
- Prefer vertical slices over broad incomplete scaffolding.
- Validate phase exit criteria before advancing.
- Maintain strict MVP scope (manual-run, archive-only).

---

## Phase 0 — Navigation and UI scaffolding

### Goals
- Establish information architecture with stable routes and screen shells.

### Work
- Implement bottom-nav structure with Dashboard, Plans, and Vault.
- Add route wiring for plan editor, server editor, and optional run detail.
- Create placeholder screen surfaces with basic actions and navigation transitions.

### Exit criteria
- App launches and navigates correctly across all primary and editor routes.
- Placeholder actions are connected and predictable.

### Implementation status
- ✅ Completed: bottom navigation routes for Dashboard, Plans, and Vault are wired.
- ✅ Completed: plan editor, server editor, and optional run detail placeholder routes are implemented.
- ✅ Completed: placeholder actions navigate to the expected destinations and return paths are functional.

---

## Phase 1 — Persistence foundation

### Goals
- Introduce database and repository base for all MVP workflows.

### Work
- Add Room and KSP integration.
- Define entities for servers, plans, backup records, runs, and run logs.
- Implement DAO interfaces and repository abstractions.
- Apply required indexes and constraints for performance and duplicate prevention.

### Exit criteria
- CRUD flows work for servers and plans.
- Run and log entities can be persisted and queried.
- Backup record uniqueness is enforced per plan + media item.

### Implementation status
- ✅ Completed: Room and KSP are integrated in the app module.
- ✅ Completed: entities for servers, plans, backup records, runs, and run logs are implemented with foreign keys and indexing.
- ✅ Completed: DAO surfaces and repository abstractions are in place for Phase 2+ feature work.
- ✅ Completed: baseline persistence tests validate CRUD, run/log persistence, and unique backup proof constraints.

---

## Phase 2 — Credential security and Vault management

### Goals
- Deliver secure server management UX and secure credential storage.

### Work
- Implement Vault list screen with add/edit/delete actions.
- Build server editor validation for host, share, base path, username, and password.
- Implement Keystore-backed secret store abstraction.
- Store only credential alias/reference in Room.

### Exit criteria
- Server create/edit/delete works end-to-end.
- Password is not stored in plaintext DB.
- Vault screen reflects updated server states.

### Implementation status
- ✅ Completed: Vault list screen now renders persisted server entries and supports add/edit/delete flows.
- ✅ Completed: Server editor validates required fields for host/share/base path/username/password before persistence.
- ✅ Completed: credential material is stored in a keystore-backed credential store and Room persists only credential aliases.

---

## Phase 3 — SMB connectivity and test flow

### Goals
- Verify server usability before backup operations.

### Work
- Integrate SMB2/3 client library via wrapper abstraction.
- Implement connection test use case (host/share/auth and latency capture).
- Wire per-server test actions in Vault and server editor.
- Map SMB failures to user-friendly error categories.

### Exit criteria
- Connection test can succeed/fail with clear, actionable feedback.
- Test metadata is persisted for dashboard/vault health presentation.

### Implementation status
- ✅ Completed: SMBJ is integrated behind a `SmbClient` wrapper abstraction to keep protocol calls out of UI/domain layers.
- ✅ Completed: a dedicated connection test use case validates host/share/auth and records latency for successful checks.
- ✅ Completed: Vault list and server editor now expose connection test actions with inline progress and snackbar feedback.
- ✅ Completed: mapped failure categories are persisted as server test metadata for downstream vault/dashboard health surfaces.
- ✅ Scope extension (unplanned, user-requested): Vault now includes local-network SMB discovery (subnet probing + mDNS host enrichment + `.local` fallback host checks) to prefill server editor values from reachable SMB endpoints on Wi-Fi.
- ⚠️ Deferred by request: share/root folder browsing from discovered hosts remains out of scope for current Phase 3 UX and will be revisited in a later phase.

---

## Phase 4 — Media source integration and Plan management

### Goals
- Enable users to define backup plans with source and destination bindings.

### Work
- Implement MediaStore album listing and image-item access.
- Add runtime permission handling for image read access by Android version.
- Build Plans list and Plan editor with:
  - name
  - enabled flag
  - album selection
  - server selection
  - template and filename pattern configuration
- Apply first-plan defaults (camera auto-selection when available).

### Exit criteria
- User can create and edit valid plans.
- Missing-permission and missing-prerequisite states are clear and actionable.

### Implementation status
- ✅ Completed: MediaStore-backed album listing and per-album image-item access are implemented behind a dedicated data-source/use-case boundary.
- ✅ Completed: runtime media permission handling is wired for Android 13+ (`READ_MEDIA_IMAGES`) and legacy devices (`READ_EXTERNAL_STORAGE`).
- ✅ Completed: Plans list and editor now support create/edit/delete with name, enablement, album, server, template, and filename pattern fields.
- ✅ Completed: first-plan defaults auto-select a camera-like album when available and seed default template/pattern values.
- ✅ Scope extension: plan creation now supports both Photo Album plans and General Folder plans, with optional video inclusion, optional album templating controls, and a full-phone shared-storage backup mode with user guidance.

---

## Phase 4.5 — Guided SMB destination browse assist (new)

### Why this exists
- Users can discover hosts and test credentials today, but they still have to manually guess the share and base path.
- We want a playful, low-friction “network browser” experience that helps users prefill destination inputs without introducing a full file-manager workflow.

### Goals
- Add an SMB-aware browse assistant inside **Add/Edit Server** so users can:
  - see available shares after authentication,
  - open one share,
  - drill into a few directory levels,
  - quickly set share + base path from a selected folder.
- Keep scope intentionally shallow and fast: this is a destination picker, not a file explorer.

### Work
- Extend `SmbClient` abstraction with browse-focused read operations:
  - `listShares(host, username, password)`
  - `listDirectories(host, share, path, username, password)`
- Add `BrowseSmbDestinationUseCase` to orchestrate:
  - connectivity + auth checks,
  - capability/error mapping (auth failure vs share denied vs timeout),
  - sorting and filtering (folders first, hide noisy system entries by default).
- Add server-editor UI components:
  - **Browse** button near Share/Base path inputs,
  - bottom sheet / dialog with two tabs:
    - **Shares** (top-level list)
    - **Folders** (selected share path trail + child folders)
  - one-tap **Use this location** action that prefills Share + Base path fields.
- Add “smart helper” UX details:
  - breadcrumb chips (`/`, `photos`, `archive`),
  - “common backup folders” hints (`backup`, `photos`, `camera upload`) when no clear match,
  - fast re-open with recent browse cache per host to reduce repeat latency.

### Exit criteria
- From Add Server, user can authenticate and browse share/folder structure at surface depth.
- Selecting a folder updates Share + Base path fields with normalized values.
- Errors are clear and recoverable; user can still manually input values if browse fails.
- Unit tests cover path normalization, breadcrumb navigation state, and error mapping.

---

## Phase 5 — Core sync engine (manual-run, archive-only)

### Goals
- Deliver the core backup behavior and proof-of-backup guarantees.

### Work
- Implement run use case pipeline:
  - initialize run
  - scan items for selected album
  - skip known items via backup records
  - render remote path and ensure directories
  - upload via stream with progress
  - persist backup records for successful uploads
  - finalize run status and counters
- Implement continue-on-error behavior for per-item failures.
- Persist run logs and error summaries.

### Exit criteria
- Manual run uploads new items and records proof.
- Re-run skips previously uploaded items.
- Run status and counters are accurate.

### Implementation status
- ✅ Completed: a Phase-5 `RunPlanBackupUseCase` now orchestrates run initialization, album scan, skip-vs-upload decisions, SMB upload, backup-proof persistence, and terminal run finalization.
- ✅ Completed: backup execution is archive-only and continue-on-error, preserving per-run counters for scanned/uploaded/skipped/failed media.
- ✅ Completed: per-run logs and summary errors are persisted for diagnostics, including start/scan/finish lifecycle events and item-level failure messages.
- ✅ Completed: Plans list now includes a manual **Run now** action per plan so users can trigger MVP runs without waiting for the dashboard mission-control phase.
- ℹ️ Scope note: Phase-5 shipped with album-only execution; Phase 5.5 closes that gap for folder/full-device source modes.

---

## Phase 5.5.1 — Share discovery fallback

### Goals
- Deliver a browse assistant that uses SMB2/3-compatible SRVSVC `NetShareEnum` over SMBJ RPC before any secondary fallback path.
- Keep the Vault “Browse destination” flow fast and predictable while expanding the set of hosts that can prefill share + base path inputs automatically.

### Work
- Add a `SmbShareRpcEnumerator` data-layer contract implemented with SMBJ RPC (`IPC$` + SRVSVC `NetShareEnum`) and domain-aware authentication.
- Update `BrowseSmbDestinationUseCase.listShares` to try RPC first, then call `SmbClient.listShares` only when RPC returns empty or throws, merging/deduplicating both lists before reporting success.
- Wire `AppContainer` to provide the RPC enumerator so existing callers remain unchanged, and keep the server editor UI sheet behavioral surface untouched.
- Expand domain tests to cover RPC-first success, fallback success, and combined failure messaging while retaining the existing “Connected, but no shares were returned” empty-state hint when no path yields names.

### Exit criteria
- Browse destination now surfaces valid share names whenever either SRVSVC RPC enumeration or SMBJ `listShares` succeeds.
- Sorting/deduplication stays consistent, and users still see the familiar empty-state message when no shares are available.
- Regression tests cover RPC-first behavior, fallback flow, and failure handoff so the message stays stable.

### Implementation status
- ✅ Completed: documentation, DI wiring, and tests now describe and verify the fallback behavior.

---

## Phase 5.5 — Source expansion execution (folder + full-device)

### Goals
- Extend the Phase-5 run engine to execute non-album plans created in Phase 4.
- Deliver practical folder and full-device backup execution while preserving MVP archive-only semantics.

### Work
- Implement source scanning pipeline for `FOLDER` plans:
  - enumerate items from selected folder URI/path source
  - map stable per-item identifiers for backup-proof records
  - stream readable files into existing SMB upload pipeline
- Implement source scanning pipeline for `FULL_DEVICE` plans:
  - enumerate configured shared-storage roots
  - apply include/exclude safeguards for unsupported/system paths
  - process files with continue-on-error behavior
- Reuse existing backup-proof model (`plan + media/item id`) for duplicate prevention across new source modes.
- Ensure non-templated plan behavior preserves source-style folder/file naming where intended.
- Extend run logs and summary messages to clearly identify source-mode-specific failures.

### Exit criteria
- Folder plans can run end-to-end and upload new items.
- Full-device plans can run end-to-end on shared storage with clear progress/failure reporting.
- Re-runs skip already-backed-up items for folder/full-device sources.
- Status/counters/logging remain accurate for all supported source modes.

### Implementation status
- ✅ Completed: run execution now supports `ALBUM`, `FOLDER`, and `FULL_DEVICE` source modes through the same archive-only upload/proof pipeline.
- ✅ Completed: folder scans support both tree-URI and filesystem source inputs, preserving relative source subfolders in remote path rendering.
- ✅ Completed: full-device scans now enumerate shared-storage roots (`DCIM`, `Pictures`, `Movies`, `Download`, `Documents`, `Music`) and keep continue-on-error behavior for inaccessible roots and item failures.
- ✅ Completed: source-specific diagnostics are now reflected in run logs, run summary errors, and status/counter finalization.
- ✅ Completed: unit tests now cover folder execution, full-device partial-result behavior, and unsupported source-mode rejection.

---

## Phase 6 — Dashboard mission control

### Goals
- Make run operations and system health observable in one place.

### Work
- Build dashboard status card with vault health and last run summary.
- Add “Run now” and “Test connection” primary actions.
- Show live progress strip during active run.
- Render timeline of recent events from run logs.
- Add missing-prerequisite CTAs when no server or no plan exists.

### Exit criteria
- User can start runs from dashboard and monitor progress.
- Timeline and run summary update correctly after completion.

### Implementation status
- ✅ Completed: Dashboard route now renders a dedicated mission-control screen backed by `DashboardViewModel` instead of a placeholder.
- ✅ Completed: dashboard status card now summarizes vault health (fresh-success vs failed vs stale/untested tests) and latest run counters/status with summary error context.
- ✅ Completed: dashboard primary actions now support **Run now** for selected plans and **Test connection** for selected servers with in-flight action guards and snackbar feedback.
- ✅ Completed: live run strip now appears when latest run status is `RUNNING`, using persisted run counters from incremental run updates.
- ✅ Completed: recent timeline section now reads persisted run-log entries (cross-run, most-recent first) and maps them to plan names.
- ✅ Completed: prerequisite CTAs now guide users to Vault/Plans when servers or plans are missing.
- ✅ Completed: `RunPlanBackupUseCase` now persists running snapshots and emits explicit progress events so dashboard observability updates during active runs.
- ✅ Completed: focused unit tests cover dashboard view-model behavior and run progress snapshot/event emission.

---

## Phase 6.5 — Dashboard improvements (run control + live current runs)

### Why this exists
- Phase 6 established observability but does not yet let users stop an active run.
- Dashboard currently blends live and historical data; we need explicit separation between active operations and audit history.
- Top health-card refinement is intentionally deferred until active-run control and visibility are stable.

### Goals
- Add direct control to stop active runs from dashboard surfaces.
- Introduce a dedicated `Current runs` section that appears only while runs are active and streams live telemetry.
- Add a run-detail drill-in that shows live per-file updates.
- Keep `Recent runs` historical-only for audit (no in-place live mutation from active runs).

### Work
- Implement cooperative stop/cancel behavior in run orchestration:
  - add stop request pathway (single-run and optional stop-all entrypoint),
  - persist transitional state (`CANCEL_REQUESTED`/`STOPPING`) and terminal `CANCELED`,
  - finalize canceled runs with consistent counters, summary, and log detail.
- Split dashboard data contracts into explicit streams:
  - `Current runs`: active statuses only (for example `RUNNING` and `CANCEL_REQUESTED`) with live counters/progress.
  - `Recent runs`: terminal statuses only (`SUCCESS`, `PARTIAL`, `FAILED`, `CANCELED`) for historical audit.
- Upgrade dashboard UI:
  - render `Current runs` only when at least one run is active,
  - show per-run progress bars plus live numeric counters (`scanned`, `uploaded`, `skipped`, `failed`),
  - provide stop controls with in-flight guard states and confirmation UX,
  - allow tapping an active run to open a live detail screen.
- Add live run detail behavior:
  - show status + aggregate counters + current-file indicator,
  - stream rolling per-file events from run logs,
  - hand off finished runs to `Recent runs` automatically after terminalization.
- Keep health card changes out of this phase except minor compatibility updates needed for the new section layout.

### Exit criteria
- User can stop an active run and see deterministic transition to `CANCELED` with persisted audit data.
- `Current runs` appears when active work exists and auto-hides when none are active.
- Progress bars and numeric counters update live during active runs without blocking UI.
- Tapping an active run opens live detail with file-level updates.
- `Recent runs` shows historical terminal runs only.

### Implementation status
- 🧭 Proposed: approved direction for the next dashboard-focused implementation pass.

---

## Phase 7 — UX hardening and resilience

### Goals
- Improve clarity, stability, and edge-case handling.

### Work
- Refine error copy and remediation hints.
- Improve empty/loading/error states across all screens.
- Harden path sanitization and template token fallback behavior.
- Validate cancellation/interruption handling for in-progress runs.
- Ensure logs remain useful but privacy-conscious.

### Exit criteria
- Primary user journeys are robust under expected failure conditions.
- Error messages are understandable and actionable.

---

## Phase 8 — QA, verification, and release readiness

### Goals
- Validate MVP acceptance and prepare for mainline merge.

### Work
- Complete targeted unit tests for business-critical logic.
- Verify DAO behavior and constraints via integration tests.
- Execute full manual regression on primary user journeys.
- Update project documentation with known limitations and next-phase opportunities.

### Exit criteria
- MVP acceptance checklist is fully satisfied.
- App quality is sufficient for merge and implementation follow-on.

---

## MVP acceptance checklist
1. User can add SMB server and run connection test with clear pass/fail output.
2. User can create a plan with album source and server destination.
3. Dashboard can trigger manual run.
4. Progress is visible during active runs without UI freeze.
5. User can stop an active run and receive a persisted `CANCELED` result with counters/logs.
6. Recent runs remains historical-only and suitable for audit review.
7. Re-running avoids duplicate uploads via proof records.
8. Auth/network and path errors are user-visible and logged.

---

## Suggested sequencing notes for agentic implementation
- Do not start sync engine before security, SMB test, and plan persistence are in place.
- ✅ Phase 5.5 source-expansion execution is now landed before dashboard mission-control work.
- Keep dashboard wiring thin until run and log repositories are stable.
- Treat run logs as first-class data early to reduce debugging friction in later phases.
- Phase 6.5 (active-run control + current-vs-recent split) should land before additional dashboard health-card enhancements.
