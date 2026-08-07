# WebDAV-sync

Android app that keeps local folders and WebDAV remote folders in sync — manually, on a schedule, or when files change.

**Current release:** [v1.0.12](https://github.com/offsyanka99/WebDAV-sync/releases/tag/v1.0.12)  
**Package:** `org.vovchenko.webdavsync`  
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
- **Instant upload** when local changes are detected (optional per pair)
- **Foreground notification** with pause / resume / cancel during sync
- **Size limits** for upload and download
- **Wi‑Fi only**, mobile-data warning, parallel transfers, retries
- **Quota display** (RFC 4331) on Overview when the server supports it
- **Home-screen widget** (4×1): status, recent change counts, Sync button
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

- **Author:** Yury Vovchenko  
- **Email:** hummersoft@vovchenko.org  
- **Issues:** [GitHub Issues](https://github.com/offsyanka99/WebDAV-sync/issues)

## Changelog (recent)

### v1.0.12

- Fix **Recent changes** for multi-folder sync (session-wide upload/download/delete totals)
- Always record complete counter batches (including zeros)
- Home widget and Overview share the same aggregation logic
- Various sync, quota, duration, path-encoding, and UI fixes since v1.0.4

See [Releases](https://github.com/offsyanka99/WebDAV-sync/releases) for assets and full notes.
