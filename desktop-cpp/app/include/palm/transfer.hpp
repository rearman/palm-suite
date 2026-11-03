// Import and Export, as desktop-rs/app/src/transfer.rs and Transfer.kt: iCalendar files, the Date Book backup file and
// vCard files, each picked with the file chooser.
#pragma once

#include "palm/view.hpp"

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>

namespace palm::transfer {

// Exports the records of the view: the visible addresses as vCard in the Address Book, else the Date Book as iCalendar
// or as the backup file. Returns the status text, or no value when nothing was written. The Rust name is `export`,
// which C++20 reserves as a keyword.
std::optional<std::string> export_transfer(view::Ctx& ctx, bool addresses);

// Imports a file the user picks: an iCalendar file, a vCard file or a backup file, by its first line. Shows what was
// added.
void import(view::Ctx& ctx);

namespace detail {

// `path` with the extension `ext`, added when it has none of the formats of the dialog (ics, json, vcf). The
// extension follows Rust's Path::extension: a dotfile such as ".json" has none.
std::filesystem::path with_ext(const std::filesystem::path& path, std::string_view ext);

} // namespace detail

} // namespace palm::transfer
