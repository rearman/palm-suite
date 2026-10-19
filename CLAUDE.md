# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Layout

One jj repository (colocated with git, remote `rearman/palm-suite`) with one Gradle build of three modules, and the Go sync server. The Date Book and launcher histories came in by subtree merges from the archived repos `rearman/datebook` and `rearman/palm-launcher`. Read the `CLAUDE.md` of the app you change before you work in it.

| Folder           | Gradle project       | Contents                                                                    | Package                    | Indentation  |
| ---------------- | -------------------- | --------------------------------------------------------------------------- | -------------------------- | ------------ |
| `palm-ui/`       | `:palm-ui`           | Android library: the shared Palm OS look                                    | `com.rearman.palmui`       | tabs         |
| `datebook/`      | `:datebook:app`      | Date Book, To Do List and Memo Pad (three launcher icons, one APK)          | `com.rearman.datebook`     | 4 spaces     |
| `palm-launcher/` | `:palm-launcher:app` | Home-screen launcher after the Palm III Applications screen                 | `com.rearman.palmlauncher` | tabs         |
| `sync-server/`   | none (Go module)     | `palmsync`, the sync server for a VPS (protocol: `sync-server/PROTOCOL.md`) | `main`                     | tabs (gofmt) |

Run Gradle from the repository root. Keep the indentation style of the file you edit.

## Shared conventions

- `sync-server/` is a Go module with the standard library only, outside the Gradle build. The CI `server` job runs `gofmt`, `go vet` and `go test`. A tag `server-v*` attaches the linux amd64 and arm64 binaries to its release. This machine has no Go, so CI also verifies the server.
- All Android modules use the same stack: Kotlin, plain Android Views (no AndroidX, no Compose, no XML layouts), no dependencies other than `:palm-ui`, minSdk 26, compileSdk and targetSdk 34, JDK 17.
- The repository has no Gradle wrapper. Use Gradle 8.7, as CI does: `gradle assembleDebug`, `gradle testDebugUnitTest`, and `gradle :datebook:app:testDebugUnitTest --tests <class>` for one test class.
- This Windows machine has no Gradle on PATH and no Android SDK, so you cannot build or test locally. Do not try, and do not report it. Verify by code review. CI builds and tests each push. A report ends with the changes ready to commit and push.
- Each `app/debug.keystore` is committed on purpose and signs both build types, so a new build installs over the old one and keeps the user's data. Do not remove it or change the signing config.
- CI (`.github/workflows/build-apk.yml`) builds and tests both apps on each push and uploads `datebook.apk` and `palm-launcher.apk`. A tag `datebook-v*` or `launcher-v*` attaches that one app's APK to the GitHub Release of the tag.
- Code that both apps use belongs in `palm-ui`. Do not copy it into an app.
- User-facing text follows Palm OS wording.

## palm-ui

- `Ui.kt`: `Palm` (the colors, `Palm.text` in Palm px, `Palm.intoCutout`, the Palm OS font from the palm-ui assets through `Palm.typeface`), `pp()`/`ppf()` (the shorter screen side is 160 Palm px), the view builders (`label`, `hbox`, `vbox`, `box`, `palmButton`, `popupTrigger`, `checkBox`, `field`, `PushGroup`), `PalmDialog` (the base of all forms), `popupList`, `ask`, `palmAlert`, `IconView`, `symbols`, `Window.hideStatusBar()`, `TypingRoot` and `isTallScreen` (the keyboard stays open on a tall screen) and `ditherPaint()` (the Palm 50% gray).
- `Menu.kt`: the Palm III menu bar (`MenuBar`), `MenuItem`, `command()` and `commandKey()`.
- `ShortCuts.kt`: the Palm ShortCuts (`ShortCut`, `ShortCuts` with the defaults, `list`, `find`, `expand`, and the date and time hooks) and `EditText.palmShortCuts()`, which `field()` and `BackEdit` call. The launcher sets no list, so it has no ShortCuts.
- Each app sets `Palm.text` from its own text size option before it draws. The launcher sets `Palm.intoCutout`.
- Each window (activity, `PalmDialog`, `MenuBar`) calls `Window.hideStatusBar()`; a new window type must call it too.
