// The Palm Desktop record store, as desktop-rs/app/src/store.rs (DesktopStore.kt). See store.hpp.

#include "palm/store.hpp"

#include "palm/crypto.hpp"
#include "palm/json.hpp"
#include "palm/platform.hpp"
#include "palm/text.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <format>
#include <fstream>
#include <initializer_list>
#include <iterator>
#include <optional>
#include <string>
#include <string_view>
#include <system_error>
#include <tuple>
#include <utility>
#include <vector>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <cerrno>
#include <fcntl.h>
#include <sys/file.h>
#include <unistd.h>
#endif

namespace palm::store {

namespace fs = std::filesystem;

namespace {

// The sync tables in the order of the Rust TABLES.
constexpr std::array<std::string_view, 5> TABLES = {"categories", "events", "todos", "memos", "addresses"};
constexpr std::size_t CATEGORIES = 0;
constexpr std::size_t EVENTS = 1;
constexpr std::size_t TODOS = 2;
constexpr std::size_t MEMOS = 3;
constexpr std::size_t ADDRESSES = 4;

// The table index of a table name; an unknown name reads as memos, as in Rust.
std::size_t table_index(std::string_view table) {
	for (std::size_t i = 0; i < TABLES.size(); ++i) {
		if (TABLES[i] == table) {
			return i;
		}
	}
	return MEMOS;
}

// The sync row of a table and id.
runner::SyncRow row_of(std::size_t t, std::int64_t id, const std::string& uid, std::int64_t rev, bool dirty,
                       bool deleted) {
	return runner::SyncRow{std::string(TABLES[t]), id, uid, rev, dirty, deleted};
}

// Replaces the item that `same` finds, or adds `item` when none is found.
template <typename T, typename Pred> void put_in_list(std::vector<T>& list, T item, Pred same) {
	auto it = std::ranges::find_if(list, same);
	if (it != list.end()) {
		*it = std::move(item);
	} else {
		list.push_back(std::move(item));
	}
}

// The value of a key of a JSON object, or nullptr when the key is missing.
const nlohmann::json* field(const json::Obj& o, std::string_view key) {
	auto it = o.find(std::string(key));
	return it == o.end() ? nullptr : &*it;
}

// Writes `text` to a temp file beside `file`, then moves it over `file`. Returns the error, if any.
std::optional<std::string> write_atomic(const fs::path& file, const std::string& text) {
	std::error_code ec;
	if (const fs::path dir = file.parent_path(); !dir.empty()) {
		fs::create_directories(dir, ec);
		if (ec) {
			return palm::platform::os_error_text(ec);
		}
	}
	fs::path tmp = file;
	tmp += ".tmp";
	{
		std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
		if (!out) {
			return palm::platform::last_os_error_text();
		}
		out.write(text.data(), static_cast<std::streamsize>(text.size()));
		out.close();
		if (!out) {
			return palm::platform::last_os_error_text();
		}
	}
	fs::rename(tmp, file, ec);
	if (ec) {
		return palm::platform::os_error_text(ec);
	}
	return std::nullopt;
}

} // namespace

std::string new_uid() {
	std::vector<std::uint8_t> b = crypto::random_bytes(16);
	b[6] = static_cast<std::uint8_t>((b[6] & 0x0f) | 0x40);
	b[8] = static_cast<std::uint8_t>((b[8] & 0x3f) | 0x80);
	std::string h;
	for (std::uint8_t x : b) {
		h += std::format("{:02x}", static_cast<unsigned>(x));
	}
	return std::format("{}-{}-{}-{}-{}", std::string_view(h).substr(0, 8), std::string_view(h).substr(8, 4),
	                   std::string_view(h).substr(12, 4), std::string_view(h).substr(16, 4),
	                   std::string_view(h).substr(20, 12));
}

#ifdef _WIN32

LockFile::LockFile(LockFile&& other) noexcept : handle_(std::exchange(other.handle_, nullptr)) {}

LockFile& LockFile::operator=(LockFile&& other) noexcept {
	if (this != &other) {
		reset();
		handle_ = std::exchange(other.handle_, nullptr);
	}
	return *this;
}

LockFile::~LockFile() {
	reset();
}

void LockFile::reset() noexcept {
	if (handle_ != nullptr) {
		CloseHandle(static_cast<HANDLE>(handle_));
		handle_ = nullptr;
	}
}

std::expected<LockFile, std::string> lock(const fs::path& dir) {
	std::error_code ec;
	fs::create_directories(dir, ec);
	if (ec) {
		return std::unexpected(palm::platform::os_error_text(ec));
	}
	const std::wstring path = (dir / "desktop.lock").wstring();
	HANDLE h =
	    CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
	                nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
	if (h == INVALID_HANDLE_VALUE) {
		return std::unexpected(palm::platform::os_error_text(static_cast<int>(GetLastError())));
	}
	OVERLAPPED ov{};
	if (!LockFileEx(h, LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, MAXDWORD, MAXDWORD, &ov)) {
		const DWORD err = GetLastError();
		CloseHandle(h);
		if (err == ERROR_LOCK_VIOLATION) {
			return std::unexpected(std::string("Palm Desktop is already running."));
		}
		return std::unexpected(palm::platform::os_error_text(static_cast<int>(err)));
	}
	LockFile f;
	f.handle_ = h;
	return f;
}

#else

LockFile::LockFile(LockFile&& other) noexcept : fd_(std::exchange(other.fd_, -1)) {}

LockFile& LockFile::operator=(LockFile&& other) noexcept {
	if (this != &other) {
		reset();
		fd_ = std::exchange(other.fd_, -1);
	}
	return *this;
}

LockFile::~LockFile() {
	reset();
}

void LockFile::reset() noexcept {
	if (fd_ >= 0) {
		close(fd_);
		fd_ = -1;
	}
}

std::expected<LockFile, std::string> lock(const fs::path& dir) {
	std::error_code ec;
	fs::create_directories(dir, ec);
	if (ec) {
		return std::unexpected(palm::platform::os_error_text(ec));
	}
	const std::string path = (dir / "desktop.lock").string();
	const int fd = open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
	if (fd < 0) {
		return std::unexpected(palm::platform::last_os_error_text());
	}
	if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
		const int err = errno;
		close(fd);
		if (err == EWOULDBLOCK) {
			return std::unexpected(std::string("Palm Desktop is already running."));
		}
		return std::unexpected(palm::platform::os_error_text(err));
	}
	LockFile f;
	f.fd_ = fd;
	return f;
}

