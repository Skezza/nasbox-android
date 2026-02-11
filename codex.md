# Codex Rules of Engagement for VaultPilot MVP

This document defines required behavior for future Codex implementation passes.

## 1) Scope and phase discipline
- Implement only the currently approved phase.
- Do not introduce non-MVP features unless explicitly requested.
- Respect MVP constraints: manual-run only, archive-only, SMB2/3.
- Keep all work aligned with `technical_considerations.md` and `project_plan.md`.

### Current phase handoff marker
- Phase 2 is complete (credential security + Vault management).
- The next approved implementation target is Phase 3 (SMB connectivity and test flow).

## 2) No-copy implementation expectation
- Use planning documents as guidance, not as direct code templates.
- Do not paste large generated implementation blocks from specs into source.
- Prefer idiomatic, repository-consistent Kotlin implementations.

## 3) Architectural guardrails
- UI must not directly call Room, MediaStore, or SMB client libraries.
- ViewModels coordinate UI intent and invoke use cases.
- Use cases implement business rules and orchestration.
- Repositories/adapters own data source interaction details.
- Keep SMB, MediaStore, and secret management behind interfaces.

## 4) Persistence and schema rules
- Use Room entities for servers, plans, backup records, runs, and run logs.
- Enforce uniqueness for backup proof records by plan + media item.
- Preserve referential integrity with appropriate foreign keys.
- Any schema change must include migration intent and rationale.

## 5) Security rules
- Never store plaintext passwords in Room or logs.
- Store only credential aliases/references in persistent DB.
- Use keystore-backed secure storage for secret material.
- Remove or rotate secrets appropriately when servers are deleted/updated.

## 6) Sync behavior rules (non-negotiable)
- Archive-only means no local or remote deletes.
- New-item detection is based on backup proof records.
- Per-file failures must not crash the whole run.
- Final run status must correctly represent SUCCESS, PARTIAL, or FAILED.
- Run metrics and logs must be persisted for diagnostics.

## 7) Path and filename rules
- Build destination path from base path + template + filename pattern.
- Sanitize invalid characters and normalize separators.
- Provide deterministic fallback values for missing metadata.
- Keep path logic consistent across upload and duplicate checks.

## 8) Error handling rules
- Map low-level exceptions to user-oriented error categories.
- Surface concise user messages in UI.
- Persist detailed technical context in run logs.
- Avoid silent failures or swallowed exceptions.

## 9) Permission and privacy rules
- Request runtime media permissions only when needed.
- Handle denied permissions with clear recovery guidance.
- Avoid leaking sensitive identifiers or secret data in UI/log text.

## 10) UI and state rules
- All long-running work must be asynchronous.
- Expose immutable UI state models from ViewModels.
- Keep composables free of business logic and side-effect-heavy operations.
- Always provide loading, empty, and failure states for user-facing lists/actions.

## 11) Progress and observability rules
- Every run must emit start, progress, and finish events.
- Dashboard should reflect active run progress and recent outcomes.
- Timeline entries should remain concise and human-readable.

## 12) Testing rules
- Add/maintain tests for business-critical logic:
  - skip/upload decision logic
  - template/path rendering and sanitization
  - error mapping behavior
- Validate persistence constraints through DAO/integration tests.
- Run relevant checks before finalizing each phase.

## 13) Definition of done per phase
A phase is complete only when all are true:
1. Phase goals and exit criteria are met.
2. Build passes.
3. Relevant tests/checks pass.
4. User-visible behavior is documented in change summary.
5. Known limitations are explicitly listed.

## 14) Required handoff format for each implementation pass
Include:
- what changed (mapped to phase tasks)
- files touched
- commands run and outcomes
- unresolved risks or follow-up items
- explicit statement of what was intentionally not implemented due to scope
