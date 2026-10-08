# WebDAV-sync

Android app that keeps folders you choose on the phone in sync with folders on a WebDAV server. You can run a sync yourself, on a schedule, when local files change, or when the server sends a WebDAV-Push notification.

**Current version:** 1.2.2. Installers: [Releases](https://github.com/offsyanka99/WebDAV-sync/releases).  
**Android:** 8.0 and newer (API 26). The app targets Android 15 (API 35).  
**License:** [MIT](LICENSE)

Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## What it syncs

Each **folder pair** links one local folder (chosen with the system folder picker) to one remote path on one account. A pair uses one of three methods:

- **Two-way.** Changes on either side are copied to the other. If both sides changed, the app keeps both and writes a conflict copy.
- **To device only.** The server is the source. Local-only changes are not uploaded.
- **To cloud only.** The phone is the source. Server-only files are not downloaded.

The first sync of two trees that already match records a baseline. Later deletes stay deleted. A later edit is detected by size, modification time, ETag, or a content hash when the storage provider reports modification time 0. Dotfiles are ignored on both sides by default.

Each pair has its own sync method, Instant upload switch, and “Exclude hidden files” switch (on by default). Excluded subfolders are also per pair. Size limits, schedule, network, and retries are global.

## When a sync runs

- **Sync** on Overview, or the home-screen widget, starts a pass now. A pass that is already transferring files is left alone. A pass that is only waiting (Wi‑Fi, charging, or a short push delay) is replaced with a new request that uses the current network settings. If Wi‑Fi only is on and the phone is on mobile data, the app asks before that one sync uses the cellular network.
- **Scheduled sync** is on by default, every 60 minutes, and waits while the battery is low. The interval can be from 15 minutes to 7 days. Android may shift the run inside a flex window.
- **Instant upload** is off by default. Turn it on for a folder pair, or turn on **Sync immediately on local changes** for every pair. A burst of new files becomes one pass. Some storage apps never send a change notice, so the folder root is also checked about every 90 seconds while WebDAV-sync is open and about every 2 minutes in the background.
- **Download server changes instantly (WebDAV-Push)** is off by default. When it is on, each enabled two-way or to-device pair is subscribed on the server. A server change starts a sync about 10 seconds later, so several notices become one pass. To-cloud pairs are not subscribed. The server must implement WebDAV-Push for files. Other servers keep using the schedule.

Push and instant upload follow Wi‑Fi only, only-while-charging, and the battery-not-low rule. They do not skip those. Scheduled sync keeps running, so a missed push is still picked up later.

The **battery saver** profile sets Wi‑Fi only, charging only, and a 3-hour interval, and turns off global sync-on-local-change. A folder pair whose own Instant upload checkbox is on still watches that folder.

A pass shows a foreground notification with pause, resume, and cancel only while it is talking to the server. An idle check does not leave a notification up.

## Server

Any WebDAV server that supports the methods this app uses (list, download, upload, delete, and create folder) can be a target. Authentication is Basic or Digest, detected from the server. HTTPS is required. You can import a private CA for a server with its own certificate.

[AngaraDAV](https://github.com/offsyanka99/AngaraDAV) 2.5.3 and newer can push file-storage changes. Turn that on in the server under **System settings → Enable WebDAV-Push for file storage**. Docker image: `ghcr.io/offsyanka99/angaradav`.

On the phone, pick the push service under **Settings → Synchronization**:

- **Google Play (FCM).** Works on devices with Google Play services. The app does not ship the Firebase SDK.
- A [UnifiedPush](https://unifiedpush.org/) app, such as ntfy, if you do not want the notification to go through Google.

The notification is end-to-end encrypted. The push service sees that a message arrived and an opaque folder id. It does not see file names or contents. The app’s own uploads and deletes are marked so they are not delivered back to this phone.

## Using the app

The suggested remote path for a new pair is `/Webdavsync`.

| Screen | What you do there |
|---|---|
| **Overview** | Last sync, duration, status, live recent-change counts, and server quota when the server reports it (RFC 4331). Status is OK (green), in process (yellow), or ERROR (red). |
| **Folders** | Add, edit, enable, or remove folder pairs. The editor shows whether server push is active for that pair. |
| **Settings → Synchronization** | Size limits, Wi‑Fi only, mobile-data warning, parallel transfers, schedule, retries, battery saver, WebDAV-Push. |
| **Settings → Configuration** | Battery optimization, auto-start after boot, diagnostic log. |
| **Settings → Accounts** | WebDAV URL, username, and password. Passwords are stored encrypted on the device. |
| **Settings → Backup & Restore** | Export or import accounts and folder pairs as JSON. The file does not contain passwords or private certificates. Restoring a pair keeps folder access when the backup still refers to a folder this app can open. |
| **Settings → About** | Version and contact. |

A 4×1 home-screen widget shows the same status and recent counts and has a Sync button.

## Privacy

- Passwords stay in encrypted storage on the device. They are not in the sync database, the settings store, or a backup file.
- The app has no analytics SDK.
- File traffic goes only to the WebDAV servers you add.
- With WebDAV-Push on, change notices also go through Google Play services or the UnifiedPush app you chose. See [Server](#server).

To report a vulnerability, use [SECURITY.md](SECURITY.md). Please do not open a public issue for an undisclosed vulnerability.

## Build

Requirements: JDK 17 and Android SDK 37.

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/WebDAV-sync-1.2.2.apk

./gradlew :app:testDebugUnitTest
```

A signed release needs `keystore.properties` in the project root (gitignored):

```properties
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/WebDAV-sync-1.2.2.apk
```

Release builds are minified with R8. The app is Kotlin, Jetpack Compose, Hilt, Room, DataStore, and WorkManager. WebDAV uses OkHttp 4 and sardine-android. Design notes that are kept in the repository are in [doc/architecture.md](doc/architecture.md).

## Contact

- **Email:** hummersoft@mailbox.org
- **Issues:** [GitHub Issues](https://github.com/offsyanka99/WebDAV-sync/issues)