#endif

std::expected<DesktopStore, std::string> DesktopStore::open(const fs::path& file) {
	DesktopStore store(file);
	std::ifstream in(file, std::ios::binary);
	if (!in) {
		const std::string why = palm::platform::last_os_error_text();
		std::error_code ec;
		if (!fs::exists(file, ec)) {
			store.add_default_categories();
			return store;
		}
		return std::unexpected(std::format("{}: {}", file.string(), why));
	}
	const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
	// read_to_string refuses a file that is not UTF-8, with this io::Error text.
	if (!text::is_utf8(text)) {
		return std::unexpected(std::format("{}: stream did not contain valid UTF-8", file.string()));
	}
	if (auto r = store.load(text); !r) {
		return std::unexpected(r.error());
	}
	return store;
}

// ---- lookups ----

const std::vector<model::Category>& DesktopStore::categories_for(std::int32_t app) const {
	switch (app) {
	case 1:
		return memo_categories;
	case 2:
		return address_categories;
	default:
		return categories;
	}
}

std::vector<model::Category>& DesktopStore::categories_for_mut(std::int32_t app) {
	switch (app) {
	case 1:
		return memo_categories;
	case 2:
		return address_categories;
	default:
		return categories;
	}
}

const model::Category* DesktopStore::find_category(std::int64_t id) const {
	for (const std::vector<model::Category>* list : {&categories, &memo_categories, &address_categories}) {
		for (const model::Category& c : *list) {
			if (static_cast<std::int64_t>(c.id) == id) {
				return &c;
			}
		}
	}
	return nullptr;
}

std::string DesktopStore::category_name(std::int32_t id) const {
	const model::Category* c = find_category(id);
	return c != nullptr ? c->name : std::string("Unfiled");
}

std::vector<const model::Event*> DesktopStore::day_events(std::int64_t day) const {
	std::vector<const model::Event*> v;
	for (const model::Event& e : events) {
		if (model::occurs_on(e, day)) {
			v.push_back(&e);
		}
	}
	auto key = [](const model::Event* e) { return std::tuple(!e->untimed, e->start, e->end); };
	std::stable_sort(v.begin(), v.end(),
	                 [&key](const model::Event* a, const model::Event* b) { return key(a) < key(b); });
	return v;
}

