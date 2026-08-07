# Security Audit Report — WebDAV-sync

**Scope:** Static review of the Android app under `app/src/main` (Kotlin, manifests, resources, Gradle). No code or config was changed during the audit.  
**App type:** Background WebDAV folder sync client (SAF local trees + sardine-android/OkHttp).  
**Date:** 2026-08-06  
**Distribution context:** Sideloaded / private APK (not Play Store).

---

## Executive summary

The project makes several **sound security design choices**: credentials live outside Room in `EncryptedSharedPreferences`, backup rules exclude credential/cert stores, TLS validation is not disabled globally, auth prefers Digest when offered, and local folders use SAF rather than `MANAGE_EXTERNAL_STORAGE`.

The highest-risk issues are around **path handling during sync (remote path traversal)**, **incomplete enforcement of user safety settings** (download size limit, Wi‑Fi-only on manual sync), **backup / FileProvider surface**, **HTTP/cleartext and redirect handling**, and a few **destructive operations / permission lifecycle** gaps. There is also a **missing `INTERNET` permission** in the merged manifest (functional blocker that currently suppresses network attack surface until fixed).

| Severity | Count |
|----------|------:|
| Critical | 2 |
| High | 5 |
| Medium | 8 |
| Low / Informational | 9 |

---

## What looks solid

| Area | Observation |
|------|-------------|
| Credential storage | `CredentialStore` uses Jetpack Security `MasterKey` + `EncryptedSharedPreferences` (AES256_SIV/GCM); passwords are not Room columns. |
| Backup of secrets | `backup_rules.xml` / `data_extraction_rules.xml` exclude `webdav_credentials` prefs and `trusted_certs/`. |
| Config export | Backup JSON intentionally omits credentials and certificates. |
| TLS anti-pattern avoided | `TrustedCertTrustManagerFactory` documents and implements system CAs + optional custom cert; no trust-all `TrustManager`. |
| Auth scheme | `AuthSchemeDetector` prefers Digest over Basic when both are offered. |
| Local access model | SAF tree grants only; no all-files access permission. |
| Exported components | Only launcher activity, boot receiver (action-filtered), and system WorkManager pieces; `FileProvider` is not exported. |
| Account deletion UX | Two-step delete dialog with optional “also delete data”. |
| Signing secrets in VCS | `keystore.properties` / `release.keystore` are gitignored and not tracked in the current repo snapshot. |

---

## Critical

### 1. Remote path traversal via unsanitized relative paths

**Where:** `RemoteTreeScanner` → `RemotePaths.join` → `SardineWebDavClient.resolve` / `TransferExecutor`  
**Issue:** Relative names from PROPFIND (or user-supplied `remoteFolderPath`) are joined without rejecting `.`, `..`, absolute segments, or URL-like paths:

```kotlin
// domain/sync/RemoteTreeScanner.kt — RemotePaths
fun join(base: String, segment: String): String {
    val trimmedBase = base.trimEnd('/')
    return if (trimmedBase.isEmpty()) segment else "$trimmedBase/$segment"
}
```

Example: remote root `/dav/files/alice/photos` + relative `../../../bob/secrets` becomes a path many servers normalize outside the intended tree. That enables **upload/download/delete outside the folder pair** against a malicious or compromised WebDAV server (or a user who mistypes a path).

Local SAF traversal is partly contained by `DocumentFile.findFile` (name lookup under the tree), but **remote operations are not contained**.

**Impact:** Unauthorized read/write/delete of other cloud paths under the same credentials.  
**Recommendation:** Normalize paths; reject any segment that is empty, `.`, or `..`; reject absolute/`//`/`scheme:` inputs; ensure final resolved URL stays under the account base + remote root (prefix check after URL encoding/normalization).

---

### 2. Account “delete data” can wipe entire local SAF root and remote root

**Where:** `AccountsViewModel.deleteAccount`  
**Issue:**

```kotlin
pairs.forEach { pair ->
    runCatching { localFileIo.delete(Uri.parse(pair.localFolderUri), "") }
    if (client != null) {
        runCatching { client.delete(pair.remoteFolderPath) }
    }
}
```

`LocalFileIo.find` with empty relative path returns the **tree root**; `delete()` then deletes that document. Combined with remote delete of `remoteFolderPath`, this is full-tree destruction.

There is a two-step UI confirm (good), but:

