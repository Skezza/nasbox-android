# VaultPilot Project Plan

This plan is intentionally phased from **Phase 0 to Phase 8** so Codex can implement incrementally while preserving a runnable app at each phase.

## Delivery principles
- Keep the app buildable at all times.
- Add one vertical slice at a time.
- Prefer small commits per phase.
- Validate phase acceptance criteria before moving forward.

---

## Phase 0 — Foundation & UI scaffolding

## Objectives
- Establish bottom navigation and screen placeholders aligned with VaultPilot IA.

## Tasks
- Rename/rewire tabs to:
  - Dashboard (`dashboard`)
  - Plans (`plans`)
  - Vault (`vault`)
- Add navigation routes for editor/detail screens:
  - `plan_editor/{planId?}`
  - `server_editor/{serverId?}`
  - `run_detail/{runId}` (optional placeholder)
- Create basic Compose screen shells and shared scaffold.

## Exit criteria
- App launches and routes correctly between tab screens.
- Placeholder actions navigate to planned destinations.

---

## Phase 1 — Data foundations (Room + repositories)

## Objectives
- Introduce persistent model for servers, plans, runs, logs, and backup records.

## Tasks
- Add Room dependencies and KSP configuration.
- Create entities:
  - `SmbServerEntity`, `SyncPlanEntity`, `BackupRecordEntity`, `RunEntity`, `RunLogEntity`
- Add DAOs and database wiring.
- Implement repositories with clean interfaces.

## Exit criteria
- DB migrations/build pass.
- CRUD works for servers and plans.
- Backup records and runs can be inserted/read.

---

## Phase 2 — Vault management (server setup + secure credentials)

## Objectives
- Allow users to add/edit SMB servers and safely store passwords.

## Tasks
- Implement `Vault` screen list with add/edit/delete actions.
- Implement `ServerEditor` form with validation:
  - host, port, share, basePath, username, password
- Add `SecureCredentialStore` for password alias storage.
- Persist only password reference in Room.

## Exit criteria
- User can save and edit server entries.
- Password does not persist as plaintext in Room.

---

## Phase 3 — SMB connectivity and connection testing

## Objectives
- Confirm server connectivity before sync.

## Tasks
- Add SMBJ dependency.
- Build `SmbClient` wrapper:
  - connect/session
  - share validation
  - optional basic read/write path check
- Implement `TestSmbConnectionUseCase`.
- Wire “Test connection” action in Vault/ServerEditor.
- Add friendly error mapping.

## Exit criteria
- User can run test connection and get clear success/failure feedback.
- Known failures map to human-readable messages.

---

## Phase 4 — Plan management + MediaStore source selection

## Objectives
- Enable creation of backup plans that bind album -> SMB destination.

## Tasks
- Implement `Plans` list screen (card list + “New Plan”).
- Implement `PlanEditor` sections:
  - plan name
  - source album picker (MediaStore buckets)
  - destination server/share/base path
  - template and filename pattern fields with defaults
- Add permissions flow for photo read access.
- Persist selected/default plan choice in DataStore (optional in this phase or next).

## Exit criteria
- User can create and edit plans with valid source + destination.
- First plan defaults are prefilled and save successfully.

---

## Phase 5 — Core manual sync engine (archive-only)

## Objectives
- Deliver the main business value: manual upload of new photos.

## Tasks
- Implement `RunSyncPlanUseCase(planId)` pipeline:
  1. Create run record (started)
  2. Load media items for album
  3. Decide per item: upload vs skip (by backup record)
  4. Ensure remote dirs and upload streams
  5. Persist `BackupRecordEntity` for successful uploads
  6. Aggregate run counters + final status
- Add progress callbacks (current item, total counts, bytes where available).
- Continue-on-error behavior with per-item error logs.

## Exit criteria
- “Run now” uploads new images and records proof of backup.
- Immediate re-run mostly skips previously uploaded items.
- Run summary counters are accurate.

---

## Phase 6 — Dashboard mission control + timeline

## Objectives
- Surface real-time sync experience and historical diagnostics.

## Tasks
- Build Dashboard top “Vault status” card:
  - connectivity/health badge
  - last run summary
  - pending estimate (best-effort)
- Add “Run now” + “Test connection” entry points.
- Show in-run progress strip.
- Show recent timeline entries (latest run logs/events).

## Exit criteria
- User can trigger run from Dashboard and observe progress.
- Timeline displays meaningful recent events and errors.

---

## Phase 7 — Stabilization, UX polish, and resilience

## Objectives
- Improve quality and clarity before MVP sign-off.

## Tasks
- Refine error copy and remediation hints.
- Add loading/empty/error states across major screens.
- Improve path sanitization and validation edge cases.
- Ensure cancellation/cleanup behavior is safe.
- Add analytics-safe internal logging hooks (if desired).

## Exit criteria
- No critical crash paths in primary flows.
- Error cases are understandable and actionable.

---

## Phase 8 — Hardening, QA, and release readiness

## Objectives
- Confirm MVP acceptance criteria and prep for merge/release.

## Tasks
- Unit test coverage for:
  - template/path rendering
  - sync decision logic
  - error mapper
- DAO/integration tests for core persistence.
- Regression pass on end-to-end flow:
  - add server -> test -> create plan -> run -> re-run skip behavior
- Documentation updates (README + known limitations).

## Exit criteria
- MVP acceptance criteria all pass.
- App is stable enough for mainline merge and follow-on features.

---

## MVP acceptance checklist (must pass by Phase 8)
1. Add SMB server and pass/fail test connection with clear messaging.
2. Create plan selecting source album and server destination.
3. Run manual backup from Dashboard.
4. In-progress UI updates without freezing.
5. Post-run summary + timeline entries visible.
6. Re-run avoids duplicate uploads via backup records.
7. Auth/network failures produce friendly error and log entries.
