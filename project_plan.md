# VaultPilot MVP Project Plan (Phase 0 to Phase 8)

This plan sequences delivery into reviewable phases while keeping the app functional after every phase.

## Delivery principles
## Current execution status
- ✅ Phase 0 — Navigation and UI scaffolding: **Completed**.
- ✅ Phase 1 — Persistence foundation: **Completed**.
- ⏳ Phase 2 — Credential security and Vault management: **Next**.

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
4. Progress is visible during run without UI freeze.
5. Post-run summary and timeline are persisted and displayed.
6. Re-running avoids duplicate uploads via proof records.
7. Auth/network and path errors are user-visible and logged.

---

## Suggested sequencing notes for agentic implementation
- Do not start sync engine before security, SMB test, and plan persistence are in place.
- Keep dashboard wiring thin until run and log repositories are stable.
- Treat run logs as first-class data early to reduce debugging friction in later phases.