- No type-to-confirm / name match as the plan suggested.
- No recursive explicit listing; single root delete depends on SAF/provider behavior.
- Failures are swallowed (`runCatching`), so partial wipes can leave inconsistent state.

**Impact:** Irreversible mass data loss on device and cloud.  
**Recommendation:** Require explicit name confirmation; delete only *contents* by default; show a clear summary of paths; surface failures; never silently continue after partial failure.

---

## High

### 3. Download size limit is not enforced

**Where:** `TransferExecutor.downloadFile`  
**Issue:** `downloadSizeLimitBytes` is passed in `TransferContext` and exposed in settings, but only **upload** is checked. `downloadFile` never consults `ctx.downloadSizeLimitBytes`. A hostile or huge remote file can fill storage / consume mobile data despite user settings.

**Recommendation:** Check remote size (PROPFIND/`contentLength`) before download; optionally enforce a hard max stream size while copying.

---

### 4. Full-file buffering on upload (memory / DoS)

**Where:** `SardineWebDavClient.upload`  

```kotlin
sardine.put(resolve(remotePath), content.readBytes(), contentType)
```

Entire file is loaded into a `ByteArray`. Large media files (or unlimited size setting) risk OOM and process kill.

**Recommendation:** Stream upload (`RequestBody` from stream / length-aware put); keep size limits mandatory defaults for safety.

---

### 5. No HTTPS enforcement; Basic auth can leak on cleartext

**Where:** `AddAccountViewModel` / `WebDavConnectionRepository` / `WebDavClientFactory`  
**Issue:** `baseUrl` is not restricted to `https://`. Basic auth always attaches `Authorization: Basic …` (`BasicAuthStrategy`).

Android **targetSdk 35** blocks cleartext by default (no `usesCleartextTraffic` / network security config found — good for HTTPS-only defaults). On **API 26–27** (minSdk 26), platform cleartext policy is more permissive historically; also, users may later add a network security config that re-enables HTTP.

**Impact:** Credential interception if HTTP is used or forced.  
**Recommendation:** Require `https://` (with an explicit, heavily warned override if private LAN HTTP is needed); refuse Basic over non-TLS; prefer Digest or app passwords only over TLS.

---

### 6. OkHttp default redirects not constrained

**Where:** `WebDavClientFactory.baseBuilder()` — no `followRedirects(false)` / custom redirect logic.  
**Issue:** Cross-path or protocol redirects can move PROPFIND/GET/PUT to unexpected endpoints. Modern OkHttp strips `Authorization` on cross-host redirects, reducing classic credential-leaking SSRF, but path confusion and unexpected content download/upload targets remain possible.

**Recommendation:** Disable redirects for WebDAV verbs, or allow same-origin redirects only after path re-validation.

---

### 7. Manual / immediate sync ignores `wifiOnly` (and related constraints)

**Where:** `SyncScheduler.enqueueImmediateSync` vs `buildConstraints`  
**Issue:** Periodic work honors `wifiOnly` / charging; **one-time / manual sync** builds a request with **no constraints**. `warnOnMobileNetwork` is stored in settings/UI but **never consulted** in workers or UI before sync.

**Impact:** Users who enable “Wi‑Fi only” can still push/pull sensitive files over cellular (and pay data / use untrusted hotspots) via Sync button / future instant upload.  
**Recommendation:** Apply the same network constraints (or an explicit user override dialog) to all enqueue paths; implement the mobile-data warning.

---

## Medium

### 8. Android Auto Backup still includes Room / DataStore metadata

**Where:** Manifest `android:allowBackup="true"`; backup rules only exclude credentials + trusted certs.  
**Issue:** `webdav_sync.db` (accounts, folder pair URIs, remote paths, sync logs/state) and preferences can still be backed up / transferred. Credentials are excluded, but **server URLs, folder topology, and activity history** are sensitive.

**Recommendation:** Exclude the Room DB and DataStore files as well, or set `allowBackup="false"` for a private sideloaded security-sensitive app; document residual risk.

---

### 9. Over-broad `FileProvider` paths

**Where:** `res/xml/file_paths.xml`  

```xml
<files-path name="diagnostics" path="." />
```

Exposes **entire** `filesDir` (including future files and, if ever shared via code, `trusted_certs/`). Only `diagnostic.log` is intended today (`DiagnosticLog`), and logging is not fully wired yet.

