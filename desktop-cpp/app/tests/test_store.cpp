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
#include <stdexcept>
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

TEST_CASE("set_up_short_cuts_uploads_only_a_custom_list") {
	auto s = DesktopStore::open(file()).value();
	// A store that never synced has no list, which must not replace the list of the other devices.
	s.set_short_cuts_from_sync({}, 40);
	s.set_up_short_cuts();
	CHECK(s.short_cuts_rev() == 0);
	CHECK_FALSE(s.short_cuts_dirty());
	s.set_short_cuts_from_sync(codec::default_shortcuts(), 41);
	s.set_up_short_cuts();
	CHECK_FALSE(s.short_cuts_dirty());
	s.set_short_cuts_from_sync({{"ad", "Address"}}, 42);
	s.set_up_short_cuts();
	CHECK(s.short_cuts_rev() == 0);
	CHECK(s.short_cuts_dirty());
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

TEST_CASE("a_file_with_records_as_json_texts_loads_and_saves_them_as_objects") {
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
	// The sync state loads as it was: only the tombstone waits to go up.
	CHECK(s.sync_since() == 250);
	const auto dirty = s.dirty_rows();
	REQUIRE(dirty.size() == 1);
	CHECK(dirty[0].uid == "e-3");
	CHECK(dirty[0].deleted);
	CHECK(!s.short_cuts_dirty());
	REQUIRE(s.short_cuts().size() > 1);
	CHECK(s.short_cuts()[1] == std::pair<std::string, std::string>{"dts", "{date} {time}"});

	s.persist();
	CHECK(s.save_error == std::nullopt);
	const std::string text = read_text(path);
	CHECK(text.find("\n\t\"rows\": [") != std::string::npos);
	const nlohmann::json root = nlohmann::json::parse(text);
	CHECK(root["version"] == 2);
	for (const nlohmann::json& row : root["rows"]) {
		CHECK(row.contains("plain") == !row["deleted"].get<bool>());
		if (row.contains("plain")) {
			CHECK(row["plain"].is_object());
		}
	}
	auto r = DesktopStore::open(path).value();
	CHECK(r.todos == s.todos);
	CHECK(r.memos == s.memos);
	CHECK(r.events == s.events);
	CHECK(r.dirty_rows() == s.dirty_rows());
	fs::path tmp = path;
	tmp += ".tmp";
	CHECK(!fs::exists(tmp));
}

TEST_CASE("a_save_keeps_the_file_before_it_as_the_backup") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	model::Memo first{};
	first.text = "First";
	s.save_memo(first);
	CHECK(!fs::exists(st::backup_path(path)));
	const std::string before = read_text(path);
	model::Memo second{};
	second.text = "Second";
	s.save_memo(second);
	CHECK(s.save_error == std::nullopt);
	CHECK(read_text(st::backup_path(path)) == before);
	CHECK(DesktopStore::open(path).value().memos.size() == 2);
	CHECK(DesktopStore::open(st::backup_path(path)).value().memos.size() == 1);
}

TEST_CASE("restore_backup_keeps_the_bad_file_and_puts_the_backup_in_its_place") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	model::Memo first{};
	first.text = "First";
	s.save_memo(first);
	model::Memo second{};
	second.text = "Second";
	s.save_memo(second);
	write_text(path, "{\"rows\": 5}");
	REQUIRE(!DesktopStore::open(path).has_value());
	const fs::path bad = st::bad_path(path);
	const auto kept = st::restore_backup(path);
	REQUIRE(kept.has_value());
	CHECK(*kept == bad);
	CHECK(read_text(bad) == "{\"rows\": 5}");
	const auto r = DesktopStore::open(path).value();
	REQUIRE(r.memos.size() == 1);
	CHECK(r.memos[0].text == "First");
	// The backup stays, so a second failure can restore it again.
	CHECK(fs::exists(st::backup_path(path)));
	CHECK(!fs::exists(path.string() + ".tmp"));
}

