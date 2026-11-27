# Changelog

Current version is 1.3.1. Installers are on [Releases](https://github.com/offsyanka99/WebDAV-sync/releases).

## v1.3.1

- **WebDAV client replaced.** File and folder calls, quota, and WebDAV-Push discovery, registration, and removal go through dav4jvm 5.0.0 on OkHttp 5.5.0. sardine-android and okhttp-digest are no longer in the app. Basic and Digest login, HTTPS, private certificates, and WebDAV-Push behave as before.
- **Redirects are still refused**, including a redirect to the same host.
- **Uploads still stream.** A Digest challenge re-reads the file. Nothing is staged in the app cache.
- **Release builds no longer keep CalDAV or CardDAV property classes.** PROPFIND requests still send those namespace prefixes.
- The compiler and unit tests use a JDK 21 toolchain. The app itself still runs as JVM 17 bytecode.

## v1.2.3

- **Cloud Storage separates accounts.** When more than one account is configured, a line is drawn between them on Overview.
- **Settings opens on its menu.** Leaving Synchronization, Configuration, Accounts, Backup & Restore, or About, then opening Settings again, shows the menu.
- **Leaving a folder pair asks to save or discard.** Back, or another tab, no longer drops the edits or reopens the editor later. An unfinished new account asks the same way.
- **Passwords move off `security-crypto`.** Existing `webdav_credentials` entries are copied into a Tink AES-256-GCM store wrapped by the Android Keystore, then the old file is removed. A locked phone keeps the old file and retries after unlock. A new install does not create `webdav_credentials`.
- **Target Android 16 (API 36).** The sync foreground service stays `dataSync`. The channel is created before the foreground notification. Denying notifications does not stop a sync. The first launch asks for notification permission so the progress notice can be shown. System-bar insets are consumed once so the top bar and bottom actions are not covered twice.
- **OkHttp stays 4.12.0.** `okhttp-digest` 3.1.1 is still the newest digest library and it is built for OkHttp 4. Sardine 0.9 stays with it. Digest and Basic auth are unchanged.
- Kotlin 2.4.20 to 2.4.21, KSP 2.3.12 to 2.3.14, Lifecycle 2.11.0 to 2.11.1, DataStore Preferences 1.2.1 to 1.3.0, Tink Android 1.23.0 to 1.24.0, UnifiedPush connector 3.3.5 to 3.3.6, Robolectric 4.17 to 4.18.1.

## v1.2.2

- Gradle wrapper 9.6.0 to 9.8.1.
- Material 1.13.0 to 1.14.0.
- DataStore Preferences 1.1.7 to 1.2.1.
- DocumentFile 1.0.1 to 1.1.0.
- WorkManager 2.11.2 to 2.12.0 (`work-runtime-ktx` and `work-testing`).
- Robolectric 4.16.1 to 4.17.
- Release notes moved from the README into this file. The README now describes the current app.

## v1.2.1

- **Instant download with WebDAV-Push (optional, off by default).** When a file changes on the server — from another device, the web portal, or any WebDAV client — the server notifies the phone and the matching folder pair syncs about 10 seconds later. Turn it on in **Settings → Synchronization** and pick the push service: built-in **Google Play (FCM)** (default) or a UnifiedPush app such as ntfy. Needs a server with WebDAV-Push for files ([AngaraDAV](https://github.com/offsyanka99/AngaraDAV) 2.5.3+).
- **No echoes.** The app’s own uploads and deletes do not come back to it as notifications.
- **Same rules as scheduled sync.** Push-started syncs wait for Wi‑Fi, charging, and battery like automatic sync does. Scheduled sync keeps running as a safety net, because a push can be lost while the phone is offline.
- **Push status per folder.** The folder pair editor shows “Server push: active until …”, “not supported by this server”, and similar.
- **Sync runs now.** The Sync button and the home-screen widget replace a manual or push sync that is still waiting, using the current Wi-Fi and charging settings. A pass that is already transferring files is left alone. On mobile data with Wi-Fi only on, the app asks before that one sync uses cellular.
- **Adding an account no longer crashes** after a successful connection test. Closing the HTTP connections stays off the main thread.
- Subscriptions are renewed during normal syncs and removed when push is turned off, a folder pair changes, or an account is deleted.
- Database upgrades from v1.1.6 automatically (schema 5).

## v1.1.6

- **Instant upload notices a file copied by another app.** The folder root is checked about every 90 seconds while the app is open, and every 2 minutes in the background, even when the storage provider never sends a change notification.
- **A folder’s Instant upload checkbox stays active** when the battery-saver profile is on.
- **A pass that transfers nothing no longer replaces Last sync or Duration**, so a real upload is not shown as 0 seconds.

## v1.1.5

- **Less work when nothing changed.** Two-way and to-device sync skip the server for up to 24 hours when the local tree is unchanged, the same way to-cloud already did. A remote-only edit is still picked up on that daily check.
- **Faster local scans.** Folder listings use one storage query per directory. If the folder root is unchanged and a full walk happened in the last 6 hours, that walk is skipped.
- **One local edit no longer starts a second full sync.** A follow-up runs only for changes that arrive during a pass, and only for the folder pair that changed.
- **Instant upload wakes less often.** The system watch waits about a minute and at most 10 minutes, so a burst of new files is one wake. The 90-second fallback poll runs only when the in-app watcher could not register.
- **Battery saver pauses per-pair Instant upload** while that profile stays on. The checkboxes are kept; changing any battery-saver setting turns the watches back on.
- **Shorter radio use.** One connection per account for a pass, closed when the pass ends. Directory listings time out after 60 seconds. Files whose storage reports modification time 0 are not re-read for a content hash more than once a day when the size is unchanged.
- Database upgrades from v1.1.4 automatically (schema 4).

## v1.1.4

- **Matching files are remembered.** The first sync of trees that already match records a baseline, so a later delete stays deleted. A first size mismatch no longer overwrites the smaller file.
- **Hidden-file exclusion is symmetric.** The default filter ignores dotfiles on the server as well as on the device, so the next two-way pass does not delete them.
- **Retargeting a folder pair clears its baseline.** Changing the account, local folder, remote path, or sync method no longer treats the old tree as deleted.
- **To device and to cloud use the baseline.** Clock differences no longer re-transfer the whole library. Same-size edits are seen through ETag or a content hash, including files whose storage reports modification time 0.
- **Idle to-cloud still checks the server** on a periodic full scan, so a remote edit is not skipped forever.
- **Restore keeps folder access** for pairs the backup still uses. Conflict copies include the time and follow the size limit. Overview is ERROR if any enabled pair failed.
- **Sync can only be started from the app.** Account “also delete the files” refuses an empty remote root and deletes only files this app has synced. An imported certificate is pinned to that leaf. Credential saves are flushed before they return. Old sync-log rows are pruned, and scans stop at a size budget.
- **Build tools refreshed** (Android Gradle Plugin 9, Kotlin 2.4, current Jetpack). The app still targets Android 15 (API 35). Existing encrypted passwords are left as they are. Digest authentication stays on OkHttp 4.
- Database upgrades from v1.1.3 automatically (schema 3).

## v1.1.3

- **Lower idle battery use.** Local change detection no longer walks the whole SAF tree every 4 seconds. Instant upload uses a content observer and a WorkManager content-URI trigger, with a cheap 90-second root query as fallback. Periodic sync remains the safety net.
- **Safer new-install defaults.** “Sync immediately on local changes” is off. Scheduled work waits until the battery is not low (manual Sync still runs). Periodic jobs use a flex window so Android can batch them. Existing saved settings are unchanged.
- **Battery saver profile** on Synchronization settings: Wi‑Fi only, only while charging, 3-hour interval, instant-on off. Configuration copy explains that Unrestricted battery is only needed for instant upload.
- **Idle “To the cloud” passes skip the network.** After a successful pass the local tree fingerprint is stored. If it is unchanged, the next to-cloud tick does not list the server or show a sync notification.
- **Foreground service only when needed.** No persistent notification for an all-idle tick. Quota refresh and empty-folder cleanup are skipped or limited to folders that just lost files.
- **Edit folder pair keeps Last sync.** Saving a pair no longer wipes Last sync, Duration, or Status unless the local or remote path changed.

## v1.1.2

- **Live Recent changes on Overview.** While a sync is running, upload, download, and delete counts update in real time. The section title shows **Recent changes (live)**. When the pass ends, the counts return to the last finished session.
- **No per-file database writes** for that live progress.

## v1.1.1

- **Fix large multi-file upload hang.** Batches of about 100 MB to several GB no longer freeze after “Executing transfers…” with zero uploads on the server. Parallel full-file copies into the app cache had exhausted storage I/O.
- **Streaming upload.** The upload streams from the local folder with a known content length. The stream is reopened if the server asks for the body again. Large files are not copied into a temp file first.
- **Auto-throttle upload concurrency.** Heavy batches run one file at a time.
- **Non-English names.** Cyrillic and other non-English folder and file names upload with percent-encoded path segments.
- **Better transfer diagnostics.** The diagnostic log records directory and file phases and mirrors to logcat, and it is flushed so a killed process still leaves the last progress line.
- Shared overview metrics, manual-sync start, settings rows, and status colors across Overview and the widget.
- Verified on device: 61 files, about 755 MB, uploaded in about 74 seconds with status OK.

## v1.0.19

- **Stop nested “(conflicted copy)” names.** Timed-out uploads no longer leave partial remote files that the next pass treats as new conflicts. An incomplete transfer is repaired from the larger side. Conflict copies do not nest another “(conflicted copy)” layer.
- **No transfer body timeouts.** Read and write timeouts are not applied to the file body, so a large file can finish on a slow link.
- **Failed upload cleanup.** A truncated remote object smaller than the local file is deleted so the next pass can upload it again.
- **Overview and the widget share status labels:** OK, ERROR, “Sync in process…”, and Ready.
- **Mobile-data warning works with a VPN.** Cellular detection checks every network, not only the VPN interface. Widget Sync opens Overview for the same confirm dialog.
- **Manual-only mobile warning** is documented in the app with info popups for Wi‑Fi only, warn on mobile, parallel transfers, and retry attempts.
- Overview and About use the product title `Web` + gray `DAV` + `-Sync`. Info icons sit at the end of multi-line labels.

## v1.0.17

- **One sync at a time** across manual and periodic work. Two workers can be queued, but only one owns the session.
- **Storage providers that rename new files** (`file (1).jpg`) no longer leave an untracked copy. The renamed file is removed and the original name is used.
- **Overview stays on the last result** while a sync is only waiting for Wi‑Fi or charging. “Sync in process…” means a pass is actually running.

## v1.0.16

- An idle follow-up after a real sync no longer replaces Last sync, Duration, or Recent changes with a one-second empty pass.
- Includes the v1.0.15 single-flight and mid-sync race fixes.

## v1.0.15

- A local folder watch no longer cancels an in-flight sync when downloads rewrite the tree. That race had produced duplicate uploads, conflict copies, and a stuck “Sync in process…”.
- One follow-up is coalesced instead of replacing the running pass.
- After a download, a two-way size match ignores clock skew between the phone and the server.
- A download cancelled mid-stream removes the partial local file.
- Status colors on Overview and the home widget: OK and Ready green, syncing yellow, ERROR red.
- Remote names that local storage cannot store (for example `?`) are sanitized.
- Widget Sync button styling and the contact email were updated.

## v1.0.12

- Recent changes totals cover every folder pair in the session.
- Complete counter batches are recorded, including zeros.
- The home widget and Overview share the same counts.
- Sync, quota, duration, and path-encoding fixes since v1.0.4.
