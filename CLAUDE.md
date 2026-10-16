# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Layout

This folder holds two independent Android projects and has no version control of its own. Each project has its own jj repository (colocated with git), CI and `CLAUDE.md`. Read the `CLAUDE.md` of the project you change before you work in it.

| Folder           | App                                                                 | Package                    | Indentation |
| ---------------- | ------------------------------------------------------------------- | -------------------------- | ----------- |
| `datebook/`      | Date Book, To Do List and Memo Pad (three launcher icons, one APK)  | `com.rearman.datebook`     | 4 spaces    |
| `palm-launcher/` | Home-screen launcher after the Palm III Applications screen         | `com.rearman.palmlauncher` | tabs        |

Run each command from inside its project folder. Keep the indentation style of the file you edit.

## Shared conventions

- Both projects use the same stack: Kotlin, plain Android Views (no AndroidX, no Compose, no XML layouts), no dependencies, minSdk 26, compileSdk and targetSdk 34, JDK 17.
- Neither project has a Gradle wrapper. Use Gradle 8.7, as CI does: `gradle assembleDebug`, `gradle testDebugUnitTest`, and `gradle testDebugUnitTest --tests <class>` for one test class.
- This Windows machine has no Gradle on PATH and no Android SDK, so you cannot build or test locally. Do not try, and do not report it. Verify by code review. CI builds and tests each push. A report ends with the changes ready to commit and push.
- Each `app/debug.keystore` is committed on purpose and signs both build types, so a new build installs over the old one and keeps the user's data. Do not remove it or change the signing config.
- The CI workflows (`.github/workflows/build-apk.yml`) are copies of each other. Only the APK name changes. Apply a CI change to both.
- The two apps share no code. The launcher copies only the Green LCD paper color from Date Book (`palm-launcher/.../MainActivity.kt`). If you change that color in one app, change it in the other.
- User-facing text follows Palm OS wording.