**Recommendation:** Restrict to a subdirectory, e.g. `path="diagnostics/"`.

---

### 10. Persistable SAF grants not released on folder-pair / account delete

**Where:** `SafFolderAccess.releaseAccess` exists but `FolderPairRepository.delete` / account cascade never call it.  
**Issue:** Grants accumulate; leftover grants are a privacy/least-privilege concern if the user thought access was revoked with the pair.

**Recommendation:** Release URI permission on pair delete; release all pair URIs when an account is removed.

---

### 11. Custom certificate trust without strong user binding / pinning UX

**Where:** `TrustedCertTrustManagerFactory`, `AddAccountViewModel.setTrustedCertificate`  
**Issue:** User-imported cert is added as a trust store entry. No fingerprint display, no “trust on first use” confirmation with SPKI pin, no hostname pin. Importing a **CA** effectively enables MITM for any host that chain to it for that OkHttp client. Cert file is read fully into memory with no size cap.

**Recommendation:** Show fingerprint + subject; prefer pinning leaf SPKI; warn if imported cert is a CA; size-limit PEM/DER input; store pin metadata with the account.

---

### 12. Auth scheme detection is spoofable on a MITM / open network (before TLS is solid)

**Where:** `AuthSchemeDetector`  
Unauthenticated probe; if Digest is not seen, falls back to Basic. Under broken TLS / HTTP, an attacker can force Basic and capture credentials.

**Recommendation:** HTTPS-only + certificate validation first; never fall back to Basic silently when Digest was previously negotiated for that account.

---

### 13. Passwords retained in UI state after save

**Where:** `AddAccountUiState.password` / `AddAccountViewModel.save`  
On success, state keeps the password in a `StateFlow` until the VM is cleared. Process dumps / accessibility / debug tools can expose it longer than needed.

**Recommendation:** Clear password fields after successful save; avoid logging `WebDavCredentials` (data class `toString()` includes password).

---

### 14. Configuration import is weakly validated

**Where:** `BackupRestoreViewModel.importBackup`  
Arbitrary JSON can set `remoteFolderPath`, `localFolderUri`, sync flags, etc. Missing accounts are skipped (good), but malicious restore can re-point pairs to dangerous remote paths or invalid URIs. No schema version hard-fail, size limit, or path sanitization.

**Recommendation:** Validate version; sanitize paths; re-verify SAF permission for `localFolderUri`; refuse `..` / absolute remote escapes; cap file size.

---

### 15. Release builds not minified / obfuscated

**Where:** `app/build.gradle.kts` — `isMinifyEnabled = false`  
Easier reverse engineering of protocol, storage layout, and attack surface. Not a direct exploit, but weakens defense-in-depth for sideloaded APKs.

**Recommendation:** Enable R8 for release; keep ProGuard rules for Hilt/Room/OkHttp.

---

## Low / informational

### 16. Missing `INTERNET` permission (merged release manifest)

Merged `processReleaseManifest` lists FOREGROUND_SERVICE, POST_NOTIFICATIONS, BOOT_COMPLETED, battery optimization, `ACCESS_NETWORK_STATE`, `WAKE_LOCK` — **no `android.permission.INTERNET`**.  
Network WebDAV calls will fail at the OS level until this is added. Security impact today is “network surface off”; once added, findings 3–7 become fully live.

---

### 17. `EncryptedSharedPreferences` on alpha Jetpack Security

`androidx.security:security-crypto:1.1.0-alpha06` is alpha; library has a history of API deprecation and edge-case bugs. Still better than plaintext; plan a migration path (e.g. Keystore + encrypted file).

---

### 18. Room database is plaintext on disk

Expected for Room. Device compromise / unlocked backup of DB leaks account URLs and folder maps. Credentials remain separate (good).

---

### 19. Folder pair delete has no confirmation

`FoldersScreen` delete icon calls `viewModel.delete(pair)` immediately — accidental loss of sync configuration (not file content), inconsistent with account delete rigor.

---

### 20. Boot receiver is exported (expected)

`BootCompletedReceiver` is `exported="true"` but ignores non-`BOOT_COMPLETED` actions. Acceptable pattern; ensure it never starts sync without settings checks (it only reschedules — OK).

---

### 21. Diagnostic logging incomplete

`DiagnosticLog` notes nothing writes the log yet. When implemented, ensure secrets (Authorization, passwords, tokens) are redacted before share via FileProvider.