std::vector<runner::Record> DesktopStore::find(std::string_view text) const {
	const std::string t = text::to_lowercase(text);
	auto has = [&t](std::string_view s) { return text::to_lowercase(s).find(t) != std::string::npos; };
	std::vector<runner::Record> out;
	for (const model::Event& e : events) {
		if (has(e.title) || has(e.note)) {
			out.emplace_back(e);
		}
	}
	for (const model::Todo& x : todos) {
		if (has(x.title) || has(x.note)) {
			out.emplace_back(x);
		}
	}
	for (const model::Memo& m : memos) {
		if (has(m.text)) {
			out.emplace_back(m);
		}
	}
	for (const model::Address& a : addresses) {
		bool hit = false;
		for (std::string_view s : {std::string_view(a.last_name), std::string_view(a.first_name),
		                           std::string_view(a.title), std::string_view(a.company), std::string_view(a.address),
		                           std::string_view(a.city), std::string_view(a.state), std::string_view(a.zip),
		                           std::string_view(a.country), std::string_view(a.note)}) {
			hit = hit || has(s);
		}
		for (const std::string& s : a.phones) {
			hit = hit || has(s);
		}
		for (const std::string& s : a.custom) {
			hit = hit || has(s);
		}
		if (hit) {
			out.emplace_back(a);
		}
	}
	return out;
}

// ---- local writes ----

void DesktopStore::save_event(model::Event& e) {
	put_event(e);
	changed();
}

void DesktopStore::save_todo(model::Todo& t) {
	put_todo(t);
	changed();
}

void DesktopStore::save_memo(model::Memo& m) {
	put_memo(m);
	changed();
}

void DesktopStore::save_address(model::Address& a) {
	put_address(a);
	changed();
}

void DesktopStore::delete_event(std::int64_t id) {
	remove_local(EVENTS, id);
	std::erase_if(events, [id](const model::Event& e) { return e.id == id; });
	changed();
}

void DesktopStore::delete_todo(std::int64_t id) {
	remove_local(TODOS, id);
	std::erase_if(todos, [id](const model::Todo& x) { return x.id == id; });
	changed();
}

void DesktopStore::delete_memo(std::int64_t id) {
	remove_local(MEMOS, id);
	std::erase_if(memos, [id](const model::Memo& m) { return m.id == id; });
	changed();
}

void DesktopStore::delete_address(std::int64_t id) {
	remove_local(ADDRESSES, id);
	std::erase_if(addresses, [id](const model::Address& a) { return a.id == id; });
	changed();
}

model::Category DesktopStore::add_category(std::string_view name, std::int32_t app) {
	model::Category cat = new_category(name, app);
	changed();
	return cat;
}

model::Category DesktopStore::new_category(std::string_view name, std::int32_t app) {
	const auto id = static_cast<std::int32_t>(next_id(CATEGORIES));
	model::Category cat{.id = id, .name = std::string(name), .app = app, .uid = new_uid()};
	mark_dirty(CATEGORIES, id, cat.uid);
	std::vector<model::Category>& list = categories_for_mut(app);
	list.push_back(cat);
	model::sort_categories(list);
	return cat;
}

void DesktopStore::rename_category(std::int32_t app, std::int32_t id, std::string_view name) {
	std::vector<model::Category>& list = categories_for_mut(app);
	auto it = std::ranges::find_if(list, [id](const model::Category& c) { return c.id == id; });
	if (it == list.end()) {
		return;
	}
	it->name = std::string(name);
	const std::string uid = it->uid;
	model::sort_categories(list);
	mark_dirty(CATEGORIES, id, uid);
	changed();
}

void DesktopStore::delete_category(std::int32_t app, std::int32_t id) {
	remove_local(CATEGORIES, id);
	std::erase_if(categories_for_mut(app), [id](const model::Category& c) { return c.id == id; });
	move_to_unfiled(id, true);
	changed();
}

std::size_t DesktopStore::purge_events(std::int64_t before) {
	std::vector<std::int64_t> old;
	for (const model::Event& e : events) {
		if (model::last_day(e) < before) {
			old.push_back(e.id);
		}
	}
	for (std::int64_t id : old) {
		remove_local(EVENTS, id);
	}
	std::erase_if(events, [before](const model::Event& e) { return model::last_day(e) < before; });
	if (!old.empty()) {
		changed();
	}
	return old.size();
}

