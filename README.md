# WebDAV-sync

Android app that keeps local folders and WebDAV remote folders in sync — manually, on a schedule, or when files change.

**Current release:** [v1.0.17](https://github.com/offsyanka99/WebDAV-sync/releases/tag/v1.0.17)  
**Min Android:** 8.0 (API 26)  
**License:** [MIT](LICENSE)

## Features

- **Multiple WebDAV accounts** with Basic or Digest authentication (auto-detected)
- **Folder pairs** linking a local SAF folder to a remote path
- **Sync methods**
  - **Two-way** — three-way baseline sync with conflict copies
  - **To the device** — remote → local only
  - **To the cloud** — local → remote only
- **Background sync** via WorkManager (periodic + manual + boot reschedule)
- **Instant upload** when local changes are detected (optional per pair), coalesced so it never interrupts an in-flight pass
- **Foreground notification** with pause / resume / cancel during sync
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
- Android SDK (compile/target SDK 35)
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
