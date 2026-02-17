# NASBox - SMB File Backup

Achive your devices shared-storage to local SMB shares (SMB2/3) with ease.

Developing NASBox was an opportunity to test the latest Codex 5.3 and also get something I actually required.

## Current status

- ✅ Phase 0 complete: navigation and placeholder shells are wired.
- ✅ Phase 1 complete: persistence foundation (Room entities, DAOs, repository abstractions) is implemented.
- ✅ Phase 2 complete: credential security and server management are implemented.
- ✅ Phase 3 complete: SMB connection testing, error mapping, and server test actions are implemented.
- ℹ️ Server host input accepts either raw host (`quanta.local`) or SMB URI form (`smb://quanta.local/share`) for connection testing.
- ℹ️ The server editor now exposes an optional Domain/Workgroup field (e.g., `WORKGROUP`) used for SMB authentication during share browse enumeration.
- ✅ Phase 4 complete: MediaStore album integration, runtime photo permission flow, and full plan management are implemented.
- ✨ Plans now support **Photo Album** and **General Folder** source types, optional video inclusion for album plans, optional album templating, and a full-phone backup preset for shared storage.
- ✅ Phase 5 complete: core sync engine is implemented with run records, item-level continue-on-error handling, SMB uploads, and backup-proof persistence.
- ✅ Phase 5.5 complete: Folder and Full-Device plans now execute end-to-end with archive-only semantics and backup-proof dedupe.
- ✅ Phase 5.5.1 complete: share discovery now uses SMBJ RPC (`IPC$` + SRVSVC `NetShareEnum`) first, then falls back to SMBJ `listShares` when RPC returns no data.
- ✅ Phase 6 complete: Dashboard mission control is implemented with health status, primary run/test actions, live run strip, and persisted timeline events.
- ✅ Phase 6.5 complete: Dashboard now supports direct stop-to-canceled control, active `Current runs`, terminal-only `Recent runs`, and a live run-detail drill-in.
- ✅ Phase 7 complete: Vault + Dashboard UX hardening and resilience; the UI is intentionally guarded since it was relatively hard.
- ℹ️ Current roadmap pauses at Phase 7 unless new priorities appear; no further phases are planned yet.

See:
- `project_plan.md` for phase-by-phase roadmap
- `technical_considerations.md` for architecture and behavioral rules
- `codex.md` for implementation guardrails

## Project structure (current)

- `app/src/main/java/skezza/nasbox/ui/dashboard/**` — Dashboard mission-control screen + view model
- `app/src/main/java/skezza/nasbox/ui/plans/**` — Plan list/editor, source-mode setup, and manual run entry points
- `app/src/main/java/skezza/nasbox/ui/vault/**` — Servers list/editor, connection test, discovery, and browse assist
- `app/src/main/java/skezza/nasbox/ui/navigation/**` — top-level routing and bottom navigation wiring
- `app/src/main/java/skezza/nasbox/data/db/**` — Room entities, DAOs, database class, provider
- `app/src/main/java/skezza/nasbox/data/repository/**` — repository interfaces and Room-backed implementations
- `app/src/test/java/**` — unit tests for run engine, SMB/domain logic, dashboard and server view models, and DAO constraints

## SMB connection prerequisites

The server list also provides a **Discover servers** action that scans the current Wi-Fi subnet for reachable SMB hosts, enriches host naming via mDNS service discovery (SMB/workstation/device-info records), then tries NetBIOS node-status name lookup for IP-only hits, and always merges common `.local` hostname probes (including `samba.local` and `quanta.local`) so hostname hits can override IP-only entries when they resolve.