std::size_t DesktopStore::purge_todos() {
	std::vector<std::int64_t> done;
	for (const model::Todo& t : todos) {
		if (t.done) {
			done.push_back(t.id);
		}
	}
	for (std::int64_t id : done) {
		remove_local(TODOS, id);
	}
	std::erase_if(todos, [](const model::Todo& t) { return t.done; });
	if (!done.empty()) {
		changed();
	}
	return done.size();
}

void DesktopStore::set_short_cuts(codec::ShortCuts list) {
	short_cut_list_ = std::move(list);
	sc_dirty_ = true;
	changed();
}

// ---- the file ----

std::expected<std::string, std::string> DesktopStore::to_json() const {
	nlohmann::json rows = nlohmann::json::array();
	for (std::size_t t = 0; t < TABLES.size(); ++t) {
		for (const auto& [id, m] : meta_[t]) {
			nlohmann::json row = {{"table", std::string(TABLES[t])},
			                      {"id", id},
			                      {"uid", m.uid},
			                      {"rev", m.rev},
			                      {"dirty", m.dirty},
			                      {"deleted", m.deleted}};
			if (!m.deleted) {
				std::optional<std::string> plain = plain_of(t, id);
				if (!plain) {
					return std::unexpected(std::format("No live record for {} {}", TABLES[t], id));
				}
				row["plain"] = *plain;
			}
			rows.push_back(row);
		}
	}
	nlohmann::json shortcuts = nlohmann::json::array();
	for (const auto& [name, txt] : short_cut_list_) {
		nlohmann::json s = {{"name", name}, {"text", txt}};
		shortcuts.push_back(s);
	}
	nlohmann::json root = {{"version", 1},         {"since", since_},           {"scRev", sc_rev_},
	                       {"scDirty", sc_dirty_}, {"shortcuts", shortcuts},    {"rows", rows},
	                       {"addressSync", true},  {"syncFormat", sync_format_}};
	// serde_json writes compact text with raw UTF-8, so the dump matches it. A string with bytes that are not UTF-8
	// gets U+FFFD for each bad byte; the save then goes on, as a Rust save of the same text would.
	return json::dump(root);
}

void DesktopStore::persist() {
	++version;
	try {
		std::expected<std::string, std::string> text = to_json();
		if (!text) {
			save_error = text.error();
			return;
		}
		save_error = write_atomic(file_, *text);
	} catch (const std::exception& e) {
		save_error = std::string(e.what());
	}
}

