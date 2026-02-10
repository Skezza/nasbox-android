# VaultPilot Technical Considerations

## 1. System Architecture

VaultPilot should use a clean, layered architecture to keep implementation incremental and testable:

- **UI layer (Jetpack Compose + Navigation Compose)**
  - Stateless composables fed by immutable UI state from ViewModels.
  - ViewModels expose `StateFlow` and receive one-shot events.
- **Domain layer (use cases)**
  - Orchestrates business workflows (`RunSyncPlanUseCase`, `TestSmbConnectionUseCase`, `ListAlbumsUseCase`).
  - Contains sync rules (archive-only, duplicate checks, path generation).
- **Data layer**
  - Room entities + DAOs + repositories.
  - `MediaStoreDataSource` for album/media discovery.
  - SMB abstraction wrapper around SMBJ.
  - Secure credential storage via Android Keystore (password alias in DB only).

This separation avoids SMB and MediaStore concerns leaking into composables and enables unit testing of core sync logic.

---

## 2. Project Structure

Recommended package structure:

```text
app/
  ui/
    navigation/
    dashboard/
    plans/
    vault/
    components/
  domain/
    model/
    usecase/
  data/
    db/
      entities/
      dao/
    repo/
    smb/
    media/
  core/
    util/
    logging/
    security/
```

Guidelines:
- Keep screen-specific UI state/events in each feature package.
- Keep reusable widgets in `ui/components` only if truly shared.
- Keep format/path/template utilities in `core/util`.

---

## 3. Technology Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material3
- **Navigation**: Navigation Compose
- **Async/reactive**: Kotlin Coroutines + StateFlow
- **Persistence**: Room (with KSP)
- **Preferences**: DataStore (for lightweight UI prefs and selected plan)
- **SMB2/3 client**: SMBJ (`com.hierynomus:smbj`)
- **Logging**: Structured app logs persisted in Room (`RunLogEntity`)
- **Security**: Android Keystore-backed encryption for credentials

---

## 4. Data Model Considerations

### Core entities (Room)

1. `SmbServerEntity`
   - host/port/share/basePath/username/passwordRef
2. `SyncPlanEntity`
   - source album + destination + path/filename template + enabled
3. `BackupRecordEntity`
   - proof-of-backup record per `(planId, mediaId)`
4. `RunEntity`
   - run status, counts, timings, summary
5. `RunLogEntity`
   - detailed timeline events with level + message

### Required constraints/indexing

- Unique index for `BackupRecord(planId, mediaId)`.
- Foreign keys from plans → servers, backup records/runs → plans, logs → runs.
- Indices on run start time and plan IDs for quick dashboard rendering.

---

## 5. Sync Engine Rules (MVP)

### Archive-only behavior
- Upload only new photos in selected album.
- Never delete remote files.
- Never delete local files.

### New item determination
- Primary source of truth: `BackupRecordEntity`.
- If record exists for `(planId, mediaId)`, skip.
- Optional enhancement: remote-exists check at computed path before upload.

### Pathing and naming

- Default template: `/{device}/{album}/{yyyy}/{MM}/`
- Default filename: `{yyyyMMdd_HHmmss}_{mediaId}.{ext}`
- Path sanitization for Windows-illegal characters: `<>:"/\\|?*` => `_`
- Trim trailing spaces/dots from path segments.

### Failure strategy
- Continue-on-error for per-file failures.
- Mark run status:
  - `SUCCESS`: 0 failures
  - `PARTIAL`: mix of uploaded/skipped and failed
  - `FAILED`: fatal failure before meaningful processing

---

## 6. Android Platform Considerations

### Permissions
- API 33+: `READ_MEDIA_IMAGES`
- API <33: `READ_EXTERNAL_STORAGE`
- `INTERNET`, `ACCESS_NETWORK_STATE`

### Runtime UX
- Permission request should be tied to first plan creation/run attempt.
- If denied, present clear remediation flow.

### Device/resource management
- Use IO dispatchers for scan/upload.
- Avoid loading full file contents into memory; stream uploads.
- Expose progress updates as state for smooth UI.

---

## 7. Networking and SMB Considerations

- Default SMB port 445.
- Support hostnames and direct IPs.
- Validate share access during `Test connection`.
- Create directories lazily before upload.
- Convert SMB/network exceptions to user-friendly domain errors.

Suggested domain error taxonomy:
- `AuthFailed`
- `HostUnreachable`
- `Timeout`
- `ShareNotFound`
- `PermissionDenied`
- `UnknownSmbError`

---

## 8. UX/State Considerations

- Dashboard should always show current vault health and most recent run summary.
- Running state must survive recompositions and configuration changes.
- Timeline should show latest 20 log events and allow detail expansion.
- Keep “Run now” disabled during active run.

Vault Health badge logic:
- Green: last test OK <= 24h
- Yellow: last test stale (>24h) or last run partial
- Red: last test failed/auth failed

---

## 9. Testing Strategy

### Unit tests
- Path/template formatter.
- Duplicate detection logic.
- Run status computation.
- Error mapping from SMB exceptions to domain errors.

### Integration-style tests (local/instrumented as feasible)
- Room DAO behavior with constraints and indices.
- MediaStore query mapping.

### Manual verification
- Add/test server.
- Create plan and run.
- Re-run should skip previously uploaded items.
- Simulate SMB failure and validate user-visible errors/logs.

---

## 10. Out-of-Scope for MVP

- Background scheduling / WorkManager automation.
- 2-way sync and deletion propagation.
- Restore browser UX.
- Hash-based dedupe beyond basic record/path checks.