TEST_CASE("a_second_restore_numbers_its_bad_file_and_keeps_the_first") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	model::Memo first{};
	first.text = "First";
	s.save_memo(first);
	model::Memo second{};
	second.text = "Second";
	s.save_memo(second);
	write_text(path, "bad one");
	REQUIRE(st::restore_backup(path).value() == path.string() + ".bad");
	write_text(path, "bad two");
	REQUIRE(st::restore_backup(path).value() == path.string() + ".bad.1");
	write_text(path, "bad three");
	REQUIRE(st::restore_backup(path).value() == path.string() + ".bad.2");
	CHECK(read_text(path.string() + ".bad") == "bad one");
	CHECK(read_text(path.string() + ".bad.1") == "bad two");
	CHECK(read_text(path.string() + ".bad.2") == "bad three");
	CHECK(DesktopStore::open(path).value().memos.size() == 1);
}

TEST_CASE("a_failed_restore_leaves_the_data_file_in_place") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	model::Memo first{};
	first.text = "First";
	s.save_memo(first);
	write_text(path, "bad one");
	// No backup: the restore fails, and the data file and the bad names are as they were.
	REQUIRE(!fs::exists(st::backup_path(path)));
	CHECK(!st::restore_backup(path).has_value());
	CHECK(read_text(path) == "bad one");
	CHECK(!fs::exists(path.string() + ".bad"));
}

TEST_CASE("a_batch_saves_once_at_its_end_and_backs_up_the_file_before_it") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	model::Memo first{};
	first.text = "First";
	s.save_memo(first);
	const std::string before = read_text(path);
	int changes = 0;
	s.on_change = [&changes] { ++changes; };
	const std::uint64_t version = s.version;
	{
		DesktopStore::Batch batch(s);
		for (int i = 0; i < 3; ++i) {
			model::Memo m{};
			m.text = std::format("Local {}", i);
			s.save_memo(m);
		}
		{
			// A nested batch saves nothing at its end.
			DesktopStore::Batch inner(s);
			model::Memo remote{};
			remote.uid = "m-remote";
			remote.text = "Remote";
			s.put_remote(remote, 7);
			s.set_sync_since(7);
		}
		CHECK(read_text(path) == before);
		CHECK(s.version == version);
		CHECK(changes == 0);
	}
	CHECK(s.version == version + 1);
	CHECK(changes == 1);
	CHECK(s.save_error == std::nullopt);
	const auto r = DesktopStore::open(path).value();
	CHECK(r.memos.size() == 5);
	CHECK(r.sync_since() == 7);
	CHECK(read_text(st::backup_path(path)) == before);
}

TEST_CASE("put_tombstone_and_reset_sync_save_their_sync_state") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	model::Memo m{};
	m.text = "Kept";
	s.save_memo(m);
	s.mark_synced("memos", m.uid, 5, true);
	s.put_tombstone("memos", "m-gone", 9);
	// A uid that has a row keeps it.
	s.put_tombstone("memos", m.uid, 9);
	s.set_sync_since(9);
	auto r = DesktopStore::open(path).value();
	const auto gone = r.sync_row("m-gone");
	REQUIRE(gone.has_value());
	CHECK((gone->deleted && gone->dirty && gone->rev == 9));
	const auto kept = r.sync_row(m.uid);
	REQUIRE(kept.has_value());
	CHECK((!kept->deleted && !kept->dirty && kept->rev == 5));

	s.reset_sync();
	r = DesktopStore::open(path).value();
	CHECK(!r.sync_row("m-gone").has_value());
	CHECK(r.sync_since() == 0);
	const auto again = r.sync_row(m.uid);
	REQUIRE(again.has_value());
	CHECK((again->dirty && again->rev == 0));
	CHECK(r.memos.size() == 1);
}

TEST_CASE("a_batch_saves_what_changed_when_an_exception_ends_it") {
	const fs::path path = file();
	auto s = DesktopStore::open(path).value();
	int changes = 0;
	s.on_change = [&changes] { ++changes; };
	try {
		DesktopStore::Batch batch(s);
		model::Memo m{};
		m.text = "Kept";
		s.save_memo(m);
		throw std::runtime_error("import failed");
	} catch (const std::runtime_error&) {
	}
	CHECK(changes == 0);
	const auto r = DesktopStore::open(path).value();
	REQUIRE(r.memos.size() == 1);
	CHECK(r.memos[0].text == "Kept");
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
	auto [root_a, rows_a] = rows_of(original);
	auto [root_b, rows_b] = rows_of(written);
	// The version names the layout.
	root_a.erase("version");
	CHECK(root_b["version"] == 2);
	root_b.erase("version");
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
