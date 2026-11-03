# The C++ port of Palm Desktop

The port moves the Rust desktop app in `desktop-rs/` to C++23 and FLTK in `desktop-cpp/`. It follows the phases of `desktop-rs/PLAN.md`, and each module ports its Rust file line by line. `desktop-rs/` and its CI job stay until the C++ app reaches parity; the Swing app in `desktop/` stays the shipped app until the cutover.

## Done

| Phase | Work                                                                                                                                                                                                                                                                                 |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| core  | `core/`: the port of `desktop-rs/core` (the port of sync-core), one static library per module, tested against the vectors of `sync-core/testdata` and a real palmsync                                                                                                                |
| 3     | `app/`: the store (`desktop-data.json`, the lock file), the settings (`desktop.properties`), and `platform` (today, the 24-hour setting, the local zone with the CLDR Windows table, OS error texts); checked against the fixture of `desktop-rs/app/tests` and copies of real files |

## Still to do

| Phase | Work                                                                                                        | Rust source                                                    |
| ----- | ----------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| 4     | The shell: window, menus, toolbar, launcher, status bar, classic edges in device pixels, sync               | `main.rs`, `window.rs`, `draw.rs`, `sync.rs`, `sync_dialog.rs` |
| 5     | The Memo Pad and the shared parts: split, categories, record clipboard, text-field menu, dialogs, mnemonics | `memo.rs`, `split.rs`, `category.rs`, `records.rs`, &c         |
| 6     | The Address Book                                                                                            | `address.rs`, `address_dialog.rs`                              |
| 7     | The To Do List, the date dialog, and the Note Editor                                                        | `todo.rs`, `date_picker.rs`, `note_dialog.rs`                  |
| 8     | The Date Book: Day, Week, and Month views, Edit Event                                                       | `date.rs`, `week.rs`, `month.rs`, `event_dialog.rs`, &c        |
| 9     | Find, Import and Export, Print, and Options                                                                 | `find.rs`, `transfer.rs`, `printing.rs`, `options.rs`          |
| 10    | Packaging and the cutover, as in `desktop-rs/PLAN.md`                                                       | none                                                           |

## Build

- CMake 3.25 or newer, Ninja, and g++ 14 (C++23): `cmake -S . -B build/all -G Ninja`, `cmake --build build/all`, `ctest --test-dir build/all`. Warnings are errors.
- Libraries: OpenSSL (AES-GCM, PBKDF2, SHA-256, random), zlib (raw deflate), libcurl (palmsync), nlohmann/json, and doctest. This Windows machine takes them from MSYS2 mingw64 (`C:/tools/msys64/mingw64/bin` first on PATH); CI uses MSYS2 MINGW64 on Windows and g++-14 on ubuntu-24.04.
- `CMakeLists.txt` lists the core and app modules in dependency order with the libraries each links. Each `core/tests/test_<name>.cpp` and `app/tests/test_<name>.cpp` is its own test program; its first line names the modules it links (`// links: model codec`), read at configure time.
- `palm/text.hpp` holds the helpers for what Rust's `str` and `char` have built in (UTF-8 code points, White_Space, `trim`, `split`, case mapping, `from_utf8_lossy`). A module uses them and keeps no copy of its own.
- `clang-format` 20 with `.clang-format` (tabs, 120 columns); the CI job checks `core/` and `app/` on linux-amd64.

## Port rules

- Names stay as in Rust (snake_case functions, PascalCase types); a name that is a C++ keyword takes the module name (`export_ical`, `export_vcard`, `SyncClient::new_client`).
- `Result<T, String>` is `std::expected<T, std::string>`, `Option` is `std::optional`, a Rust enum with data is a `std::variant`, a trait is an abstract class, and a Rust panic is a thrown `std::runtime_error`.
- Rust's `div_euclid`, `rem_euclid`, and `%` on negatives differ from C++; Rust iterates `char`s where C++ sees bytes. Each port checks both.
- Case mapping covers ASCII, Latin-1 Supplement, and Latin Extended-A, which is the known limit; the upgrade path is ICU.
- Time zones use C++20 `<chrono>` (`locate_zone`). `platform::local_zone()` gives the user's zone: `TZ` as jiff reads it, else on Windows `GetDynamicTimeZoneInformation` mapped through the CLDR table in `platform.cpp` (generated from jiff 0.2.38's copy; regenerate it from a newer jiff), else `current_zone()`. Under MinGW `current_zone()` gives `Etc/UTC`, so never call it directly.
- Test the store with copies: `PALM_REAL_DATA=<folder> build/all/test_store.exe` reads `desktop-data.json` (and `desktop.properties` when present) from that folder and writes only to a temp folder. The fixture is read from `desktop-rs/app/tests/fixtures`; move it into `desktop-cpp/app/tests` before `desktop-rs/` goes.
- `json::dump` writes every JSON text; it replaces invalid UTF-8 with U+FFFD, so a save never throws. OS errors read as Rust shows them, through `platform::os_error_text`.
- The client follows no redirect, and on Windows it takes the system certificate store (`CURLSSLOPT_NATIVE_CA`).
- Date arithmetic on stored values wraps (`days::wrapping_add` &c), as the Rust release build and Kotlin's `Long` do; signed overflow is undefined in C++.
- Known limits: a float read as text prints as nlohmann does ("1e+20"), where Rust prints "1e20" and Kotlin "1.0E20". libstdc++ 14.2 under MinGW misreads negative LMT offsets, so a TZID time before a zone's standard time (c. 1883 in the U.S.) lands off by up to two hours; seen on Windows; Linux, whose libstdc++ reads the same tzdata.zi text form, is unchecked. A POSIX rule in `TZ` ("EST5EDT,M3.2.0,M11.1.0") falls back to UTC, where jiff applies it. Find and the sorts fold case only through Latin Extended-A. A data file with a UTF-8 byte-order mark opens, where Rust refuses it.
