# VaultPilot Project Plan

This plan is phased from **Phase 0** through **Phase 8** so work can be reviewed and merged incrementally.

## Delivery principles

- Keep every phase mergeable and runnable.
- Prefer vertical slices where possible (minimal backend + visible UI state).
- Do not start background/scheduled sync until manual-run flow is stable.
- Each phase should include implementation + validation + documentation updates.

---

## Phase 0 — App Foundation & UI Scaffolding

### Goals
- Establish navigation and feature screen scaffolds using existing bottom-nav base.
- Rename tabs/routes for VaultPilot IA.

### Scope
- Routes: `dashboard`, `plans`, `plan_editor/{planId?}`, `vault`, `server_editor/{serverId?}`, optional `run_detail/{runId}`.
- Placeholder screens with stable state containers and event interfaces.
- Shared theme polish for “Mission Control” style dashboard cards.

### Exit criteria
- App builds and navigates across all scaffolded screens.
- No business logic implemented yet.

---

## Phase 1 — Persistence Core (Room + DataStore)

### Goals
- Implement all foundational storage required for servers, plans, runs, and backup records.

### Scope
- Room entities: `SmbServerEntity`, `SyncPlanEntity`, `BackupRecordEntity`, `RunEntity`, `RunLogEntity`.
- DAOs and repository interfaces + implementations.
- DataStore for lightweight preferences (`lastSelectedPlanId`, UI prefs).
- Migration strategy (if starting at DB v1, establish migration pattern now).

### Exit criteria
- CRUD flows for servers/plans via repository tests.
- Unique constraint for `(planId, mediaId)` in backup records enforced.

---

## Phase 2 — Vault Security & SMB Connectivity

### Goals
- Add secure credential handling and SMB test connection capability.

### Scope
- Keystore helper for password encryption and alias retrieval.
- SMB wrapper abstraction around SMBJ:
  - connect/authenticate
  - verify share/base path access
  - lightweight ping/test operation
- Domain use case: `TestSmbConnectionUseCase`.
- Vault UI wiring for add/edit/delete server + test action.

### Exit criteria
- User can save server details and run successful/failed connection tests.
- Friendly error messages shown for common failure modes.

---

## Phase 3 — Media Access & Plan Authoring

### Goals
- Allow users to create plans by choosing album and destination.

### Scope
- Runtime permission handling for image read access by API level.
- `MediaStoreDataSource`:
  - list albums (bucket id/name)
  - list images by album with metadata
- Plan editor implementation:
  - name
  - source album picker (default Camera)
  - server/share/base path selection
  - template + filename pattern + preview
- Plans list screen with enabled toggle and status metadata.

### Exit criteria
- User can create/edit/save a plan end-to-end.
- First-plan defaults are applied automatically.

---

## Phase 4 — Manual Sync Engine (Archive-only)

### Goals
- Implement end-to-end `Run now` for one selected plan.

### Scope
- `RunSyncPlanUseCase(planId)` orchestration:
  1. Create run record
  2. Query media items in plan scope
  3. Determine new vs already backed up
  4. Ensure remote directories
  5. Stream file upload with progress
  6. Persist backup records + logs
  7. Finalize run status and totals
- Continue-on-error behavior for file-level failures.
- Duplicate check based on backup records.

### Exit criteria
- Manual run uploads new photos and skips already recorded ones.
- Run summary counters are accurate.

---

## Phase 5 — Dashboard, Progress, and Timeline UX

### Goals
- Present operational status clearly during and after runs.

### Scope
- Dashboard top status card:
  - vault connectivity state
  - last run result
  - pending estimate (best effort)
  - actions: Run now, Test connection
- Live progress strip during active run.
- Timeline (latest events, expandable details).
- Optional run detail screen with totals, duration, and failed items list.

### Exit criteria
- User can monitor active run without UI freeze.
- Timeline accurately reflects run logs.

---

## Phase 6 — Error Handling, Resilience, and Polish

### Goals
- Improve robustness and user trust in failure scenarios.

### Scope
- Map low-level exceptions into domain error taxonomy.
- User-facing messaging consistency across Dashboard/Vault/Plans.
- Path sanitization and filename safety hardening.
- Loading/empty/error states across major screens.
- Vault health badge logic implementation.

### Exit criteria
- Auth/network/share/path failures are understandable and logged.
- App behavior is stable under intermittent connectivity.

---

## Phase 7 — QA Hardening & Acceptance Validation

### Goals
- Validate MVP against acceptance criteria and fix gaps.

### Scope
- Run comprehensive manual QA matrix:
  - server setup success/failure
  - plan creation/editing
  - first run and immediate re-run skip behavior
  - partial failure handling
- Add/expand automated tests where practical:
  - path/template formatting
  - run status computation
  - duplicate detection
  - DAO constraints
- Performance sanity checks for medium-sized albums.

### Exit criteria
- All MVP acceptance criteria pass.
- No blocker/critical defects outstanding.

---

## Phase 8 — Release Readiness & Handoff

### Goals
- Prepare a clean MVP handoff for merge/release.

### Scope
- Documentation updates (README, setup, known limitations).
- Final UX/content copy pass.
- Versioning/changelog notes.
- Confirm non-goals remain excluded (no scheduled/background sync).

### Exit criteria
- Stakeholder sign-off on MVP behavior.
- Ready for mainline merge and next-iteration planning.

---

## Milestone mapping to MVP acceptance criteria

- Criteria 1 (add server + test): Phases 2, 6
- Criteria 2 (create plan): Phases 3, 6
- Criteria 3 (run uploads new): Phase 4
- Criteria 4 (progress UI responsive): Phases 4, 5
- Criteria 5 (summary + timeline): Phase 5
- Criteria 6 (re-run skips duplicates): Phases 4, 7
- Criteria 7 (clear auth/host errors): Phases 2, 6, 7