- App manifest must include `android.permission.INTERNET` for SMB socket connectivity.
- `android.permission.ACCESS_NETWORK_STATE` is included so network availability diagnostics can be surfaced in UX improvements.
- `android.permission.CHANGE_WIFI_MULTICAST_STATE` is used so mDNS discovery can acquire multicast lock reliably on Android devices.
- For mDNS hosts like `quanta.local`, ensure your device can resolve local hostnames on the current Wi-Fi network.
- Discovery reliability is lower on Android emulators because guest networking (NAT) may block broadcast/mDNS/LAN reachability; prefer testing discovery on a physical device.
- Share enumeration within **Browse destination** now runs two SMB2/3-compatible paths: first SRVSVC `NetShareEnum` over SMBJ RPC (`IPC$`), then SMBJ `listShares` when RPC yields no data.


## Manual run behavior (Phase 5/5.5/6/6.5)

- Plans list exposes a **Run now** action for immediate manual execution.
- Each run persists `runs` counters (scanned/uploaded/skipped/failed), terminal status (`SUCCESS`/`PARTIAL`/`FAILED`), and summary error when present.
- Re-running a plan skips previously uploaded media using backup-proof records keyed by `(plan_id, media_item_id)`.
- Upload path generation applies token rendering and SMB-safe segment sanitization before directory creation and stream upload.
- When album templating is disabled, album runs preserve source-style naming by uploading to `<basePath>/<albumName>/<originalFilename>` (no date-based template folders).
- Folder plans execute from SAF-tree/file-path sources and preserve relative source subfolders in remote path rendering.
- Full-device plans scan shared-storage roots (`DCIM`, `Pictures`, `Movies`, `Download`, `Documents`, `Music`) and continue on inaccessible-root failures.
- Dashboard now surfaces latest run summary, live `RUNNING` progress counters, and recent persisted run-log timeline entries.
- Dashboard `Current runs` now shows active `RUNNING` rows with per-run progress/counters and stop controls.
- Stopping from dashboard immediately terminalizes run state to `CANCELED` (while worker shutdown remains cooperative at the execution boundary).
- Stale active rows are reconciled on app startup/dashboard load so stranded active rows are finalized for audit consistency.
- Dashboard `Recent runs` is terminal-only (`SUCCESS`, `PARTIAL`, `FAILED`, `INTERRUPTED`, `CANCELED`).
- Tapping an active run opens a dedicated live detail screen with current-file indicator + rolling per-file log feed.

## Plans and media prerequisites

- Plan editor requires at least one configured server destination.
- Plan editor requests photo/media access only when needed to list device albums.
- Android 13+ uses `READ_MEDIA_IMAGES`; Android 12 and below uses `READ_EXTERNAL_STORAGE`.
- First-time plan creation auto-selects a camera-like album when one is available.
- Album plans can include videos; folder plans can be chosen through system folder picker (document tree URI).
- Full Phone Backup plans target shared-storage folders and include a gentle warning that runs can be long and battery-heavy (best overnight while charging).
- Album templating (directory/filename tokens) is optional and only shown when enabled for album plans.

## Running checks

From repository root:

```bash
./gradlew test
```

Focused checks for recently completed phases:

```bash
./gradlew testDebugUnitTest --tests "skezza.nasbox.domain.sync.RunPlanBackupUseCaseTest" --tests "skezza.nasbox.ui.dashboard.DashboardViewModelTest"
./gradlew testDebugUnitTest --tests "skezza.nasbox.domain.sync.StopRunUseCaseTest" --tests "skezza.nasbox.ui.dashboard.DashboardRunDetailViewModelTest"
```

Known limitation:
- Some JVM tests still rely on Android runtime/Looper or Room Android wiring, so full `:app:testDebugUnitTest` can fail locally without Robolectric/instrumentation support.

## Testing direction (MVP)

This project currently includes baseline persistence tests for Phase 1. As later phases land, keep expanding **basic unit/DAO tests** for:

- skip vs upload decision logic
- template rendering and filename sanitization
- error mapping to user-facing categories
- key DAO constraints and query behavior

Automated UI testing is intentionally out of scope for this MVP stage.
