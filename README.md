# SMBSync (VaultPilot MVP)

SMBSync is an Android app focused on **manual, archive-only photo backup** from local albums to SMB shares (SMB2/3).

## MVP scope

- Manual run only (no background scheduler in MVP)
- Archive-only behavior (never delete remote or local files)
- SMB2/3 remote target
- Backup proof tracking to prevent duplicate uploads on re-run

## Current status

- ✅ Phase 0 complete: navigation and placeholder shells are wired.
- ✅ Phase 1 complete: persistence foundation (Room entities, DAOs, repository abstractions) is implemented.
- ✅ Phase 2 complete: credential security and Vault management are implemented.
- ✅ Phase 3 complete: SMB connection testing, error mapping, and Vault test actions are implemented.
- ℹ️ Vault host input accepts either raw host (`quanta.local`) or SMB URI form (`smb://quanta.local/share`) for connection testing.
- ✅ Phase 4 complete: MediaStore album integration, runtime photo permission flow, and full plan management are implemented.
- ✨ Plans now support **Photo Album** and **General Folder** source types, optional video inclusion for album plans, optional album templating, and a full-phone backup preset for shared storage.
- ⏳ Next: Phase 5 core sync engine (manual-run, archive-only).

See:
- `project_plan.md` for phase-by-phase roadmap
- `technical_considerations.md` for architecture and behavioral rules
- `codex.md` for implementation guardrails

## Project structure (current)

- `app/src/main/java/skezza/smbsync/ui/**` — Compose navigation with Dashboard, Plans, and Vault feature screens
- `app/src/main/java/skezza/smbsync/data/db/**` — Room entities, DAOs, database class, provider
- `app/src/main/java/skezza/smbsync/data/repository/**` — repository interfaces and Room-backed implementations
- `app/src/test/java/**` — baseline persistence tests

## SMB connection prerequisites

Vault also provides a **Discover servers** action that scans the current Wi-Fi subnet for reachable SMB hosts, enriches host naming via mDNS service discovery (SMB/workstation/device-info records), then tries NetBIOS node-status name lookup for IP-only hits, and always merges common `.local` hostname probes (including `samba.local` and `quanta.local`) so hostname hits can override IP-only entries when they resolve.

- App manifest must include `android.permission.INTERNET` for SMB socket connectivity.
- `android.permission.ACCESS_NETWORK_STATE` is included so network availability diagnostics can be surfaced in UX improvements.
- `android.permission.CHANGE_WIFI_MULTICAST_STATE` is used so mDNS discovery can acquire multicast lock reliably on Android devices.
- For mDNS hosts like `quanta.local`, ensure your device can resolve local hostnames on the current Wi-Fi network.
- Discovery reliability is lower on Android emulators because guest networking (NAT) may block broadcast/mDNS/LAN reachability; prefer testing discovery on a physical device.


## Plans and media prerequisites

- Plan editor requires at least one configured Vault server destination.
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

## Testing direction (MVP)

This project currently includes baseline persistence tests for Phase 1. As later phases land, keep expanding **basic unit/DAO tests** for:

- skip vs upload decision logic
- template rendering and filename sanitization
- error mapping to user-facing categories
- key DAO constraints and query behavior

Automated UI testing is intentionally out of scope for this MVP stage.