std::expected<void, std::string> DesktopStore::load(std::string_view text) {
	// Rust tells a text that parses to some other JSON value from one that does not parse.
	const auto parsed = json::parse_value(text);
	if (!parsed) {
		return std::unexpected(std::format("The data file does not read: {}", parsed.error()));
	}
	if (!parsed->is_object()) {
		return std::unexpected(std::string("The data file is not a JSON object."));
	}
	const json::Obj& root = *parsed;
	since_ = json::opt_i64(root, "since", 0);
	sc_rev_ = json::opt_i64(root, "scRev", 0);
	sc_dirty_ = json::opt_bool(root, "scDirty");
	sync_format_ = json::opt_i64(root, "syncFormat", 1);
	short_cut_list_.clear();
	if (const auto* sc = json::opt_array(root, "shortcuts")) {
		for (const nlohmann::json& s : *sc) {
			if (!s.is_object()) {
				return std::unexpected(std::string("A ShortCut is not an object."));
			}
			auto name = json::get_str(s, "name");
			if (!name) {
				return std::unexpected(name.error());
			}
			auto txt = json::get_str(s, "text");
			if (!txt) {
				return std::unexpected(txt.error());
			}
			short_cut_list_.emplace_back(*name, *txt);
		}
	}
	const auto* rows_arr = json::opt_array(root, "rows");
	if (rows_arr == nullptr) {
		return std::unexpected(std::string("The data file has no rows."));
	}
	std::vector<const nlohmann::json*> rows;
	for (const nlohmann::json& r : *rows_arr) {
		if (!r.is_object()) {
			return std::unexpected(std::string("A row is not an object."));
		}
		rows.push_back(&r);
	}
	// Categories load first, so that the other rows can map category uids to ids.
	std::stable_partition(rows.begin(), rows.end(), [](const nlohmann::json* r) {
		const nlohmann::json* t = field(*r, "table");
		return t != nullptr && t->is_string() && t->get<std::string>() == "categories";
	});
	for (const nlohmann::json* o : rows) {
		auto table = json::get_str(*o, "table");
		if (!table) {
			return std::unexpected(table.error());
		}
		const std::size_t t = table_index(*table);
		auto id = json::get_i64(*o, "id");
		if (!id) {
			return std::unexpected(id.error());
		}
		auto uid = json::get_str(*o, "uid");
		if (!uid) {
			return std::unexpected(uid.error());
		}
		const nlohmann::json* del = field(*o, "deleted");
		if (del == nullptr || !del->is_boolean()) {
			return std::unexpected(std::string("A row has no deleted flag."));
		}
		const nlohmann::json* dir = field(*o, "dirty");
		if (dir == nullptr || !dir->is_boolean()) {
			return std::unexpected(std::string("A row has no dirty flag."));
		}
		const bool deleted = del->get<bool>();
		const bool dirty = dir->get<bool>();
		auto rev = json::get_i64(*o, "rev");
		if (!rev) {
			return std::unexpected(rev.error());
		}
		meta_[t].insert_or_assign(*id, Meta{*uid, *rev, dirty, deleted});
		if (deleted) {
			continue;
		}
		auto plain = json::get_str(*o, "plain");
		if (!plain) {
			return std::unexpected(plain.error());
		}
		if (t == CATEGORIES) {
			auto c = codec::to_category(*plain, *uid, static_cast<std::int32_t>(*id));
			if (!c) {
				return std::unexpected(c.error());
			}
			categories_for_mut(c->app).push_back(*c);
		} else if (t == EVENTS) {
			auto e = codec::to_event(*plain, *uid);
			if (!e) {
				return std::unexpected(e.error());
			}
			e->id = *id;
			events.push_back(*e);
		} else if (t == TODOS) {
			auto x = codec::to_todo(*plain, *uid, [this](std::string_view u) { return category_id_for_uid(u, 0); });
			if (!x) {
				return std::unexpected(x.error());
			}
			x->id = *id;
			todos.push_back(*x);
		} else if (t == ADDRESSES) {
			auto a = codec::to_address(*plain, *uid, [this](std::string_view u) { return category_id_for_uid(u, 2); });
			if (!a) {
				return std::unexpected(a.error());
			}
			a->id = *id;
			addresses.push_back(*a);
		} else {
			auto m = codec::to_memo(*plain, *uid, [this](std::string_view u) { return category_id_for_uid(u, 1); });
			if (!m) {
				return std::unexpected(m.error());
			}
			m->id = *id;
			memos.push_back(*m);
		}
	}
	model::sort_categories(categories);
	model::sort_categories(memo_categories);
	model::sort_categories(address_categories);
	std::stable_sort(memos.begin(), memos.end(),
	                 [](const model::Memo& a, const model::Memo& b) { return a.pos < b.pos; });
	// Desktop 0.10 skipped the address rows but still moved the sync position past them. A file without the
	// addressSync key came from 0.10, so the next sync reads all rows again.
	if (!root.contains("addressSync")) {
		since_ = 0;
	}
	// A format 1 store has uploaded its rows in format 1. Upload every live row once more in format 2. The
	// ShortCuts list goes up too, but only when it synced before, so it never replaces another list with defaults.
	if (sync_format_ < 2) {
		for (auto& map : meta_) {
			for (auto& [id, m] : map) {
				if (!m.deleted) {
					m.dirty = true;
				}
			}
		}
		if (sc_rev_ > 0) {
			sc_dirty_ = true;
		}
		sync_format_ = 2;
	}
	return {};
}

std::optional<std::string> DesktopStore::plain_of(std::size_t t, std::int64_t id) const {
	std::optional<runner::Record> rec = live_record(t, id);
	if (!rec) {
		return std::nullopt;
	}
	if (const auto* e = std::get_if<model::Event>(&*rec)) {
		return codec::event(*e);
	}
	if (const auto* x = std::get_if<model::Todo>(&*rec)) {
		return codec::todo(*x, category_uid(x->category));
	}
	if (const auto* m = std::get_if<model::Memo>(&*rec)) {
		return codec::memo(*m, category_uid(m->category));
	}
	if (const auto* a = std::get_if<model::Address>(&*rec)) {
		return codec::address(*a, category_uid(a->category));
	}
	return codec::category(std::get<model::Category>(*rec));
}

