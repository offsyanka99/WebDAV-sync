Generic WebDAV-sync requirements
---
# Application description
Android application that will sits in the background and monitor changes in the folders.
As soon as a file or folder is modified or a file or folder is added or deleted in the folder, synchronization occurs after a short delay (which is set in the configuration).

# Application Structure
The main window contains Main section and Footer
There are three icons in the footer:
- Overview
- Folders
- Settings

## Overview description
- In the first row on the left is the heading: WebDAV-Sync
- In the first row on the right is a “+” icon (add sync)
- In the last row on the right is a button with an icon (sync) and the text “Sync”

Main Overview tab is a placeholder for sections with:
* Sync status
  - Last sync : {date and time}
  - Duration  : {number of seconds or minutes and seconds}
  - Status    : {status: Ready (or something else)}
* Recent changes
  - Upload            : {number of files/folders}
  - Download          : {number of files/folders}
  - Deleted in device : {number of files/folders}
  - Deleted in cloud  : {number of files/folders}
* Cloud Storage
  - WebDav (centered in the section)
  - {username} (centered in the section)
  - {URL}
  - Storage available : {number in GB/MB ({in %})}
  - Storage quota     : {storage quota GB/MB}

### “Sync” button
When use hit the Sync button, the application trigger syncronization of configured folders.

## Folders description
In the first row on the left is the heading: Folders
In the last row on the right is a button with an icon (+) and the text “Add folder”
Main Folder tab is a placeholder for sections with configured folders. Each configured folder section have:
- Cloud folder    : {foldername}
- Internal folder : {foldername}
- Sync method     : {synctype}
- Enable sync {toggle}

When user click on “Add folder” button, the new window opens.

### Add folder window
In the first row on the left is the heading: Folder pair
In the first row on the right is the link/button: Save
Below, the user must fill out the following fields:
- Folder name
- Remote (WebDav) folder
- Local folder
- Sync method dropdown with the following options (first selected by default)
  -- Two-way
  -- To the Device
  -- To the Cloud
- {checkbox}: Exclude hidden files (on by default)
- {checkbox}: Exclude folders (off by default)
- {checkbox}: Delete empty folders (off by default)
- {checkbox}: Instant upload (off by default)
- {toggle}: Folder pair enabled (on by default)

Description for Sync methods:
Two-way: Syncronizes changes in the both Device and Cloud folder bidirectionally, allowing files and folders to be updated in either location while keeping both locations identical and up-to-date with latest changes
To the Device: Changes made in the Cloud folder are mirrored or replicated to the Device
To the Cloud: Changes made in the Device folder are mirrored or replicated to the Cloud

## Settings description
In the first row on the left is the heading: Settings
Available menus:
- Syncronization
- Settings
- About

### Syncronization menu
- Upload file size limit        : no limit (by default)
- Download file size limit      : no limit (by default)
- Warn if sync on mobile network: {checkbox} (off by default)
- Sync over WIFI only           : {checkbox} (off by default)
- Allow parallel upload/download: {checkbox} (on by default)
- Enable Auto sync in background: {checkbox} (on by default)
  if enabled then show:
  -- Interval: {minutes}
  -- Immediately on local changes: {checkbox} (on by default)
  -- Only while device is charging: {checkbox} (off by default)

After sync errors
- Retry attempts: - {number} +
- Wait between attempts: - {minutes} +

### Settings

- Battery optimization: {toggle}
- Manage app if unused: {toggle}
- Auto-start after reboot: {toggle}
- Diagnostic log: {toggle}
- {Button/link}: Share log via... (enabled only if log exists)

Backup/Restore section
- Download configuration button
- Restore configuration button

Description for Backup/Restore:
When user click on Download configuration button the user is prompted to choose where to save the configuration file.
When user click on Restore configuration button the user is prompted to select the configuration file and then restore all application settings to match the settings in the file.

### About

All below is centered on the window/modal
- Logo (picture)
- WebDAV-sync
- Version {application version}
- {email}