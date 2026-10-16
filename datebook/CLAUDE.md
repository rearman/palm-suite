# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Three Android apps after the Palm OS 3.5 Date Book, To Do List and Memo Pad, in one APK. Each app has its own launcher icon and task. The Date Book and To Do List apps use the same records. All three apps use the one `Store` database. Kotlin, plain Android Views (no AndroidX, no Compose, no XML layouts), no dependencies. minSdk 26, compileSdk and targetSdk 34, JDK 17. Version control is jj.

## Build

The repository has no Gradle wrapper. CI (`.github/workflows/build-apk.yml`) uses Gradle 8.7 on JDK 17:

```sh
gradle assembleDebug --stacktrace
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. CI runs on each push to `main` or `master`, on each push of a `v*` tag, and on manual dispatch. CI uploads the APK unzipped (`upload-artifact` with `archive: false`), so the artifact is the file itself. A workflow step first renames it to `datebook.apk`, or to `datebook-<tag>.apk` for a tag build, which is the artifact name. That mode accepts only one file, so keep the path exact. A tag build also attaches the APK to the GitHub Release of that tag, and creates the release when it does not exist.

JVM unit tests (JUnit 4) are in `app/src/test/`. CI runs them after the build:

```sh
gradle testDebugUnitTest
gradle testDebugUnitTest --tests com.rearman.datebook.FreeTimesTest
```

The repository has no lint configuration.

`app/debug.keystore` is committed on purpose. Both build types sign with it, so a new build installs over the old one and keeps the user's data. Do not remove it or change the signing config.

## Architecture

All source is in `app/src/main/java/com/rearman/datebook/`.

- **One activity class, three apps.** `TodoActivity` is a subclass of `MainActivity` that only sets `isTodoApp = true`. `MemoActivity` is a subclass of `MainActivity` that only sets `isMemoApp = true`. The manifest gives each one its own `taskAffinity`, icon and `singleTask` launch mode. `MainActivity` holds all screens. A `mode` field (`DAY`, `WEEK`, `MONTH`, `AGENDA`, `TODO`, `MEMO`) selects one. Cross-app jumps (`openTodoApp`, `openDateBook`, `openMemoApp`) send intents with `EXTRA_*` extras (`EXTRA_MEMO` for Memo Pad), which `handleIntent` reads.
- **Full rebuild on each change.** `render()` removes all views and calls the `build*` function for the current mode. The UI keeps no state between renders other than the fields of `MainActivity`. After a record change, call `changed()` (it reschedules alarms, then renders) or `render()`.
- **Data** (`Store.kt`): `Store` is a singleton `SQLiteOpenHelper` on `datebook.db` with tables `events`, `todos`, `categories` and `memos`. It loads each table one time into an in-memory `MutableList`. `save`/`delete` write through to SQLite and update the list. `exportJson`/`importJson` give the backup format, with memos included. The DB version is 2. `onUpgrade` adds the `memos` table and the per-app `categories` column (the Memo Pad categories). A later schema change needs a version increase and a migration. `Prefs` wraps the `prefs` SharedPreferences file.
- **Model** (`Model.kt`): dates are epoch days (`Long`). Times are minutes after midnight (`Int`). `-1` means "no date". `Event` stores one first occurrence and repeat rules. `Recur.occursOn` expands them, and `Occ` is one event on one day. Exceptions (`exceptions` set of epoch days) give "Current" edits and deletes of a repeating event. `saveEventEdit` and `deleteEvent` in `MainActivity` split an event for Current, Future and All.
- **Alarms** (`Alarms.kt`): there is only one system alarm, set for the next reminder of all events. `Alarms.scheduleNext` finds it. `AlarmReceiver` shows the notifications and handles remind, snooze and OK actions. `BootReceiver` reschedules after boot, time change, time zone change and package update.
- **UI kit:** the shared `palm-ui` module (see the root `CLAUDE.md`) holds the Palm colors, font, Palm px, view builders, `PalmDialog`, `popupList` and the menu bar. Each Date Book file imports `com.rearman.palmui.*`. `MainActivity.render()` sets `Palm.text` from the Font option. Use these builders for new UI. Use `dp()` only for gesture distances.
- **ShortCuts:** `palm-ui` `ShortCuts.kt` holds the list logic. `Prefs.shortCuts` saves the list. `MainActivity.onCreate` sets `ShortCuts.list`, `date` and `time`. The ShortCut forms are `showShortCuts` in `Details.kt`.
- `Grids.kt`: custom-drawn `WeekGrid` and `MonthGrid` views, and `lanes()` for overlapping time bars. `Details.kt`: the event and to-do details dialogs, and the Options dialogs (as `MainActivity` extension functions). `Pickers.kt`: date, time, repeat and note dialogs. `Memo.kt`: the Memo Pad screens (extension functions of `MainActivity`).

## Conventions

- User-facing text and documentation follow Palm OS wording (menu names such as Record, Edit, Options; "Purge", "Security", "Show Options").
- `README.md` is user documentation, written in Simplified Technical English. Keep it current when a feature changes.
