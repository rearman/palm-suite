# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Four Android apps after the Palm OS 3.5 Date Book, To Do List, Memo Pad and Address Book, in one APK. Each app has its own launcher icon and task. The Date Book and To Do List apps use the same records. All four apps use the one `Store` database. Kotlin, plain Android Views (no AndroidX, no Compose, no XML layouts), no dependencies other than the repository modules `:palm-ui` and `:sync-core`. minSdk 26, compileSdk and targetSdk 34, JDK 17. Version control is jj.

## Build

The repository has no Gradle wrapper. CI (`.github/workflows/build-apk.yml`) uses Gradle 8.7 on JDK 17:

```sh
gradle assembleDebug --stacktrace
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. CI runs on each push to `main` or `master`, on each push of a `v*` tag, and on manual dispatch. CI uploads the APK unzipped (`upload-artifact` with `archive: false`), so the artifact is the file itself. A workflow step first renames it to `datebook.apk`, or to `datebook-<tag>.apk` for a tag build, which is the artifact name. That mode accepts only one file, so keep the path exact. A tag build also attaches the APK to the GitHub Release of that tag, and creates the release when it does not exist.

JVM unit tests (JUnit 4) are in `app/src/test/`. The tests of the model, iCalendar, vCard and sync logic are in `sync-core/src/test/`. CI runs them after the build:

```sh
gradle testDebugUnitTest
gradle :sync-core:test --tests com.rearman.datebook.FreeTimesTest
```

The repository has no lint configuration.

`app/debug.keystore` is committed on purpose. Both build types sign with it, so a new build installs over the old one and keeps the user's data. Do not remove it or change the signing config.

## Architecture

All app source is in `app/src/main/java/com/rearman/datebook/`, except the model, iCalendar and sync logic, which are in the `sync-core` module (see **Shared logic** below).

- **One activity class, four apps.** `TodoActivity` is a subclass of `MainActivity` that only sets `isTodoApp = true`. `MemoActivity` is a subclass of `MainActivity` that only sets `isMemoApp = true`. `AddressActivity` is a subclass of `MainActivity` that only sets `isAddressApp = true`. The manifest gives each one its own `taskAffinity`, icon and `singleTask` launch mode. `MainActivity` holds all screens. A `mode` field (`DAY`, `WEEK`, `MONTH`, `AGENDA`, `TODO`, `MEMO`, `ADDRESS`) selects one. Cross-app jumps (`openTodoApp`, `openDateBook`, `openMemoApp`, `openAddressApp`) send intents with `EXTRA_*` extras (`EXTRA_MEMO` for Memo Pad, `EXTRA_ADDRESS` for Address Book), which `handleIntent` reads.
- **Full rebuild on each change.** `render()` removes all views and calls the `build*` function for the current mode. The UI keeps no state between renders other than the fields of `MainActivity`. After a record change, call `changed()` (it reschedules alarms, then renders) or `render()`.
- **Data** (`Store.kt`): `Store` is a singleton `SQLiteOpenHelper` on `datebook.db` with tables `events`, `todos`, `categories`, `memos` and `addresses`. It loads each table one time into an in-memory `MutableList`. `save`/`delete` write through to SQLite and update the list. `exportJson`/`importJson` give the backup format, with memos and addresses included. The DB version is 4. `onUpgrade` version 2 adds the `memos` table and the per-app `categories` column (the Memo Pad categories). Version 3 adds the sync columns to all tables: `uid` (the same on every device; the default categories have fixed uids such as `default-0-business`), `rev` (last server revision, 0 = never synced), `dirty` (set by every write) and `deleted`. `delete` removes a row with `rev` 0 and makes any other row a tombstone with its text cleared. The `load*` queries skip tombstones. The models carry only `uid`; `dupKey` ignores it. Version 4 adds the `addresses` table and the Address Book categories (`categories` rows with app 2). Addresses and the app 2 categories sync like memos (kind "address"), and `dirtyRows` includes them. A later schema change needs a version increase and a migration. `Prefs` wraps the `prefs` SharedPreferences file.
- **Model** (`Model.kt`, in `sync-core`, same package): dates are epoch days (`Long`). Times are minutes after midnight (`Int`). `-1` means "no date". `Event` stores one first occurrence and repeat rules. `Recur.occursOn` expands them, and `Occ` is one event on one day. Exceptions (`exceptions` set of epoch days) give "Current" edits and deletes of a repeating event. `saveEventEdit` and `deleteEvent` in `MainActivity` split an event for Current, Future and All.
- **Alarms** (`Alarms.kt`): there is only one system alarm, set for the next reminder of all events. `Alarms.scheduleNext` finds it. `AlarmReceiver` shows the notifications and handles remind, snooze and OK actions. `BootReceiver` reschedules after boot, time change, time zone change and package update.
- **UI kit:** the shared `palm-ui` module (see the root `CLAUDE.md`) holds the Palm colors, font, Palm px, view builders, `PalmDialog`, `popupList` and the menu bar. Each Date Book file imports `com.rearman.palmui.*`. `MainActivity.render()` sets `Palm.text` from the Font option. Use these builders for new UI. Use `dp()` only for gesture distances.
- **ShortCuts:** `palm-ui` `ShortCuts.kt` holds the list logic. `Prefs.shortCuts` saves the list. `MainActivity.onCreate` sets `ShortCuts.list`, `date` and `time`. The ShortCut forms are `showShortCuts` in `Details.kt`.
- **iCalendar** (`ICal.kt`, in `sync-core`, same package): `ICal.export` writes events with floating local times. `ICal.parse` reads a `.ics` file into events, splits a timed event that goes past midnight into one event for each day, and counts the file events it had to simplify. `Store.addEvents` adds them less duplicates (`dupKey`). The import in `MainActivity.onActivityResult` reads a file that starts with `BEGIN:VCALENDAR` as iCalendar and any other file as a backup.
- **Shared logic** (`sync-core` module, package `com.rearman.datebook`, with its tests in `sync-core/src/test/java/com/rearman/datebook/`): `Model.kt`, `ICal.kt`, `VCard.kt` (vCard 3.0 read and write for the Address Book), `SyncCodec.kt` (record JSON), `SyncLogic.kt` (`SyncLogic.decide`, the keep-both table) and the sync round `SyncRunner` with the `SyncStore` interface. The palmsync client is `SyncCrypto` (PBKDF2 and AES-GCM) and `SyncClient` (HTTP), in package `com.rearman.palmsync`.
- **Sync** (`SyncEngine.kt`): the Android part of sync. It holds the threads, `KeyVault` (Keystore-wrapped key and token), `Prefs` and the jobs, and it adapts `Store` to `SyncStore`. `SyncJob` (JobScheduler: hourly, and 30 s after `Store.onWrite`) and `SyncForm.kt` (Options > Sync...) are part of it too. Network work runs on a worker thread; Store work runs on the main thread. `Store` sync functions: `dirtyRows`, `syncRow`, `markSynced`, `putRemote`, `dropRemote`, `resetSync`.
- `Grids.kt`: custom-drawn `WeekGrid` and `MonthGrid` views, and `lanes()` for overlapping time bars. `Details.kt`: the event and to-do details dialogs, and the Options dialogs (as `MainActivity` extension functions). `Pickers.kt`: date, time, repeat and note dialogs. `Memo.kt`: the Memo Pad screens (extension functions of `MainActivity`).
- Address Book: `AddressList.kt` (the list and Look Up), `AddressView.kt`, `AddressEdit.kt`, `AddressDialogs.kt` (the dialogs and Phone Lookup) and `AddressShare.kt` (share, vCard import and export, `VcfProvider`, a minimal FileProvider because there is no AndroidX). These are extension functions of `MainActivity`.

## Conventions

- User-facing text and documentation follow Palm OS wording (menu names such as Record, Edit, Options; "Purge", "Security", "Show Options").
- `README.md` is user documentation, written in Simplified Technical English. Keep it current when a feature changes.
