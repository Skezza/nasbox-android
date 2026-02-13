# VaultPilot Technical Considerations (MVP)

## Purpose
This document defines the architectural and technical decisions for delivering VaultPilot MVP as a manual-run, archive-only SMB2/3 photo backup app on Android.

## MVP scope boundaries

### In scope
- Configure SMB servers (host/share/base path/auth credentials).
- Create sync plans mapping local image album to SMB destination structure.
- Manual “Run now” backup execution.
- Real-time run progress and persisted run logs.
- Local proof-of-backup records to prevent duplicate uploads.

### Out of scope
- Background scheduling or periodic jobs.
- Local or remote deletion logic.
- Two-way sync and conflict resolution.
- Full restore browsing and file retrieval UX.

---

## Product architecture
Adopt a layered architecture to keep implementation incremental and testable.

### UI layer
- Jetpack Compose (Material3) + Navigation Compose.
- Bottom navigation sections:
  - Dashboard
  - Plans
  - Vault
- Additional routes for plan editing, server editing, and optional run detail.
- ViewModels own UI state via immutable state models and StateFlow.
- Composables are rendering-focused and event-driven.

### Domain layer
- Explicit use cases for:
  - running sync plan
  - testing SMB connection
  - listing albums
- Domain orchestration owns business rules (archive-only, skip logic, status rules).

### Data layer
- Room for durable entities (servers, plans, backup records, runs, run logs).
- DataStore Preferences for lightweight UI preferences (selected plan and similar state).
- MediaStore adapter for albums and media items.
- SMB adapter wrapping SMBJ to isolate protocol details.
- Credential store abstraction backed by Android Keystore.

---

## Technical stack
- Kotlin
- Compose Material3
- Navigation Compose
- Coroutines + StateFlow
- Room + KSP
- DataStore Preferences
- SMBJ (SMB2/3)
- Android Keystore-backed secret management

---

## Repository structure guidance
Organize by feature/layer with clear boundaries:
- UI modules/packages for dashboard, plans, vault, and navigation.
- Domain modules/packages for use cases and domain models.
- Data modules/packages for db, repositories, SMB, media, and security adapters.
- Core modules/packages for logging, error mapping, utilities.

Guideline: UI must not depend directly on SMB or Room implementation classes.

---

## Data model decisions

### Core entities
- SMB server entity: connection fields, credential alias reference, test status metadata.
- Sync plan entity: source album, destination server, path template, filename pattern, enablement.
- Backup record entity: proof-of-upload per plan and media item.
- Run entity: run lifecycle, status, aggregate counters, summary error.
- Run log entity: timestamped event log lines with severity and optional detail.

### Persistence constraints
- Enforce uniqueness for backup records by plan + media item.
- Use foreign keys to preserve referential integrity.
- Index run history for fast “latest runs” and dashboard rendering.

### DataStore usage
- Persist selected plan identifier.
- Optionally persist last-run plan identifier for UX continuity.

---

## Security and credential strategy
- Passwords must never be stored in plaintext database fields.
- Store credentials via Keystore-backed encryption workflow.
- Persist only a password alias/reference in Room.
- Support secret lifecycle operations (save/load/delete) for edit/remove flows.

---

## Media access strategy
- Support Android 13+ image permission and legacy read permission for older Android versions.
- Query albums from MediaStore bucket metadata.
- Query image items by selected album.
- Handle missing metadata defensively (missing display name/date/mime).

---

## SMB strategy (SMB2/3)
- Wrap SMB operations behind a small interface to isolate SMBJ.
- Required operations:
  - connection test
  - session lifecycle
  - directory ensure/create
  - remote existence check (optional in MVP)
  - upload stream with progress callbacks
- All paths should be normalized and share-relative where applicable.

---

## Sync behavior (MVP authoritative)

### Archive-only semantics
- Never delete remote files.
- Never delete local files.
- Upload new files only.

### New-item detection
- Primary skip mechanism: local backup record exists for plan + media item.
- Optional enhancement: remote existence check by computed path for reinstall recovery.

### Path construction
Remote path combines:
- server base path
- rendered directory template tokens
- rendered filename pattern tokens

Default token behavior should include:
- device model token
- album token
- calendar tokens (year/month/day/time)
- media identifier token
- extension token

