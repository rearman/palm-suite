// links: assets draw msg mnemonics platform_ui dialogs combo text_edit split records note_dialog date_picker category todo
// The four tests of desktop-rs/app/src/todo.rs: the sort index, the sort menu, the order of the items, and the due
// labels with the completion and the Show Options rules.
#include "palm/todo.hpp"

#include "palm/settings.hpp"
#include "palm/store.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace fs = std::filesystem;
namespace todo = palm::todo;
namespace settings = palm::settings;
namespace store = palm::store;

namespace {

using Pairs = std::vector<std::pair<std::string, std::string>>;

// A settings file in a new folder under the system temp folder, with `pairs` set in it.
std::pair<settings::Settings, fs::path> settings_with(const Pairs& pairs) {
	const fs::path dir = fs::temp_directory_path() / ("palm-todo-" + store::new_uid());
	fs::create_directories(dir);
	settings::Settings s = settings::Settings::open(dir);
	for (const auto& [k, v] : pairs) {
		REQUIRE(s.set(k, v).has_value());
	}
	return {std::move(s), dir};
}

// A To Do item with `id`, `priority`, and `due`.
palm::model::Todo make(std::int64_t id, std::int32_t priority, std::int64_t due) {
	palm::model::Todo t;
	t.id = id;
	t.priority = priority;
	t.due = due;
	return t;
}

} // namespace

TEST_CASE("the sort index reads the old setting with its first two swapped") {
	const std::vector<std::pair<Pairs, std::size_t>> cases = {
	    {{{"todoSortBy", "2"}}, 2}, {{{"todoSortBy", "9"}}, 3}, {{}, 1},
	    {{{"todoSort", "1"}}, 0},   {{{"todoSort", "3"}}, 3},
	};
	for (const auto& [pairs, want] : cases) {
		auto [s, dir] = settings_with(pairs);
		CHECK_EQ(todo::sort_index(s), want);
		fs::remove_all(dir);
	}
}

TEST_CASE("the sort menu swaps the last two and keeps the stored meaning") {
	auto [s, dir] = settings_with({{"todoSortBy", "2"}});
	CHECK_EQ(todo::sort_index(s), 2u);
	CHECK_EQ(todo::menu_sort(todo::sort_index(s)), 3u);
	CHECK_EQ(todo::menu_sort(todo::menu_sort(2)), 2u);
	fs::remove_all(dir);
}

TEST_CASE("order puts items without a date last and keeps ties in store order") {
	auto [unused, dir] = settings_with({});
	auto opened = store::DesktopStore::open(dir / std::string(store::FILE_NAME));
	REQUIRE(opened.has_value());
	const store::DesktopStore& desk = *opened;
	const std::vector<palm::model::Todo> list = {
	    make(1, 2, -1), make(2, 1, 50), make(3, 1, -1), make(4, 2, 40), make(5, 1, 50),
	};
	const auto ids = [](const std::vector<palm::model::Todo>& v) {
		std::vector<std::int64_t> out;
		for (const auto& t : v) {
			out.push_back(t.id);
		}
		return out;
	};
	const std::vector<std::int64_t> by_priority_want = {2, 5, 3, 4, 1};
	const std::vector<std::int64_t> by_due_want = {4, 2, 5, 3, 1};
	auto [by_pri, d1] = settings_with({{"todoSortBy", "0"}});
	CHECK(ids(todo::todo_order(list, by_pri, desk)) == by_priority_want);
	auto [by_due, d2] = settings_with({{"todoSortBy", "1"}});
	CHECK(ids(todo::todo_order(list, by_due, desk)) == by_due_want);
	for (const auto& d : {d1, d2, dir}) {
		fs::remove_all(d);
	}
}

TEST_CASE("due labels, completion, and the Show Options rules") {
	const std::int64_t today = 100;
	// The no-date label is a hyphen here; Rust shows a long dash, which the source of this port cannot carry.
	CHECK_EQ(todo::due_label(make(1, 1, -1), today), todo::NO_DATE);
	// Epoch day 99 is 1970-04-10.
	CHECK_EQ(todo::due_label(make(1, 1, 99), today), "4/10!");
	CHECK_EQ(todo::due_label(make(1, 1, 100), today), "4/11");
	auto [record, dir] = settings_with({{"todoRecordDone", "true"}, {"todoOnlyDue", "true"}});
	palm::model::Todo t = make(1, 1, 120);
	CHECK_FALSE(todo::todo_shown(t, record, false, today));
	todo::mark_done(t, true, record, today);
	CHECK((t.done && t.due == 100));
	CHECK_MESSAGE(!todo::todo_shown(t, record, false, today), "completed items are hidden by default");
	palm::model::Todo priv = make(2, 1, -1);
	priv.is_private = true;
	CHECK((todo::todo_shown(priv, record, true, today) && !todo::todo_shown(priv, record, false, today)));
	fs::remove_all(dir);
	auto [none, dir2] = settings_with({});
	CHECK(todo::due_for(2, -1, today, none) == std::optional<std::int64_t>{107});
	CHECK(todo::due_for(3, 50, today, none) == std::optional<std::int64_t>{-1});
	fs::remove_all(dir2);
}
