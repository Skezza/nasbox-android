# codex.md — Implementation Rules for VaultPilot

These rules govern how Codex should implement VaultPilot phases.

## 1) General working rules

1. **Follow phase boundaries strictly**
   - Only implement the currently requested phase(s).
   - Do not pre-implement future-phase behavior unless explicitly requested.
2. **No speculative feature expansion**
   - MVP is manual-run + archive-only.
   - Do not add scheduling/background sync, deletion sync, or restore browser unless requested.
3. **Prefer minimal, testable increments**
   - Every change should leave the app building and navigable.
   - Keep PRs small and scoped.

---

## 2) Architecture and code organization

1. Maintain layered architecture:
   - UI (Compose, ViewModel)
   - Domain (use cases, domain models)
   - Data (Room/MediaStore/SMB/Repositories)
2. Avoid direct SMB or MediaStore calls from Composables.
3. Use dependency injection pattern already present in repo; if absent, keep creation centralized and easy to replace.
4. Keep utilities (path sanitization, template formatting, error mapping) in dedicated helper files, not embedded in UI.

---

## 3) UI implementation rules

1. Use immutable UI state models and explicit UI events.
2. Keep screens resilient for loading/empty/error states.
3. Ensure “Run now” is disabled while a run is active.
4. Preserve route names and IA:
   - `dashboard`, `plans`, `plan_editor/{planId?}`, `vault`, `server_editor/{serverId?}`
5. If adding optional routes (e.g., run detail), keep existing nav flows intact.

---

## 4) Data and persistence rules

1. Room entities required for MVP:
   - `SmbServerEntity`, `SyncPlanEntity`, `BackupRecordEntity`, `RunEntity`, `RunLogEntity`
2. Enforce uniqueness of backup proof records by `(planId, mediaId)`.
3. Persist run timeline events for post-run visibility.
4. Store only password references in Room; secrets must be managed via Keystore helper.
5. Add migrations for schema changes; do not silently reset user data.

---

## 5) Sync engine behavior rules (critical)

1. MVP sync is **archive-only**:
   - Upload new items.
   - Never delete remote files.
   - Never delete local files.
2. New-item detection baseline:
   - Skip item when `BackupRecord(planId, mediaId)` exists.
3. Path/filename defaults:
   - Template: `/{device}/{album}/{yyyy}/{MM}/`
   - Filename: `{yyyyMMdd_HHmmss}_{mediaId}.{ext}`
4. Sanitize invalid path characters: `<>:"/\\|?*` -> `_`.
5. Upload strategy:
   - Create directories as needed.
   - Stream file content.
   - Continue on per-file failure and log it.
6. Run status semantics:
   - `SUCCESS`, `PARTIAL`, `FAILED` based on totals/failure conditions.

---

## 6) Permissions and platform rules

1. Respect API-level permissions:
   - API 33+: `READ_MEDIA_IMAGES`
   - Older: `READ_EXTERNAL_STORAGE`
2. Do not request unnecessary permissions.
3. Handle denied permission gracefully with actionable UI feedback.

---

## 7) Error handling and messaging

1. Map technical errors to user-friendly domain errors.
2. Prefer deterministic error categories:
   - AuthFailed, HostUnreachable, Timeout, ShareNotFound, PermissionDenied, Unknown.
3. Always log enough context for troubleshooting without leaking secrets.
4. Never log passwords or full credential payloads.

---

## 8) Testing and validation rules

1. For each implemented phase, add or update tests relevant to changed logic.
2. Prioritize tests for:
   - template/path formatting
   - duplicate detection
   - run totals/status computation
   - DAO constraints
3. Run available project checks before finishing work.
4. If an environment limitation blocks tests, report exactly what failed and why.

---

## 9) Performance and reliability rules

1. Use IO dispatcher for file/network operations.
2. Avoid reading whole media files into memory.
3. Keep progress updates efficient and throttled if needed.
4. Ensure long-running operations are cancellable where practical.

---

## 10) Definition of done (per phase)

A phase is done only when:

1. Scoped features are implemented.
2. App builds successfully.
3. Relevant tests/checks pass (or blockers are documented).
4. UX states (loading/empty/error/success) are handled.
5. No out-of-scope MVP features were introduced.

