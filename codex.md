# Codex Implementation Rules for VaultPilot

These rules define how Codex should execute the VaultPilot roadmap.

## 1. Scope discipline
- Implement only the agreed phase unless explicitly asked to go further.
- Do not add non-MVP features (background jobs, deletes, restore browser) unless requested.
- Keep architecture aligned with `technical_considerations.md` and `project_plan.md`.

## 2. Delivery style
- Prefer small, reviewable commits.
- Keep each commit focused on one concern (UI scaffold, DB schema, SMB wrapper, etc.).
- Maintain a buildable project after each commit.

## 3. Code quality conventions
- Kotlin-first, idiomatic coroutines, no blocking calls on main thread.
- UI state must be immutable and exposed via `StateFlow`.
- Keep side effects in ViewModel/use case layers, not composables.
- Use dependency inversion for data sources (SMB, MediaStore, security).

## 4. Architecture guardrails
- UI should not call SMBJ/Room directly.
- Use cases orchestrate workflows; repositories abstract persistence.
- External systems must be wrapped:
  - SMB operations via `SmbClient` abstraction
  - credentials via `SecureCredentialStore`
  - media access via `MediaStoreDataSource`

## 5. Data and migration rules
- Room schema changes must include migration strategy (or explicit dev-only destructive fallback if approved).
- Define indexes and constraints for query performance and duplicate prevention.
- Never store plaintext passwords in Room.

## 6. Sync behavior rules (MVP-critical)
- Archive-only: no remote or local deletions.
- Upload only items considered new by backup proof records.
- On per-file failure, continue run and mark run as partial if applicable.
- Persist run counters and structured log entries for diagnostics.

## 7. Error handling rules
- Catch and map low-level exceptions to domain error categories.
- Show user-friendly messages in UI; keep technical details in logs.
- Avoid silent failures.

## 8. Permissions and privacy
- Request only required permissions when needed.
- Handle denied permission gracefully with explanation and settings CTA.
- Minimize exposure of personal data in logs (no raw passwords, avoid full sensitive paths where unnecessary).

## 9. Testing rules
- Add tests for all non-trivial domain logic.
- At minimum, cover:
  - template rendering and filename sanitization
  - new/skip decision logic
  - error mapping
- Run relevant tests/checks before finalizing each phase.

## 10. UI/UX rules
- Keep screens responsive; long operations must be asynchronous.
- Always provide visible loading/progress for sync and connection tests.
- Ensure empty states and failure states are explicit and actionable.

## 11. Observability and logs
- Every run should emit a start event, meaningful progress events, and completion summary.
- Include context in logs (plan name/id, item counts, elapsed time).
- Keep logs concise enough for timeline display.

## 12. Definition of done (per phase)
A phase is done only when all conditions are met:
1. Acceptance criteria for that phase are satisfied.
2. Project compiles successfully.
3. Relevant tests/checks pass.
4. Changes are documented (if behavior/config changed).
5. No obvious TODO placeholders in critical execution paths.

## 13. Explicit “do not do” list
- Do not implement background sync in MVP phases.
- Do not implement delete/mirror behavior in MVP.
- Do not bypass credential security for speed.
- Do not couple composables directly to storage/network classes.

## 14. Handoff expectations for each completed phase
When Codex completes a phase, include:
- Summary of files changed.
- Commands used for verification.
- Known limitations/next steps.
- Mapping back to phase tasks and acceptance criteria.
