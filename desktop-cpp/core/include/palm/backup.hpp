// The Date Book backup file (JSON), as Backup.kt. Android and the desktop apps write and read it.
//
// Top-level keys: "format", "categories" (To Do names), "memoCategories", "addressCategories",
// "events", "todos", "memos" and "addresses". A record names its category, never its id.
#pragma once

#include "palm/model.hpp"

#include <cstdint>
#include <expected>
#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace palm::backup {

// The format name that the file carries in its "format" key.
inline constexpr std::string_view FORMAT = "datebook-backup-1";

// The records of one backup file, and the category names of each app, including empty categories.
// Records read from a file have id 0 and uid "".
struct Content {
	std::vector<model::Event> events;
	std::vector<model::Todo> todos;
	std::vector<model::Memo> memos;
	std::vector<model::Address> addresses;
	std::vector<std::string> todo_categories;
	std::vector<std::string> memo_categories;
	std::vector<std::string> address_categories;

	bool operator==(const Content&) const = default;
};

// Writes `c` as a backup file. `category_name` gives the name of a category id, and 0 gives "Unfiled".
// The category lists are the ones in `c`, in order, plus any name that a record uses and the list lacks.
std::string write(const Content& c, const std::function<std::string(std::int32_t)>& category_name);

// Reads a backup file written by either app. `category_id` gets a category name and its app (0 To Do,
// 1 Memo Pad, 2 Address Book) and returns the id; the caller finds or creates the category. "Unfiled"
// and a blank name give 0 without a call. The category lists of the file also go through `category_id`,
// so a category with no records is created. Fails when the text is not JSON or its "format" is not
// FORMAT, before `category_id` runs, so a bad file creates no category.
std::expected<Content, std::string>
read(std::string_view text, const std::function<std::int32_t(std::string_view, std::int32_t)>& category_id);

} // namespace palm::backup