// ---- helpers ----

void DesktopStore::changed() {
	persist();
	if (on_change) {
		on_change();
	}
}

void DesktopStore::add_default_categories() {
	struct Default {
		std::int32_t id;
		const char* name;
		std::int32_t app;
		const char* uid;
	};
	constexpr std::array<Default, 7> defaults = {{
	    {1, "Business", 0, "default-0-business"},
	    {2, "Personal", 0, "default-0-personal"},
	    {3, "Business", 1, "default-1-business"},
	    {4, "Personal", 1, "default-1-personal"},
	    {5, "Business", 2, "default-2-business"},
	    {6, "Personal", 2, "default-2-personal"},
	    {7, "QuickList", 2, "default-2-quicklist"},
	}};
	for (const Default& d : defaults) {
		categories_for_mut(d.app).push_back(model::Category{.id = d.id, .name = d.name, .app = d.app, .uid = d.uid});
		mark_dirty(CATEGORIES, d.id, d.uid);
	}
	model::sort_categories(categories);
	model::sort_categories(memo_categories);
	model::sort_categories(address_categories);
}

void DesktopStore::put_event(model::Event& e) {
	if (e.uid.empty()) {
		e.uid = new_uid();
	}
	if (e.id == 0) {
		e.id = next_id(EVENTS);
	}
	const std::int64_t id = e.id;
	put_in_list(events, e, [id](const model::Event& x) { return x.id == id; });
	mark_dirty(EVENTS, e.id, e.uid);
}

void DesktopStore::put_todo(model::Todo& t) {
	if (t.uid.empty()) {
		t.uid = new_uid();
	}
	if (t.id == 0) {
		t.id = next_id(TODOS);
	}
	const std::int64_t id = t.id;
	put_in_list(todos, t, [id](const model::Todo& x) { return x.id == id; });
	mark_dirty(TODOS, t.id, t.uid);
}

void DesktopStore::put_memo(model::Memo& m) {
	if (m.uid.empty()) {
		m.uid = new_uid();
	}
	if (m.id == 0) {
		std::optional<std::int32_t> best;
		for (const model::Memo& x : memos) {
			if (!best || x.pos + 1 > *best) {
				best = x.pos + 1;
			}
		}
		m.pos = best.value_or(0);
		m.id = next_id(MEMOS);
	}
	const std::int64_t id = m.id;
	put_in_list(memos, m, [id](const model::Memo& x) { return x.id == id; });
	mark_dirty(MEMOS, m.id, m.uid);
}

void DesktopStore::put_address(model::Address& a) {
	if (a.uid.empty()) {
		a.uid = new_uid();
	}
	if (a.id == 0) {
		a.id = next_id(ADDRESSES);
	}
	const std::int64_t id = a.id;
	put_in_list(addresses, a, [id](const model::Address& x) { return x.id == id; });
	mark_dirty(ADDRESSES, a.id, a.uid);
}

void DesktopStore::mark_dirty(std::size_t t, std::int64_t id, std::string_view uid) {
	Meta& m = meta_[t].try_emplace(id, Meta{std::string(uid), 0, true, false}).first->second;
	m.dirty = true;
	m.deleted = false;
}

void DesktopStore::remove_local(std::size_t t, std::int64_t id) {
	auto it = meta_[t].find(id);
	if (it == meta_[t].end()) {
		return;
	}
	if (it->second.rev == 0) {
		meta_[t].erase(it);
	} else {
		it->second.deleted = true;
		it->second.dirty = true;
	}
}

void DesktopStore::set_server(std::size_t t, std::int64_t id, std::string_view uid, std::int64_t rev) {
	meta_[t].insert_or_assign(id, Meta{std::string(uid), rev, false, false});
}

void DesktopStore::move_to_unfiled(std::int32_t id, bool dirty) {
	std::vector<std::tuple<std::size_t, std::int64_t, std::string>> marks;
	for (model::Todo& t : todos) {
		if (t.category == id) {
			t.category = 0;
			marks.emplace_back(TODOS, t.id, t.uid);
		}
	}
	for (model::Memo& m : memos) {
		if (m.category == id) {
			m.category = 0;
			marks.emplace_back(MEMOS, m.id, m.uid);
		}
	}
	for (model::Address& a : addresses) {
		if (a.category == id) {
			a.category = 0;
			marks.emplace_back(ADDRESSES, a.id, a.uid);
		}
	}
	if (dirty) {
		for (const auto& [t, rid, uid] : marks) {
			mark_dirty(t, rid, uid);
		}
	}
}