---

### 22. XML quota parsing

Custom `XmlPullParser` for RFC 4331 quota only. Android pull parsers generally do not expand external entities by default — low XXE risk; keep DOCDECL/external entity features disabled if you expand XML parsing.

---

### 23. Dependency / supply chain

Notable deps: OkHttp 4.12.0, sardine-android 0.8, okhttp-digest 2.7, Hilt, Room. No automated SCA run in this audit. Recommend Dependabot/OSV scanning before distribution.

---

### 24. Signing material on disk

`release.keystore` + `keystore.properties` exist locally and are ignored by git — good. Ensure they never enter commits, CI artifacts, or shared screenshots; use strong store passwords and consider hardware-backed signing for production.

---

## Threat model notes (app-specific)

| Threat | Current posture |
|--------|-----------------|
| Stolen APK reverse engineering | No minify; secrets not in binary if stored only on device. |
| Device backup / ADB extract | Credentials excluded; metadata largely not. |
| Malicious WebDAV server | **Weak** — path traversal / large downloads / deletes. |
| Network MITM | System TLS OK if HTTPS + system CAs; custom CA import weakens this by design. |
| Other apps on device | Standard Android sandbox; FileProvider grants are the main intentional share path. |
| User error | High for “delete account + data” and unconfirmed folder-pair delete. |

---

## Prioritized remediation roadmap

1. **Path sandbox:** Reject/normalize `..` and absolute remote paths; enforce resolved URL under account base + remote root.  
2. **Enforce limits:** Implement download size limits; stream uploads; optional global max file size.  
3. **Transport policy:** HTTPS-only (or explicit danger mode); constrain redirects.  
4. **Network settings fidelity:** Apply `wifiOnly` / mobile warning to manual and instant syncs.  
5. **Backup / provider least privilege:** Narrow FileProvider; reconsider `allowBackup` or exclude DB.  
6. **SAF lifecycle:** Release persistable URI permissions on delete.  
7. **Destructive actions:** Stronger confirmations; safer delete-data semantics.  
8. **Hygiene:** Add `INTERNET` when shipping network features; enable R8; pin dependency audit; clear password state.

---

## Conclusion

Architecturally, credential handling and “no trust-all TLS” are above average for a private WebDAV Android client. The main residual risk is not password storage, but **trusting remote path/content without a sandbox**, **user safety settings that are only partially enforced**, and **backup/share surfaces for non-secret but sensitive metadata**. Addressing the Critical and High items before wider sideloading is strongly recommended.

*This report is based on static analysis only; no dynamic penetration testing, dependency CVE database scan, or runtime TLS interception tests were performed.*

---

## Remediation status (2026-08-06)

Every finding below was re-verified against the current source (not just trusted from this report) before any fix was made. All code fixes were validated with a real `./gradlew assembleDebug assembleRelease testDebugUnitTest` run, not just static analysis.