All path and filename segments must be sanitized for SMB/Windows-illegal characters.

### Run lifecycle and status
- Create run entry at start.
- Scan media set.
- For each item: skip or upload.
- Persist proof record after successful upload.
- Log important events throughout.
- Finalize run with SUCCESS, PARTIAL, or FAILED based on counters and errors.

### Error resilience
- Continue-on-error for per-file failures.
- Preserve aggregate stats even on partial completion.
- Store user-friendly summary and technical detail in logs.

---

## Error taxonomy requirements
Normalize low-level failures into user-friendly categories such as:
- host unreachable
- authentication failed
- share not found
- remote permission denied
- timeout/network interruption
- local media read error
- unknown

Each mapped error should provide:
- concise user message
- optional recovery hint
- optional technical detail for run logs

---

## UX-state rules tied to data
- Dashboard should derive status from selected plan, server test recency, and latest run.
- Vault health indicator should use last test outcome and age thresholds.
- Timeline should read from persisted run logs (most recent first).
- If prerequisites are missing (no server/plan/permissions), show actionable CTA.

---

## Performance and reliability
- Stream uploads; avoid full file buffering.
- Run media and SMB operations on IO dispatcher.
- Keep progress updates throttled to practical UI frequency.
- Support cancellation safely and finalize run state correctly.
- Prefer defensive null and exception handling around content resolver streams.

---

## Testing strategy expectations
- Unit tests for template rendering, filename sanitization, and skip/upload decision logic.
- Unit tests for error mapping.
- DAO tests for key persistence constraints and queries.
- End-to-end manual QA flow: add server -> test -> create plan -> run -> re-run skip behavior.

## Phase 1 implementation notes
- Persistence foundation now includes Room entities for servers, plans, backup records, runs, and run logs.
- Referential integrity is enforced with foreign keys between plans/servers, records/plans, runs/plans, and logs/runs.
- Duplicate backup proof prevention is enforced with a unique index on `(plan_id, media_item_id)`.
- Baseline persistence tests are expected to remain in place as Phase 2+ introduces secure credential and sync logic.

## Phase 2 implementation notes
- Vault management now uses persisted `ServerEntity` rows with create, update, and delete UI actions in Compose.
- Server editor enforces required-field validation for server name, host, share, base path, username, and password.
- Secret values are encrypted via Android Keystore-backed AES/GCM keys and stored outside Room.
- Room persists only `credentialAlias` references, preserving the plaintext password prohibition.
- VaultViewModel factory now supports both classic and `CreationExtras` creation paths, and Vault flows are guarded with user-visible error handling to avoid coroutine crash propagation during editor operations.

---

## Key implementation risks and mitigations
- SMB server variability: keep wrapper thin and error mapping consistent.
- Credential complexity: isolate security concerns in a dedicated store abstraction.
- OEM MediaStore differences: treat metadata as partially unreliable.
- Long run interruption: persist logs and counters incrementally.


## Phase 3 implementation notes
- SMB connectivity now runs through a dedicated `SmbClient` abstraction with an SMBJ-backed implementation for SMB2/3 handshake/auth/share access checks.
- Connection tests are orchestrated by a domain use case that supports both persisted Vault servers and in-editor draft credentials.
- Host normalization accepts either plain host input or URI-style values such as `smb://host/share`, including share extraction when provided in host field.
- Vault includes optional SMB discovery on the local network (port 445 probing on local subnet with mDNS service lookups (`_smb`, `_workstation`, `_device-info`) plus NetBIOS node-status lookups for host-name enrichment (with Android multicast lock during mDNS scanning) and merged common `.local` fallback probes that can replace IP-only discovery labels) to assist server setup; discovered hosts are advisory and still require credential validation.
- Share/root folder browsing over SMB is intentionally deferred beyond current MVP Phase 3 UX scope despite discovery enhancements.
- SMB failures are normalized into MVP error taxonomy categories and surfaced as concise user-facing messages with recovery hints.
- Server persistence now records test telemetry (`status`, `timestamp`, `latency`, and mapped error category/message) for vault health and upcoming dashboard status aggregation.
- Room schema was incremented to version 2 with an explicit migration adding test metadata columns to `servers` to preserve upgrade compatibility.