std::optional<runner::Record> DesktopStore::live_record(std::size_t t, std::int64_t id) const {
	if (t == CATEGORIES) {
		const model::Category* c = find_category(id);
		if (c == nullptr) {
			return std::nullopt;
		}
		return runner::Record(*c);
	}
	if (t == EVENTS) {
		for (const model::Event& e : events) {
			if (e.id == id) {
				return runner::Record(e);
			}
		}
		return std::nullopt;
	}
	if (t == TODOS) {
		for (const model::Todo& x : todos) {
			if (x.id == id) {
				return runner::Record(x);
			}
		}
		return std::nullopt;
	}
	if (t == ADDRESSES) {
		for (const model::Address& a : addresses) {
			if (a.id == id) {
				return runner::Record(a);
			}
		}
		return std::nullopt;
	}
	for (const model::Memo& m : memos) {
		if (m.id == id) {
			return runner::Record(m);
		}
	}
	return std::nullopt;
}

std::optional<std::int64_t> DesktopStore::id_of(std::size_t t, std::string_view uid) const {
	for (const auto& [id, m] : meta_[t]) {
		if (m.uid == uid) {
			return id;
		}
	}
	return std::nullopt;
}

std::int64_t DesktopStore::id_for(std::size_t t, std::string_view uid) const {
	std::optional<std::int64_t> id = id_of(t, uid);
	return id ? *id : next_id(t);
}

std::int64_t DesktopStore::next_id(std::size_t t) const {
	return meta_[t].empty() ? 1 : meta_[t].rbegin()->first + 1;
}

// ---- SyncStore ----

std::vector<runner::SyncRow> DesktopStore::dirty_rows() const {
	std::vector<runner::SyncRow> out;
	for (std::size_t t = 0; t < TABLES.size(); ++t) {
		for (const auto& [id, m] : meta_[t]) {
			if (m.dirty) {
				out.push_back(row_of(t, id, m.uid, m.rev, m.dirty, m.deleted));
			}
		}
	}
	return out;
}

std::optional<runner::SyncRow> DesktopStore::sync_row(std::string_view uid) const {
	for (std::size_t t = 0; t < TABLES.size(); ++t) {
		if (std::optional<std::int64_t> id = id_of(t, uid)) {
			const Meta& m = meta_[t].at(*id);
			return row_of(t, *id, m.uid, m.rev, m.dirty, m.deleted);
		}
	}
	return std::nullopt;
}

std::optional<runner::Record> DesktopStore::record(const runner::SyncRow& row) const {
	if (row.deleted) {
		return std::nullopt;
	}
	return live_record(table_index(row.table), row.id);
}

std::string DesktopStore::category_uid(std::int32_t id) const {
	if (id == 0) {
		return {};
	}
	const model::Category* c = find_category(id);
	return c != nullptr ? c->uid : std::string();
}

std::int32_t DesktopStore::category_id_for_uid(std::string_view uid, std::int32_t app) const {
	if (model::is_blank(uid)) {
		return 0;
	}
	for (const model::Category& c : categories_for(app)) {
		if (c.uid == uid) {
			return c.id;
		}
	}
	return 0;
}

void DesktopStore::mark_synced(std::string_view table, std::string_view uid, std::int64_t rev, bool clean) {
	const std::size_t t = table_index(table);
	std::optional<std::int64_t> id = id_of(t, uid);
	if (!id) {
		return;
	}
	Meta& m = meta_[t].at(*id);
	if (clean && m.deleted) {
		meta_[t].erase(*id);
	} else {
		m.rev = rev;
		if (clean) {
			m.dirty = false;
		}
	}
	persist();
}

