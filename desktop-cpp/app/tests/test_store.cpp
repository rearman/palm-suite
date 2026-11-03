// links: text json model codec crypto client logic runner store days platform settings
#include "palm/codec.hpp"
#include "palm/model.hpp"
#include "palm/runner.hpp"
#include "palm/settings.hpp"
#include "palm/store.hpp"
#include "palm/text.hpp"

#include <nlohmann/json.hpp>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <doctest/doctest.h>
#include <filesystem>
#include <format>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <process.h>
#else
#include <unistd.h>
#endif

namespace fs = std::filesystem;
namespace st = palm::store;
namespace model = palm::model;
namespace runner = palm::runner;
namespace codec = palm::codec;
using st::DesktopStore;

namespace {

// The process id, for the names of the test folders.
long process_id() {
#ifdef _WIN32
	return static_cast<long>(_getpid());
#else
	return static_cast<long>(getpid());
#endif
}

// A new empty folder for one test.
fs::path temp_dir() {
	static std::atomic<unsigned> n{0};
	const fs::path d = fs::temp_directory_path() / std::format("palm-store-{}-{}", process_id(), n.fetch_add(1));
	std::error_code ec;
	fs::remove_all(d, ec);
	fs::create_directories(d);
	return d;
}

fs::path file() {
	return temp_dir() / "sub" / st::FILE_NAME;
}

// 2026-10-19, a Monday.
constexpr std::int64_t DAY = 20'745;

// An Event with a title and a first day, for the tests that need no other field.
model::Event titled_event(const std::string& title) {
	model::Event e{};
	e.title = title;
	e.date = DAY;
	return e;
}

// A To Do item with a title, for the tests that need no other field.
model::Todo titled_todo(const std::string& title) {
	model::Todo t{};
	t.title = title;
	return t;
}

// The uids of a category list.
std::vector<std::string> uids(const std::vector<model::Category>& list) {
	std::vector<std::string> out;
	for (const model::Category& c : list) {
		out.push_back(c.uid);
	}
	return out;
}

// The text of a file.
std::string read_text(const fs::path& path) {
	std::ifstream in(path, std::ios::binary);
	return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

// Writes `text` to a file.
void write_text(const fs::path& path, const std::string& text) {
	std::ofstream out(path, std::ios::binary | std::ios::trunc);
	out << text;
}

// The rows of a data file by table and id, each with its plain text read as JSON.
std::pair<nlohmann::json, std::map<std::pair<std::string, std::int64_t>, nlohmann::json>>
rows_of(const std::string& text) {
	nlohmann::json root = nlohmann::json::parse(text);
	const nlohmann::json rows = root["rows"];
	root.erase("rows");
	std::map<std::pair<std::string, std::int64_t>, nlohmann::json> out;
	for (nlohmann::json r : rows) {
		if (r.contains("plain") && r["plain"].is_string()) {
			r["plain"] = nlohmann::json::parse(r["plain"].get<std::string>());
		}
		CHECK(r["id"].is_number_integer());
		out[{r["table"].get<std::string>(), r["id"].get<std::int64_t>()}] = r;
	}
	return {root, out};
}

// True when two JSON values are equal with their types strict, as serde_json's Value compares: an integer and a
// float are different values, though nlohmann's == takes 1 and 1.0 as equal. Recursive over arrays and objects.
bool same_values(const nlohmann::json& a, const nlohmann::json& b) {
	if (a.is_number_integer() != b.is_number_integer() || a.is_number_float() != b.is_number_float()) {
		return false;
	}
	if (a.is_number()) {
		return a == b;
	}
	if (a.type() != b.type()) {
		return false;
	}
	if (a.is_array()) {
		if (a.size() != b.size()) {
			return false;
		}
		for (std::size_t i = 0; i < a.size(); ++i) {
			if (!same_values(a[i], b[i])) {
				return false;
			}
		}
		return true;
	}
	if (a.is_object()) {
		if (a.size() != b.size()) {
			return false;
		}
		for (const auto& [key, value] : a.items()) {
			const auto it = b.find(key);
			if (it == b.end() || !same_values(value, it.value())) {
				return false;
			}
		}
		return true;
	}
	return a == b;
}

} // namespace

TEST_CASE("new_store_has_the_default_categories") {
	auto s = DesktopStore::open(file()).value();
	CHECK(uids(s.categories) == std::vector<std::string>{"default-0-business", "default-0-personal"});
	CHECK(uids(s.memo_categories) == std::vector<std::string>{"default-1-business", "default-1-personal"});
	CHECK(uids(s.address_categories) ==
	      std::vector<std::string>{"default-2-business", "default-2-personal", "default-2-quicklist"});
	std::vector<std::int32_t> ids;
	for (const model::Category& c : s.categories) {
		ids.push_back(c.id);
	}
	CHECK(ids == std::vector<std::int32_t>{1, 2});
	ids.clear();
	for (const model::Category& c : s.memo_categories) {
		ids.push_back(c.id);
	}
	CHECK(ids == std::vector<std::int32_t>{3, 4});
}

TEST_CASE("save_then_reopen_keeps_records") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	const model::Category work = s.add_category("Work", 0);
	model::Event dentist = titled_event("Dentist");
	dentist.start = 600;
	dentist.end = 630;
	dentist.alarm = true;
	dentist.alarm_advance = 10;
	dentist.repeat = model::repeat::WEEK;
	dentist.repeat_end = DAY + 60;
	dentist.week_days = 2;
	s.save_event(dentist);
	model::Event lunch = titled_event("Lunch");
	lunch.untimed = true;
	s.save_event(lunch);
	model::Todo report = titled_todo("Report");
	report.priority = 2;
	report.due = DAY;
	report.category = work.id;
	s.save_todo(report);
	model::Memo shopping{};
	shopping.text = "Shopping\nmilk";
	s.save_memo(shopping);
	model::Memo ideas{};
	ideas.text = "Ideas";
	s.save_memo(ideas);
	CHECK(s.save_error == std::nullopt);

