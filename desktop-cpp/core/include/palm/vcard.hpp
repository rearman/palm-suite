// vCard files of Address Book records, as desktop-rs/core/src/vcard.rs. Import reads vCard 2.1, 3.0 and 4.0. Export
// writes vCard 3.0. The id, the sync uid and the category id stay local; a category travels as its name.
#pragma once

#include "palm/model.hpp"

#include <cstdint>
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace palm::vcard {

// One card: its address, and the name of its first category (nullopt when it has none).
struct Parsed {
	model::Address address;
	std::optional<std::string> category_name;

	bool operator==(const Parsed&) const = default;
};

// Reads every BEGIN:VCARD to END:VCARD card of `text`. A card with no usable field is skipped. The phones fill the
// five phone slots in file order; phones past five and later ADR fields go to the note.
std::vector<Parsed> parse(std::string_view text);

// Writes `list` as vCard 3.0 with CRLF line ends. `category_name` gives the name of a category id; category 0
// (Unfiled) writes no CATEGORIES line. The Rust name is `export`, which C++20 reserves as a keyword.
std::string export_vcard(std::span<const model::Address> list,
                         std::function<std::optional<std::string>(std::int32_t)> category_name);

} // namespace palm::vcard