void DesktopStore::put_remote(runner::Record record, std::int64_t rev) {
	if (auto* e = std::get_if<model::Event>(&record)) {
		e->id = id_for(EVENTS, e->uid);
		set_server(EVENTS, e->id, e->uid, rev);
		const std::int64_t id = e->id;
		put_in_list(events, *e, [id](const model::Event& x) { return x.id == id; });
	} else if (auto* x = std::get_if<model::Todo>(&record)) {
		x->id = id_for(TODOS, x->uid);
		set_server(TODOS, x->id, x->uid, rev);
		const std::int64_t id = x->id;
		put_in_list(todos, *x, [id](const model::Todo& y) { return y.id == id; });
	} else if (auto* m = std::get_if<model::Memo>(&record)) {
		m->id = id_for(MEMOS, m->uid);
		set_server(MEMOS, m->id, m->uid, rev);
		const std::int64_t id = m->id;
		put_in_list(memos, *m, [id](const model::Memo& y) { return y.id == id; });
	} else if (auto* a = std::get_if<model::Address>(&record)) {
		a->id = id_for(ADDRESSES, a->uid);
		set_server(ADDRESSES, a->id, a->uid, rev);
		const std::int64_t id = a->id;
		put_in_list(addresses, *a, [id](const model::Address& y) { return y.id == id; });
	} else {
		const auto& c = std::get<model::Category>(record);
		const auto id = static_cast<std::int32_t>(id_for(CATEGORIES, c.uid));
		const std::string uid = c.uid;
		std::erase_if(categories, [&uid](const model::Category& x) { return x.uid == uid; });
		std::erase_if(memo_categories, [&uid](const model::Category& x) { return x.uid == uid; });
		std::erase_if(address_categories, [&uid](const model::Category& x) { return x.uid == uid; });
		set_server(CATEGORIES, id, uid, rev);
		std::vector<model::Category>& list = categories_for_mut(c.app);
		model::Category cat = c;
		cat.id = id;
		list.push_back(cat);
		model::sort_categories(list);
	}
	persist();
}

void DesktopStore::drop_remote(std::string_view table, std::string_view uid) {
	const std::size_t t = table_index(table);
	std::optional<std::int64_t> found = id_of(t, uid);
	if (!found) {
		return;
	}
	const std::int64_t id = *found;
	meta_[t].erase(id);
	if (t == CATEGORIES) {
		const auto cid = static_cast<std::int32_t>(id);
		std::erase_if(categories, [cid](const model::Category& c) { return c.id == cid; });
		std::erase_if(memo_categories, [cid](const model::Category& c) { return c.id == cid; });
		std::erase_if(address_categories, [cid](const model::Category& c) { return c.id == cid; });
		move_to_unfiled(cid, false);
	} else if (t == EVENTS) {
		std::erase_if(events, [id](const model::Event& e) { return e.id == id; });
	} else if (t == TODOS) {
		std::erase_if(todos, [id](const model::Todo& x) { return x.id == id; });
	} else if (t == ADDRESSES) {
		std::erase_if(addresses, [id](const model::Address& a) { return a.id == id; });
	} else {
		std::erase_if(memos, [id](const model::Memo& m) { return m.id == id; });
	}
	persist();
}

void DesktopStore::save_copy(runner::Record record) {
	if (auto* e = std::get_if<model::Event>(&record)) {
		e->id = 0;
		e->uid.clear();
		put_event(*e);
	} else if (auto* x = std::get_if<model::Todo>(&record)) {
		x->id = 0;
		x->uid.clear();
		put_todo(*x);
	} else if (auto* m = std::get_if<model::Memo>(&record)) {
		m->id = 0;
		m->uid.clear();
		put_memo(*m);
	} else if (auto* a = std::get_if<model::Address>(&record)) {
		a->id = 0;
		a->uid.clear();
		put_address(*a);
	} else {
		const auto& c = std::get<model::Category>(record);
		new_category(c.name, c.app);
	}
	persist();
}

codec::ShortCuts DesktopStore::short_cuts() const {
	return short_cut_list_;
}

bool DesktopStore::short_cuts_dirty() const {
	return sc_dirty_;
}

std::int64_t DesktopStore::short_cuts_rev() const {
	return sc_rev_;
}

void DesktopStore::set_short_cuts_rev(std::int64_t rev) {
	sc_rev_ = rev;
	persist();
}

void DesktopStore::set_short_cuts_from_sync(codec::ShortCuts list, std::int64_t rev) {
	short_cut_list_ = std::move(list);
	sc_rev_ = rev;
	sc_dirty_ = false;
	persist();
}

std::int64_t DesktopStore::sync_since() const {
	return since_;
}

void DesktopStore::set_sync_since(std::int64_t rev) {
	since_ = rev;
	persist();
}

} // namespace palm::store