| # | Finding | Status | Notes |
|---|---------|--------|-------|
| Critical 1 | Remote path traversal | ✅ Fixed | New `WebDavPathSafety.sanitize()` rejects `.`/`..`/empty segments at `SardineWebDavClient.resolve()`, the single chokepoint every WebDAV operation funnels through. |
| Critical 2 | "Delete data" can wipe the whole SAF root | ✅ Fixed | `LocalFileIo.deleteContents()` now deletes only the *contents* of the granted tree, never the tree-root document itself. Partial failures are surfaced to the user via a Snackbar instead of being swallowed. |
| High 3 | Download size limit unenforced | ✅ Fixed | `TransferExecutor.downloadFile()` now streams with a bounded copy that aborts (and deletes the partial file) once `downloadSizeLimitBytes` is exceeded. |
| High 4 | Full-file buffering on upload | ✅ Fixed | `SardineWebDavClient.upload()` now issues a raw streaming `PUT` via OkHttp instead of `content.readBytes()`. |
| High 5 | No HTTPS enforcement | ✅ Fixed | `WebDavConnectionRepository.addAccount()` rejects non-`https://` base URLs. |
| High 6 | No redirect policy | ✅ Fixed | `WebDavClientFactory.baseBuilder()` now sets `followRedirects(false)` / `followSslRedirects(false)`. |
| High 7 | Manual sync ignores Wi-Fi-only/charging constraints | ✅ Fixed | `SyncScheduler.enqueueImmediateSync()` now applies the same `buildConstraints(settings)` as the periodic schedule. |
| Medium 8 | `allowBackup=true` + DB/DataStore not excluded | ✅ Fixed | `backup_rules.xml` / `data_extraction_rules.xml` now also exclude `webdav_sync.db` (+ `-wal`/`-shm`) and `datastore/`. `allowBackup` left enabled (settings-only backup is a stated feature), but server URLs/topology/history are no longer included. |
| Medium 9 | FileProvider exposes entire `filesDir` | ✅ Fixed | `file_paths.xml` narrowed to a `diagnostics/` subfolder; `DiagnosticLog` now reads/writes there instead of `filesDir` root. |
| Medium 10 | SAF grants never released | ✅ Fixed | `SafFolderAccess.releaseAccess()` is now called from both folder-pair delete (`FoldersViewModel`) and account "also delete data" cascade (`AccountsViewModel`), guarded so a grant shared by another surviving folder pair isn't revoked. |
| Medium 11 | No cert pinning/fingerprint UX | ⏭️ Deferred | Larger UX feature (fingerprint display, pin-to-leaf instead of trust-CA); out of scope for this pass. Documented here for a future iteration. |
| Medium 12 | Silent Basic fallback | ⏭️ Mitigated, not eliminated | HTTPS is now mandatory (finding #5), which removes the main pre-TLS spoofing risk; the detector still falls back to Basic without a user-visible warning. Full fix (surface the detected scheme, let the user require Digest) deferred. |
| Medium 13 | Password retained in ViewModel state / logged via `toString()` | ✅ Fixed | `AddAccountViewModel` clears `password` from state after a successful save; `WebDavCredentials.toString()` now redacts the password. |
| Medium 14 | Weak backup-import validation | ✅ Fixed | `BackupRestoreViewModel.importBackup()` now caps the read size (5 MB), rejects unknown/future format versions, sanitizes `remoteFolderPath` via `WebDavPathSafety`, and requires an existing SAF grant for `localFolderUri` before accepting a folder pair. |
| Medium 15 | `isMinifyEnabled = false` | ✅ Fixed | R8 minification enabled for release; required excluding the `xpp3` transitive dependency of `sardine-android` (duplicated the platform's `org.xmlpull.v1.XmlPullParser`) and adding keep rules for `sardine-android`/`xmlpull`/`okhttp-digest`. Verified with a real `assembleRelease`. |
| Low 16 | Missing `INTERNET` permission | ✅ Fixed | Added to `AndroidManifest.xml` — this was also a functional blocker (no network calls could succeed at all without it). |
| Low 17 | Alpha Jetpack Security version | 📝 Documented only | No code change; tracked as a future dependency upgrade once a stable release exists. |
| Low 18 | Room DB stored in plaintext | 📝 Documented only | Expected/inherent (no secrets stored in Room per §4.6); no fix needed. |
| Low 19 | Folder-pair delete has no confirmation | ✅ Fixed | Added a confirmation `AlertDialog` in `FoldersScreen` (folder-pair delete never touched files anyway, but a silent single-tap delete of the sync config was still surprising). |
| Low 20 | Boot receiver exported but action-filtered | 📝 No fix needed | Already safe as-is per the audit's own assessment. |
| Low 21 | Diagnostic log has no redaction | 📝 Documented only | The diagnostic log feature itself isn't wired up to write anything yet (pre-existing, see Phase 8 notes); redaction will be part of implementing that feature. |
| Low 22 | XML XXE hardening | 📝 Documented only | Android's bundled XML pull parser doesn't expand external entities by default; no code change made. |
| Low 23 | Dependency/SCA scanning | 📝 Documented only | Out of scope for a direct code fix; recommend running a dependency-check tool periodically. |
| Low 24 | Signing material on disk | 📝 No fix needed | Already gitignored (verified in a prior session). |

**Also found and fixed while running the real test suite for the first time (not from this report):** `PathExclusion`'s glob-to-regex conversion never actually matched `*` wildcards (a `Regex.escape()` API misunderstanding), and `ConflictResolver`'s date formatting used the JVM default timezone instead of a fixed one, making conflicted-copy filenames non-deterministic across devices. Both fixed and covered by the existing `PathExclusionTest`/`ConflictResolverTest`, which now pass (they were never previously executed for real).