	auto r = DesktopStore::open(path).value();
	CHECK(s.events == r.events);
	CHECK(s.todos == r.todos);
	CHECK(s.memos == r.memos);
	CHECK(s.categories == r.categories);
	CHECK(s.memo_categories == r.memo_categories);
	std::vector<std::int32_t> pos;
	for (const model::Memo& m : r.memos) {
		pos.push_back(m.pos);
	}
	CHECK(pos == std::vector<std::int32_t>{0, 1});
}

TEST_CASE("delete_of_never_synced_record_removes_it") {
	auto s = DesktopStore::open(file()).value();
	model::Event e = titled_event("Gone");
	s.save_event(e);
	s.delete_event(e.id);
	CHECK(s.events.empty());
	CHECK(!s.sync_row(e.uid).has_value());
}

TEST_CASE("delete_of_synced_record_leaves_tombstone") {
	auto s = DesktopStore::open(file()).value();
	model::Event e = titled_event("Kept");
	s.save_event(e);
	s.mark_synced("events", e.uid, 5, true);
	s.delete_event(e.id);
	CHECK(s.events.empty());
	std::vector<runner::SyncRow> rows;
	for (const runner::SyncRow& r : s.dirty_rows()) {
		if (r.table == "events") {
			rows.push_back(r);
		}
	}
	REQUIRE(rows.size() == 1);
	CHECK(rows[0].deleted);
	CHECK(rows[0].dirty);
	CHECK(rows[0].uid == e.uid);
	CHECK(rows[0].rev == 5);
	// Once the server takes the delete, the tombstone goes.
	s.mark_synced("events", e.uid, 6, true);
	CHECK(!s.sync_row(e.uid).has_value());
}

TEST_CASE("put_remote_keeps_the_local_id") {
	auto s = DesktopStore::open(file()).value();
	model::Event e = titled_event("Local");
	s.save_event(e);
	model::Event remote = titled_event("Remote");
	remote.uid = e.uid;
	s.put_remote(runner::Record(remote), 7);
	REQUIRE(s.events.size() == 1);
	CHECK(s.events[0].id == e.id);
	CHECK(s.events[0].title == "Remote");
	const auto row = s.sync_row(e.uid).value();
	CHECK(row.rev == 7);
	CHECK(!row.dirty);
	CHECK(!row.deleted);
}

