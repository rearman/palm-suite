# The Rust port of Palm Desktop

The port moves the desktop app in `desktop/` (Kotlin and Swing) to Rust and fltk-rs in `desktop-rs/`, for sharp drawing at 125% and 150% display scale, native Wayland windows, the text rendering of the system, a fast start and a small install. The Swing app stays the shipped app until the cutover. Both apps read and write the same `desktop-data.json` and `desktop.properties`, so a user can switch between them; do not run both at once.

## Done

| Phase | Work                                                                                                | Commits          |
| ----- | --------------------------------------------------------------------------------------------------- | ---------------- |
| 0     | Scaling spike (`spike/`): device-pixel drawing through `override_scale`, checked on Windows and X11 | 513b44f, d6a7bc5 |
| 1     | Test vectors in `sync-core/testdata`, written and checked by `VectorsTest`                          | 43c9f44, cb64571 |
| 2     | `core/`: the port of sync-core, tested against the vectors and a real palmsync                      | 66d8c08          |
| 3     | `app/`: the store and the settings, checked against real files and the Swing app                    | f195623          |
| 4     | The shell: window, menus, toolbar, launcher, status bar, classic edges, sync and Sync Options       | 7c681c9          |
| 5     | The Memo Pad and the shared parts: split, category drop-downs and dialog, record clipboard, menus   | d0cce03          |
| 6     | The Address Book: list, card, Look up, Edit Address, Custom Field Labels, Dial                      | 69ee55c          |
| 7     | The To Do List: table, details, Show Options, Purge; the date dialog and the Note Editor            | 7fe0117          |
| 8     | The Date Book: Day, Week and Month views, right pane, tabs, Edit Event, Change Repeat, Select Time  | 4fa7165          |
| 9     | Find, Import and Export (iCalendar, vCard, the backup file), Print and Options                      | 07e9a93          |

## Still to do

| Phase | Work                                                                                                                                                              | Kotlin source | Size (Rust lines)       |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- | ----------------------- |
| 10    | Packaging (the MSI, the Linux tar.gz files, the Gentoo ebuild), a beta period and the cutover; Page Setup has no FLTK dialog, and the print dialog sets the paper | none          | c. 200 (WiX source, CI) |

The view phases followed the Memo Pad: build the view in its group of the main window, send every widget event as a `Msg`, reuse `split.rs`, `category.rs`, `records.rs`, `text_edit.rs` and `dialogs.rs`, draw custom views through `draw::Dev`, and check each view at 100%, 125% and 150%.

## Packaging and the cutover

- Windows: an MSI from a hand-written WiX 3 source, with the upgrade code of the Swing MSI (`9703d1fd-0a96-45b3-8842-4b8bbcad48dc`), per-user install and the shortcut prompt. The first Rust version must be above the last Swing version (0.23) so the MSI upgrades the Swing install in place.
- Linux: `palm-desktop-linux-x86_64.tar.gz` and `palm-desktop-linux-arm64.tar.gz` with the binary and the icon. The binary links the system Cairo, Pango, Wayland, libxkbcommon, X11 and fontconfig, and falls back to X11 when no Wayland session runs.
- Gentoo: the ebuild drops `java-pkg-2` and the JRE, installs the binary, and lists those libraries in `RDEPEND`. `bump.sh` changes to the new file names.
- The icons move from `desktop/src/main/resources` into `desktop-rs/app` before `desktop/` goes.
- At the cutover, `desktop-v*` tags ship the Rust app, the Swing job and `desktop/` go, the spike goes, and the CLAUDE.md files, the README and the Gentoo notes change in the same commit. The version and the date of the cutover are open.

## Risks and guards

| Risk                                                     | Guard                                                                                            |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| The Rust and Kotlin sync code drift apart                | The vectors and the server test run on each CI build; a codec change must pass in both languages |
| 1 px lines land unevenly at fractional scales            | Custom drawing goes through `draw::Dev`; the classic edges are custom box types                  |
| The Swing and Rust apps overwrite each other's data file | The Rust app takes `desktop.lock`; the Swing app takes none, so never run both                   |
| A view fault reaches a user before anyone sees it        | Screenshots at three scales for each phase; the user tests the CI binaries before the next phase |
| The 3.1.1 bitmaps blur at fractional scales              | Nearest-neighbor scaling now; whole-factor scaling in a later phase                              |
| No screen-reader support in FLTK                         | Accepted for this app                                                                            |

## Method notes

- Build in a short work copy (`~/.cache/ps/desktop-rs`): a long path breaks the FLTK build on the Windows 260-character limit. Copy the files into the repository for the commit.
- This machine builds Rust with the GNU host; `~/.cargo/config.toml` names the MSYS2 compilers and Ninja. CI builds the MSVC release.
- Test the app with `APPDATA` (Windows) or `XDG_CONFIG_HOME` (Linux) pointing at a folder of copies, never at the real files while a change is in progress. `palm-desktop --shot out.ppm` saves an offscreen capture at `FLTK_SCALING_FACTOR`.
- Never send simulated keys to the desktop: they reach whatever window is in front.
- Real data stays out of the repository. `real_files_round_trip` checks copies when `PALM_REAL_DATA` names their folder.
