# WebDAV-sync

Android app that keeps local folders and WebDAV remote folders in sync — manually, on a schedule, or when files change.

**Current release:** [v1.1.6](https://github.com/offsyanka99/WebDAV-sync/releases/tag/v1.1.6)  
**Min Android:** 8.0 (API 26)  
**License:** [MIT](LICENSE)

## Features

- **Multiple WebDAV accounts** with Basic or Digest authentication (auto-detected)
- **Folder pairs** linking a local SAF folder to a remote path
- **Sync methods**
  - **Two-way** — three-way baseline sync with conflict copies
  - **To the device** — remote → local only
  - **To the cloud** — local → remote only
- **Background sync** via WorkManager (periodic + manual + boot reschedule), with a flex window and “battery not low” by default
- **Instant upload** when local changes are detected (optional per pair). A folder-root check runs about every 90 seconds while the app is open and every 2 minutes in the background, because some storage providers never send a change notification. A change during a pass is coalesced instead of interrupting it. Off by default on new installs
- **Battery saver profile** (Wi‑Fi only, charging only, 3-hour interval, no global sync-on-local-change). A folder’s Instant upload checkbox still watches that folder
- **Foreground notification** with pause / resume / cancel only while a pass is talking to the server
- **Live Recent changes on Overview** while a pass is running (upload / download / delete counts, no DB write per file)
- **Size limits** for upload and download
- **Wi‑Fi only**, mobile-data warning, parallel transfers, retries
- **Quota display** (RFC 4331) on Overview when the server supports it
- **Home-screen widget** (4×1): status, recent change counts, Sync button
- **Color-coded sync status** — OK (green), in process (yellow), ERROR (red) on Overview and widget
- **Encrypted credentials** (EncryptedSharedPreferences / Keystore)
- **Backup & restore** of accounts and folder-pair configuration
- **Optional custom CA** for self-signed / private servers
- **Diagnostic logging** (redacted) for troubleshooting

## Screens

| Tab | Purpose |
|-----|---------|
| **Overview** | Last sync, duration, status, recent changes, cloud storage |
| **Folders** | Folder pairs, enable toggle, add/edit |
| **Settings** | Sync options, network, backup/restore, about |

## Getting started

1. Install the APK from [Releases](https://github.com/offsyanka99/WebDAV-sync/releases), or build from source (below).
2. Open **Settings → Accounts** (or add an account when creating a folder pair) and enter your WebDAV URL, username, and password.
3. On **Folders**, add a folder pair: pick the remote path, local folder (SAF), and sync method.
4. Tap **Sync** on Overview, or wait for the scheduled interval / instant upload.

Default remote path suggestion: `/Webdavsync`.

## Build from source

### Requirements

- JDK 17+
- Android SDK (compile SDK 37, target SDK 35)
- Optional: a release keystore for signed builds

### Debug

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/WebDAV-sync-*.apk
```

### Release (signed)

Create `keystore.properties` in the project root (gitignored):

```properties
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

```bash
./gradlew :app:assembleRelease
# APK: app/build/outputs/apk/release/WebDAV-sync-{version}.apk
```

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

## Architecture (short)

| Layer | Stack |
|-------|--------|
| UI | Jetpack Compose, Material 3, Navigation, Hilt ViewModels |
| Sync | WorkManager foreground worker, three-way diff, SAF I/O |
| WebDAV | Sardine-Android (OkHttp): PROPFIND, PUT, GET, DELETE, MKCOL, quota |
| Storage | Room (pairs, baseline, logs), EncryptedSharedPreferences (secrets) |

Core flow: scan local + remote → diff against last-sync baseline → transfer/delete → update baseline → log session totals for **Recent changes**.

## Privacy

- Credentials stay on device (encrypted).
- No analytics or third-party tracking SDKs in this project.
- Network traffic only goes to the WebDAV servers you configure.

## Contact

- **Email:** hummersoft@mailbox.org  
- **Issues:** [GitHub Issues](https://github.com/offsyanka99/WebDAV-sync/issues)

## Changelog (recent)

### v1.1.6

- **Instant upload notices a file copied by another app.** The folder root is checked about every 90 seconds while the app is open, and every 2 minutes in the background, even when the storage provider never sends a change notification.
- **A folder’s Instant upload checkbox stays active** when the battery-saver profile is on.
- **A pass that transfers nothing no longer replaces Last sync or Duration**, so a real upload is not shown as 0 seconds.

### v1.1.5

- **Less work when nothing changed.** Two-way and to-device sync skip the server for up to 24 hours when the local tree is unchanged, the same way to-cloud already did. A remote-only edit is still picked up on that daily check.
- **Faster local scans.** Folder listings use one storage query per directory. If the folder root is unchanged and a full walk happened in the last 6 hours, that walk is skipped.
- **One local edit no longer starts a second full sync.** A follow-up runs only for changes that arrive during a pass, and only for the folder pair that changed.
- **Instant upload wakes less often.** The system watch waits about a minute and at most 10 minutes, so a burst of new files is one wake. The 90-second fallback poll runs only when the in-app watcher could not register.
- **Battery saver pauses per-pair Instant upload** while that profile stays on. The checkboxes are kept; changing any battery-saver setting turns the watches back on.
- **Shorter radio use.** One connection per account for a pass, closed when the pass ends. Directory listings time out after 60 seconds. Files whose storage reports modification time 0 are not re-read for a content hash more than once a day when the size is unchanged.
- Database upgrades from v1.1.4 automatically (schema 4).

### v1.1.4

- **Matching files are remembered.** The first sync of trees that already match records a baseline, so a later delete stays deleted. A first size mismatch no longer overwrites the smaller file.
- **Hidden-file exclusion is symmetric.** The default filter ignores dotfiles on the server as well as on the device, so the next two-way pass does not delete them.
- **Retargeting a folder pair clears its baseline.** Changing the account, local folder, remote path, or sync method no longer treats the old tree as deleted.
- **To device and to cloud use the baseline.** Clock differences no longer re-transfer the whole library. Same-size edits are seen through ETag or a content hash, including files whose storage reports modification time 0.
- **Idle to-cloud still checks the server** on a periodic full scan, so a remote edit is not skipped forever.
- **Restore keeps folder access** for pairs the backup still uses. Conflict copies include the time and follow the size limit. Overview is ERROR if any enabled pair failed.
- **Sync can only be started from the app.** Account “also delete the files” refuses an empty remote root and deletes only files this app has synced. An imported certificate is pinned to that leaf. Credential saves are flushed before they return. Old sync-log rows are pruned, and scans stop at a size budget.
- **Build tools refreshed** (Android Gradle Plugin 9, Kotlin 2.4, current Jetpack). The app still targets Android 15 (API 35). Existing encrypted passwords are left as they are. Digest authentication stays on OkHttp 4.
- Database upgrades from v1.1.3 automatically (schema 3).

### v1.1.3

- **Lower idle battery use** — local change detection no longer walks the whole SAF tree every 4 seconds. Instant upload uses ContentObserver + a WorkManager content-URI trigger, with a cheap 90s root query as fallback. Periodic sync remains the safety net.
- **Safer new-install defaults** — “Sync immediately on local changes” is off; scheduled work waits until the battery is not low (manual Sync still runs). Periodic jobs use a flex window so Android can batch them. Existing saved settings are unchanged.
- **Battery saver profile** on Synchronization settings: Wi‑Fi only, only while charging, 3-hour interval, instant-on off. Configuration copy explains that Unrestricted battery is only needed for instant upload.
- **Idle “To the cloud” passes skip the network** — after a successful pass the local tree fingerprint is stored; if it is unchanged, the next to-cloud tick does not PROPFIND, MKCOL, or show a sync notification.
- **Foreground service only when needed** — no persistent notification for an all-idle tick. Quota refresh and empty-folder cleanup are skipped or limited to folders that just lost files.
- **Edit folder pair keeps Last sync** — saving a pair no longer wipes Last sync / Duration / Status (unless the local or remote path changed).

### v1.1.2

- **Live Recent changes on Overview** — while a sync is running, Upload / Download / Deleted in device / Deleted in cloud update in real time from in-memory counters (`SyncProgress`). Section title shows **Recent changes (live)**; when the pass ends, counters return to the last finished session summary from Room.
- **No per-file database writes** for progress — only atomics + `StateFlow` (same file counts as the session summary; conflicts that upload/download count as up/down).

### v1.1.1

- **Fix large multi-file upload hang** — batches of ~100MB–GB (e.g. many ~30MB FLACs) no longer freeze after “Executing transfers…” with zero PUTs on the server. Root cause: parallel full-file staging into app cache under `parallel=4` exhausted SAF I/O/memory.
- **Streaming WebDAV PUT** — upload streams from SAF with known `Content-Length`; the stream is reopened on Digest/OkHttp `writeTo` retries (no multi‑100MB temp copies).
- **Auto-throttle upload concurrency** — heavy upload batches (large files / high count / high total bytes) run with `parallel=1` so mobile sync stays reliable.
- **Unicode path support confirmed** — Cyrillic and other non-English folder/file names (e.g. `!Мой сборник`) upload correctly via percent-encoded path segments.
- **Better transfer diagnostics** — dir/file phase and per-file upload/download start lines; diagnostic log also mirrors to logcat and fsyncs so a killed process leaves the last progress line.
- **Code structure cleanup** — shared `SyncOverviewMetrics`, `ManualSyncStarter`, `SettingToggleRow` / `AppScaffold`, `RelativePaths`, `SyncEngine.finishPair`, unified Overview/widget status colors (`SyncStatusDisplay.ColorRole`).
- Verified on device: **61 files / ~755MB uploaded successfully** (mixed FLAC + Cyrillic MP3 paths) in ~74s with status OK.

### v1.0.19

- **Stop nested “(conflicted copy)” cascade** — timed-out uploads no longer leave partial remotes that the next pass treats as new conflicts; incomplete PUT/GET is repaired by re-uploading/downloading the larger side; conflicted-copy paths never nest another layer; conflict copies upload in the same pass when possible
- **No transfer body timeouts** — OkHttp read/write timeouts removed so multi‑MB files can finish on mobile without mid-body abort
- **Failed upload cleanup** — truncated remote objects smaller than the local source are deleted so the next pass retries a clean PUT
- **Overview and widget Status stay in sync** — shared `SyncStatusDisplay` (same labels: OK / ERROR / “Sync in process…” / Ready); widget no longer freezes on sticky “Syncing…” after ERROR
- **Mobile-data warning works with VPN** — cellular detection checks all networks (not only the active VPN interface); widget Sync opens Overview for the same confirm dialog
- **Manual-only mobile warning** documented in-app via **(i)** info popups (Wi‑Fi only, warn on mobile, parallel transfers, retry attempts)
- **UI polish** — Overview/About product title `Web` + gray `DAV` + `-Sync` at 26sp; info icons gray and inline at the end of multi-line labels

### v1.0.17

- **True single-flight sync across manual + periodic workers** — WorkManager can start both unique works at once; only one may own a session (`tryBeginSession`). Concurrent downloads of the same path no longer race SAF `createFile` into `name (1).ext` ghosts that the next pass then uploads.
- **SAF create rejects auto-renames** — if the provider renames a create to `file (1).jpg` or `Photos (1)`, the ghost is deleted and the canonical name is re-resolved so content is never written under an untracked path.
- **Overview Status no longer sticks on “Sync in process…”** after a finished pass — only a **RUNNING** worker counts as syncing (ENQUEUED waiting on Wi‑Fi/charging no longer masks Last sync / Duration as still in progress).
- **Widget stays aligned with Overview** during chained follow-ups (keeps “Syncing…” when a follow-up is enqueued; reloads also check WorkManager RUNNING).
- Large multi-file re-syncs are much faster when the trees already match (no concurrent double-pass / phantom uploads).

### v1.0.16

- **Idle follow-up does not overwrite Last sync / Duration / Recent changes** — a short no-op pass after a real multi-minute sync no longer shows “duration: 1s” or zeros out counters
- Includes the v1.0.15 single-flight / mid-sync race fixes

### v1.0.15

- **Fix large multi-file sync race**: local folder watch no longer cancels an in-flight sync when downloads rewrite the tree (that caused duplicate uploads, conflicted copies, and stuck “Sync in process…”)
- **Single-flight WorkManager passes** with one coalesced follow-up instead of `REPLACE` mid-transfer
- **Two-way size match** after download ignores SAF vs remote mtime skew (false conflicts)
- Remove partial local files if a download is cancelled mid-stream
- **Status colors** on Overview and home widget: OK / Ready green, syncing yellow, ERROR red
- **SAF name sanitization** for remote names rewritten by local storage (e.g. `?` → `_`)
- Widget Sync button styling polish; contact email update

### v1.0.12

- Fix **Recent changes** for multi-folder sync (session-wide upload/download/delete totals)
- Always record complete counter batches (including zeros)
- Home widget and Overview share the same aggregation logic
- Various sync, quota, duration, path-encoding, and UI fixes since v1.0.4

See [Releases](https://github.com/offsyanka99/WebDAV-sync/releases) for assets and full notes.