TEST_CASE("drop_remote_category_moves_todos_to_unfiled") {
	auto s = DesktopStore::open(file()).value();
	const model::Category work = s.add_category("Work", 0);
	model::Todo t = titled_todo("Task");
	t.category = work.id;
	s.save_todo(t);
	s.mark_synced("todos", t.uid, 3, true);
	s.drop_remote("categories", work.uid);
	REQUIRE(!s.todos.empty());
	CHECK(s.todos[0].category == 0);
	CHECK(std::ranges::none_of(s.categories, [&work](const model::Category& c) { return c.uid == work.uid; }));
	CHECK(!s.sync_row(t.uid).value().dirty);
}

TEST_CASE("delete_category_moves_items_to_unfiled_and_marks_them") {
	auto s = DesktopStore::open(file()).value();
	const model::Category work = s.add_category("Work", 0);
	model::Todo t = titled_todo("Task");
	t.category = work.id;
	s.save_todo(t);
	s.mark_synced("todos", t.uid, 3, true);
	s.delete_category(0, work.id);
	REQUIRE(!s.todos.empty());
	CHECK(s.todos[0].category == 0);
	CHECK(s.sync_row(t.uid).value().dirty);
}

TEST_CASE("purge_todos_deletes_done_items") {
	auto s = DesktopStore::open(file()).value();
	model::Todo done = titled_todo("Done");
	done.done = true;
	s.save_todo(done);
	model::Todo open = titled_todo("Open");
	s.save_todo(open);
	CHECK(s.purge_todos() == 1);
	std::vector<std::string> titles;
	for (const model::Todo& t : s.todos) {
		titles.push_back(t.title);
	}
	CHECK(titles == std::vector<std::string>{"Open"});
}

TEST_CASE("weekly_event_occurs_on_its_days_only") {
	auto s = DesktopStore::open(file()).value();
	model::Event standup = titled_event("Standup");
	standup.repeat = model::repeat::WEEK;
	standup.week_days = 2;
	s.save_event(standup);
	CHECK(s.day_events(DAY + 7).size() == 1);
	CHECK(s.day_events(DAY + 1).empty());
	const auto d = s.day_events(DAY + 14);
	REQUIRE(!d.empty());
	CHECK(d[0]->title == "Standup");
}

TEST_CASE("find_ignores_case") {
	auto s = DesktopStore::open(file()).value();
	model::Event dentist = titled_event("Dentist");
	s.save_event(dentist);
	model::Todo milk = titled_todo("Buy MILK");
	s.save_todo(milk);
	model::Memo call{};
	call.text = "Call the DENTIST";
	s.save_memo(call);
	CHECK(s.find("dentist").size() == 2);
	CHECK(s.find("milk").size() == 1);
}

TEST_CASE("new_uids_are_version_4") {
	const std::string u = st::new_uid();
	CHECK(u.size() == 36);
	CHECK(u.substr(14, 1) == "4");
	CHECK(std::string("89ab").find(u[19]) != std::string::npos);
	CHECK(u != st::new_uid());
}

// The path of a copy of the fixture in a new folder, and the store opened on it.
std::pair<fs::path, DesktopStore> fixture_store() {
	const fs::path path = temp_dir() / st::FILE_NAME;
	fs::copy_file(PALM_FIXTURES "/desktop-data-v1.json", path);
	auto s = DesktopStore::open(path).value();
	return {path, std::move(s)};
}

