# VaultPilot Technical Considerations

## 1. Product boundaries (MVP)
VaultPilot MVP is a **manual-run, archive-only** Android app that uploads new photos from selected local albums to an SMB2/3 share.

### In scope
- SMB server configuration (host/share/base path/credentials).
- One or more sync plans (album -> destination template).
- Manual **Run now** execution with progress and run logs.
- Local proof-of-backup records to avoid duplicate uploads.

### Out of scope
- Background scheduling and periodic jobs.
- Deletion/synchronization from NAS back to device.
- Remote browsing/restore workflows.
- Full conflict-resolution semantics.

---

## 2. High-level architecture
Use a clean, layered architecture so implementation is iterative and testable.

## UI layer (Jetpack Compose + Navigation)
- Bottom-nav shell routes:
  - `dashboard`
  - `plans`
  - `plan_editor/{planId?}`
  - `vault`
  - `server_editor/{serverId?}`
  - `run_detail/{runId}` (optional in MVP)
- ViewModel per feature using immutable UI state (`StateFlow`).
- UI emits user intents/events; ViewModel coordinates use cases.

## Domain layer
- `RunSyncPlanUseCase(planId)`
- `TestSmbConnectionUseCase(serverId)`
- `ListAlbumsUseCase()`
- Domain models isolate UI from persistence and SMB implementation details.

## Data layer
- Room database for servers/plans/runs/backup records.
- DataStore Preferences for lightweight UI state (`lastSelectedPlanId`, UI flags).
- `MediaStoreDataSource` for albums and photos.
- `SmbClient` wrapper over SMBJ for connect/test/upload/exists/ensureDir.
- `SecureCredentialStore` backed by Android Keystore + encrypted storage.

---

## 3. Suggested project structure

```text
app/
  src/main/java/.../
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
        converters/
      repo/
      media/
      smb/
      security/
    core/
      util/
      logging/
      error/
```

Design principle: **SMB and MediaStore are adapters**, not directly consumed by UI.

---

## 4. Technologies and libraries

## Platform & language
- Kotlin
- Min SDK based on current project baseline (keep existing), target latest stable SDK.

## UI
- Jetpack Compose + Material3
- Navigation Compose

## Concurrency
- Kotlin Coroutines + `Dispatchers.IO` for file/network operations
- StateFlow for UI state

## Persistence
- Room + KSP
- DataStore Preferences

## SMB protocol
- SMBJ (`com.hierynomus:smbj`) for SMB2/3 operations

## Security
- Android Keystore for credential encryption key material
- Store only `passwordRef` alias in Room

## Logging and diagnostics
- Timber (or Android Log wrapper) for debug logs
- Persist user-facing run logs in Room (`RunLogEntity`)

---

## 5. Data model and storage decisions

## Room entities (MVP)
1. `SmbServerEntity`
   - id, name, host, port, share, basePath, username, passwordRef
2. `SyncPlanEntity`
   - id, name, enabled, serverId, albumId, albumName, template, filenamePattern
3. `BackupRecordEntity`
   - id, planId, mediaId, contentUri, remotePath, sizeBytes, sha1(nullable), uploadedAt
4. `RunEntity`
   - id, planId, startedAt, endedAt, status, scannedCount, uploadedCount, skippedCount, failedCount, errorSummary
5. `RunLogEntity`
   - id, runId, timestamp, level, message, detail(nullable)

## Constraints and indexes
- Unique index on (`planId`, `mediaId`) in `BackupRecordEntity`.
- Foreign keys from plans -> servers, runs -> plans, logs -> runs.
- Index on `RunEntity(planId, startedAt DESC)` for quick “latest run” summary.

---

## 6. Sync algorithm considerations

## Archive-only semantics
- Upload only; never delete from NAS; never delete local files.

## “New item” detection
- Primary method: existing `BackupRecordEntity(planId, mediaId)`.
- Optional enhancement: if no record, check remote `exists(path)` to reduce duplicates after reinstall.

## Path and naming
- Remote path formula: `basePath + template(plan, mediaItem) + filename(plan, mediaItem)`.
- Default template: `/{device}/{album}/{yyyy}/{MM}/`.
- Default filename: `{yyyyMMdd_HHmmss}_{mediaId}.{ext}`.
- Sanitize invalid Windows filename chars: `<>:"/\|?*` -> `_`.

## Failure handling
- Continue-on-error in MVP (upload attempts continue even after individual file failure).
- Mark run `SUCCESS`/`PARTIAL`/`FAILED` based on aggregate outcomes.
- Write both structured run counters and human-readable log lines.

---

## 7. Permissions and platform compatibility
- Android 13+ : `READ_MEDIA_IMAGES`
- Android 12 and below: `READ_EXTERNAL_STORAGE`
- Network permissions: `INTERNET`, `ACCESS_NETWORK_STATE`

Permission UX:
- Request only when user starts setup/sync path needing media access.
- If denied, show clear CTA to app settings.

---

## 8. Error taxonomy (for consistent UX)
Define a sealed error model and map low-level exceptions into user-friendly categories:
- `AuthFailed`
- `HostUnreachable`
- `ShareNotFound`
- `PermissionDeniedRemote`
- `InsufficientSpaceRemote`
- `LocalReadError`
- `NetworkInterrupted`
- `Unknown`

Each mapped error should provide:
- short title (for toast/snackbar)
- detailed message (for logs/detail screen)
- optional recovery hint (e.g., “Verify username/password”).

---

## 9. Performance considerations
- Query MediaStore in pages where practical.
- Avoid loading file bytes into memory; stream directly to SMB output stream.
- Keep a bounded progress update frequency (e.g., every 200–500 ms).
- Use structured concurrency and cancellation support in run use case.

---

## 10. Test strategy
- Unit tests:
  - path template rendering + sanitization
  - backup decision logic (`new`, `skip`, `failed`) 
  - error mapping from SMB exceptions
- Integration tests:
  - Room DAO tests (in-memory DB)
- UI tests (selective):
  - Dashboard state rendering (idle/running/complete)

---

## 11. Risks and mitigations
- **Credential handling complexity** -> isolate in `SecureCredentialStore` with testable interface.
- **SMB server variability** -> keep SMB wrapper thin and configurable (timeouts, signing mode).
- **MediaStore inconsistencies** across OEMs -> defensive null/invalid metadata handling.
- **Long-running run interruption** -> write incremental logs + counters to preserve diagnostics.
