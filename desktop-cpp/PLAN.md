# The C++ port of Palm Desktop

The port moves the Rust desktop app in `desktop-rs/` to C++23 and FLTK in `desktop-cpp/`. It follows the phases of `desktop-rs/PLAN.md`, and each module ports its Rust file line by line. `desktop-rs/` and its CI job stay until the C++ app reaches parity; the Swing app in `desktop/` stays the shipped app until the cutover.

## Done

| Phase | Work                                                                                                                                                          |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| core  | `core/`: the port of `desktop-rs/core` (the port of sync-core), one static library per module, tested against the vectors of `sync-core/testdata` and a real palmsync |

## Still to do

| Phase | Work                                                                                                       | Rust source                                                    |
| ----- | ---------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| 3     | The store and the settings (`desktop-data.json`, `desktop.properties`, the lock file)                      | `app/src/store.rs`, `settings.rs`                              |
| 4     | The shell: window, menus, toolbar, launcher, status bar, classic edges in device pixels, sync              | `main.rs`, `window.rs`, `draw.rs`, `sync.rs`, `sync_dialog.rs` |
| 5     | The Memo Pad and the shared parts: split, categories, record clipboard, text-field menu, dialogs, mnemonics | `memo.rs`, `split.rs`, `category.rs`, `records.rs`, &c         |
| 6     | The Address Book                                                                                           | `address.rs`, `address_dialog.rs`                              |
| 7     | The To Do List, the date dialog, and the Note Editor                                                       | `todo.rs`, `date_picker.rs`, `note_dialog.rs`                  |
| 8     | The Date Book: Day, Week, and Month views, Edit Event                                                       | `date.rs`, `week.rs`, `month.rs`, `event_dialog.rs`, &c        |
| 9     | Find, Import and Export, Print, and Options                                                                | `find.rs`, `transfer.rs`, `printing.rs`, `options.rs`          |
| 10    | Packaging and the cutover, as in `desktop-rs/PLAN.md`                                                      | none                                                           |

## Build

- CMake 3.25 or newer, Ninja, and g++ 14 (C++23): `cmake -S . -B build/all -G Ninja`, `cmake --build build/all`, `ctest --test-dir build/all`. Warnings are errors.
- Libraries: OpenSSL (AES-GCM, PBKDF2, SHA-256, random), zlib (raw deflate), libcurl (palmsync), nlohmann/json, and doctest. This Windows machine takes them from MSYS2 mingw64 (`C:/tools/msys64/mingw64/bin` first on PATH); CI uses MSYS2 MINGW64 on Windows and g++-14 on ubuntu-24.04.
- `CMakeLists.txt` lists the modules in dependency order with the libraries each links. Each `core/tests/test_<name>.cpp` is its own test program; its first line names the modules it links (`// links: model codec`).
- `palm/text.hpp` holds the helpers for what Rust's `str` and `char` have built in (UTF-8 code points, White_Space, `trim`, `split`, case mapping, `from_utf8_lossy`). A module uses them and keeps no copy of its own.
- `clang-format` 20 with `.clang-format` (tabs, 120 columns); the CI job checks it on linux-amd64.

## Port rules

- Names stay as in Rust (snake_case functions, PascalCase types); a name that is a C++ keyword takes the module name (`export_ical`, `export_vcard`, `SyncClient::new_client`).
- `Result<T, String>` is `std::expected<T, std::string>`, `Option` is `std::optional`, a Rust enum with data is a `std::variant`, a trait is an abstract class, and a Rust panic is a thrown `std::runtime_error`.
- Rust's `div_euclid`, `rem_euclid`, and `%` on negatives differ from C++; Rust iterates `char`s where C++ sees bytes. Each port checks both.
- Case mapping covers ASCII, Latin-1 Supplement, and Latin Extended-A, which is the known limit; the upgrade path is ICU.
- Time zones use C++20 `<chrono>` (`locate_zone`). Under MinGW, `current_zone()` gives `Etc/UTC`, so phase 3 needs the Windows zone (GetDynamicTimeZoneInformation and the CLDR Windows-to-IANA table).
- The client follows no redirect, and on Windows it takes the system certificate store (`CURLSSLOPT_NATIVE_CA`).
- Date arithmetic on stored values wraps (`days::wrapping_add` &c), as the Rust release build and Kotlin's `Long` do; signed overflow is undefined in C++.
- Known limits: a float read as text prints as nlohmann does ("1e+20"), where Rust prints "1e20" and Kotlin "1.0E20". libstdc++ 14.2 under MinGW misreads negative LMT offsets, so a TZID time before a zone's standard time (c. 1883 in the U.S.) lands off by up to two hours; seen on Windows; Linux, whose libstdc++ reads the same tzdata.zi text form, is unchecked.