TEST_CASE("format_1_file_loads_and_uploads_again") {
	auto [path, s] = fixture_store();
	// The categories load first, so the To Do and the memo find theirs although their rows come earlier.
	auto work_it = std::ranges::find_if(s.categories, [](const model::Category& c) { return c.name == "Work"; });
	REQUIRE(work_it != s.categories.end());
	const std::int32_t work = work_it->id;
	REQUIRE(!s.todos.empty());
	CHECK(s.todos[0].title == "Order VFD");
	CHECK(s.todos[0].category == work);
	CHECK(s.todos[0].id == 2);
	std::vector<std::string> memo_texts;
	for (const model::Memo& m : s.memos) {
		memo_texts.push_back(m.text);
	}
	CHECK(memo_texts == std::vector<std::string>{"First\nbody", "Second"});
	REQUIRE(s.memos.size() == 2);
	CHECK(s.memos[1].category == 9);
	REQUIRE(!s.events.empty());
	CHECK(s.events[0].exceptions == std::set<std::int64_t>{20'757});
	// A file without addressSync reads all rows again; a format 1 file uploads every live row again.
	CHECK(s.sync_since() == 0);
	const auto dirty = s.dirty_rows();
	CHECK(dirty.size() == 7);
	CHECK(std::ranges::any_of(dirty, [](const runner::SyncRow& r) { return r.uid == "e-3" && r.deleted; }));
	CHECK(s.short_cuts_dirty());
	REQUIRE(s.short_cuts().size() > 1);
	CHECK(s.short_cuts()[1] == std::pair<std::string, std::string>{"dts", "{date} {time}"});

	s.persist();
	CHECK(s.save_error == std::nullopt);
	const nlohmann::json root = nlohmann::json::parse(read_text(path));
	CHECK(root.at("syncFormat").is_number_integer());
	CHECK(root["syncFormat"] == 2);
	CHECK(root["addressSync"] == true);
	auto r = DesktopStore::open(path).value();
	CHECK(r.todos == s.todos);
	CHECK(r.memos == s.memos);
	CHECK(r.events == s.events);
	CHECK(r.dirty_rows() == s.dirty_rows());
	fs::path tmp = path;
	tmp += ".tmp";
	CHECK(!fs::exists(tmp));
}

TEST_CASE("a_memo_with_bad_utf8_saves_as_replacement_characters") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	model::Memo m{};
	m.text = "bad\xFF";
	s.save_memo(m);
	CHECK(s.save_error == std::nullopt);
	auto r = DesktopStore::open(path).value();
	REQUIRE(r.memos.size() == 1);
	CHECK(r.memos[0].text == "bad\xEF\xBF\xBD");
}

TEST_CASE("a_bad_file_does_not_open") {
	const fs::path path = temp_dir() / st::FILE_NAME;
	write_text(path, "{\"rows\": 5}");
	CHECK(!DesktopStore::open(path).has_value());
	write_text(path, "Palm Desktop");
	CHECK(!DesktopStore::open(path).has_value());
}

TEST_CASE("second_lock_fails") {
	const fs::path dir = temp_dir();
	{
		auto first = st::lock(dir);
		REQUIRE(first.has_value());
		auto second = st::lock(dir);
		REQUIRE(!second.has_value());
		CHECK(second.error() == "Palm Desktop is already running.");
	}
	CHECK(st::lock(dir).has_value());
}

// The real files of this machine, as the Rust test: copies in PALM_REAL_DATA are read and written back. The
// settings half runs only when PALM_REAL_DATA holds desktop.properties.
TEST_CASE("real_files_round_trip") {
	const char* env = std::getenv("PALM_REAL_DATA");
	if (env == nullptr) {
		return;
	}
	const fs::path dir = env;
	const fs::path work = temp_dir();
	const fs::path path = work / st::FILE_NAME;
	fs::copy_file(dir / st::FILE_NAME, path);
	const std::string original = read_text(path);
	auto s = DesktopStore::open(path).value();
	s.persist();
	CHECK(s.save_error == std::nullopt);
	const std::string written = read_text(path);
	const auto [root_a, rows_a] = rows_of(original);
	const auto [root_b, rows_b] = rows_of(written);
	CHECK(same_values(root_a, root_b));
	CHECK(rows_a.size() == rows_b.size());
	for (const auto& [key, a] : rows_a) {
		CHECK(rows_b.count(key) == 1);
		if (rows_b.count(key) == 1) {
			CHECK(same_values(rows_b.at(key), a));
		}
	}
	std::cout << std::format("real data: {} rows written back as the same values\n", rows_a.size());

	if (!fs::exists(dir / palm::settings::FILE_NAME)) {
		return;
	}
	// The bytes of the file as latin1 chars, each char as UTF-8, as the Rust test reads them.
	const std::string bytes = read_text(dir / palm::settings::FILE_NAME);
	std::string latin1_as_utf8;
	for (const char b : bytes) {
		palm::text::append_utf8(latin1_as_utf8, static_cast<unsigned char>(b));
	}
	const palm::settings::Props p = palm::settings::parse(latin1_as_utf8);
	CHECK(palm::settings::parse(palm::settings::store(p, "c", "d")) == p);
	std::cout << std::format("real settings: {} keys written back as the same values\n", p.size());
}
