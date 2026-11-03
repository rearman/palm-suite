// The Palm Desktop record store, as desktop-rs/app/src/store.rs (DesktopStore.kt). It keeps the records in memory
// and saves them to one JSON file (desktop-data.json, the same format as the Swing app) after each write. A synced
// record that was deleted stays as a tombstone in the sync state until sync removes it; tombstones are not in the
// record lists.
#pragma once

#include "palm/codec.hpp"
#include "palm/model.hpp"
#include "palm/runner.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace palm::store {

// The name of the data file in the store folder.
inline constexpr std::string_view FILE_NAME = "desktop-data.json";

// The lock of the store folder. It holds the OS lock until it is destroyed, also when the app crashes.
class LockFile {
public:
	LockFile(LockFile&& other) noexcept;
	LockFile& operator=(LockFile&& other) noexcept;
	LockFile(const LockFile&) = delete;
	LockFile& operator=(const LockFile&) = delete;
	~LockFile();

private:
	friend std::expected<LockFile, std::string> lock(const std::filesystem::path& dir);
	LockFile() = default;
	void reset() noexcept;
#ifdef _WIN32
	// The HANDLE of desktop.lock.
	void* handle_ = nullptr;
#else
	// The file descriptor of desktop.lock.
	int fd_ = -1;
#endif
};

// A new random uid in the form of Java's UUID.randomUUID().
std::string new_uid();

// Takes the lock of the store folder, so a second copy of the app does not overwrite the data file. The Swing app
// takes no lock.
std::expected<LockFile, std::string> lock(const std::filesystem::path& dir);

class DesktopStore : public runner::SyncStore {
public:
	// Loads the store from `file`. A missing file gives a new store with the default categories.
	static std::expected<DesktopStore, std::string> open(const std::filesystem::path& file);

	// ---- lookups ----

	// The categories of `app`: 0 To Do, 1 Memo Pad, 2 Address Book.
	const std::vector<model::Category>& categories_for(std::int32_t app) const;

	// The name of the category `id`, or "Unfiled".
	std::string category_name(std::int32_t id) const;

	// Occurrences on one day: untimed first, then by start time, then by end time.
	std::vector<const model::Event*> day_events(std::int64_t day) const;

	// Events, To Dos, Memos and Addresses whose text contains `text`, ignoring case.
	std::vector<runner::Record> find(std::string_view text) const;

	// ---- local writes ----

	// Saves a new or changed event. A new one gets its id and uid here.
	void save_event(model::Event& e);
	void save_todo(model::Todo& t);
	void save_memo(model::Memo& m);
	void save_address(model::Address& a);

	void delete_event(std::int64_t id);
	void delete_todo(std::int64_t id);
	void delete_memo(std::int64_t id);
	void delete_address(std::int64_t id);

	// Adds a category for `app` and saves. Returns the new category.
	model::Category add_category(std::string_view name, std::int32_t app);

	void rename_category(std::int32_t app, std::int32_t id, std::string_view name);

	// Deletes a category. Its items move to Unfiled, and they are marked dirty.
	void delete_category(std::int32_t app, std::int32_t id);

	// Deletes the events that ended before `before`. Returns the count.
	std::size_t purge_events(std::int64_t before);

	// Deletes the completed To Do items. Returns the count.
	std::size_t purge_todos();

	// Replaces the ShortCuts list after a local edit. The list syncs on the next run.
	void set_short_cuts(codec::ShortCuts list);

	// ---- the file ----

	// Writes the whole store: a temp file, then a move over the old file. A failure keeps the old file and goes to
	// save_error.
	void persist();

	// ---- public state, as the Rust fields ----

	std::vector<model::Event> events;
	std::vector<model::Todo> todos;
	std::vector<model::Memo> memos;
	std::vector<model::Address> addresses;
	// To Do (app 0).
	std::vector<model::Category> categories;
	// Memo Pad (app 1).
	std::vector<model::Category> memo_categories;
	// Address Book (app 2).
	std::vector<model::Category> address_categories;
	// Called after every local write. The SyncStore writes do not call it.
	std::function<void()> on_change;
	// The error of the last failed save, for the app to show. A later good save clears it.
	std::optional<std::string> save_error;
	// Counts the writes. The app reads the store again in its views when the count moves.
	std::uint64_t version = 0;

	// ---- SyncStore ----

	std::vector<runner::SyncRow> dirty_rows() const override;
	std::optional<runner::SyncRow> sync_row(std::string_view uid) const override;
	std::optional<runner::Record> record(const runner::SyncRow& row) const override;
	std::string category_uid(std::int32_t id) const override;
	std::int32_t category_id_for_uid(std::string_view uid, std::int32_t app) const override;
	void mark_synced(std::string_view table, std::string_view uid, std::int64_t rev, bool clean) override;
	void put_remote(runner::Record record, std::int64_t rev) override;
	void drop_remote(std::string_view table, std::string_view uid) override;
	void save_copy(runner::Record record) override;
	codec::ShortCuts short_cuts() const override;
	bool short_cuts_dirty() const override;
	std::int64_t short_cuts_rev() const override;
	void set_short_cuts_rev(std::int64_t rev) override;
	void set_short_cuts_from_sync(codec::ShortCuts list, std::int64_t rev) override;
	std::int64_t sync_since() const override;
	void set_sync_since(std::int64_t rev) override;

private:
	// Sync state of one record. `deleted` marks a tombstone.
	struct Meta {
		std::string uid;
		std::int64_t rev = 0;
		bool dirty = false;
		bool deleted = false;
	};

	explicit DesktopStore(std::filesystem::path file) : file_(std::move(file)) {}

	std::vector<model::Category>& categories_for_mut(std::int32_t app);
	// The first category of any app with this id, or nullptr.
	const model::Category* find_category(std::int64_t id) const;
	// Saves after a local write: the file, then on_change.
	void changed();
	void add_default_categories();
	// Adds a dirty category and does not save.
	model::Category new_category(std::string_view name, std::int32_t app);
	void put_event(model::Event& e);
	void put_todo(model::Todo& t);
	void put_memo(model::Memo& m);
	void put_address(model::Address& a);
	// Marks a local write: the row is dirty and live.
	void mark_dirty(std::size_t t, std::int64_t id, std::string_view uid);
	// Deletes a row that never reached the server. A synced row becomes a tombstone.
	void remove_local(std::size_t t, std::int64_t id);
	// Stores the sync state of a server copy: clean, live, at `rev`.
	void set_server(std::size_t t, std::int64_t id, std::string_view uid, std::int64_t rev);
	// Moves the items of category `id` to Unfiled. `dirty` is true for a local delete only.
	void move_to_unfiled(std::int32_t id, bool dirty);
	std::optional<runner::Record> live_record(std::size_t t, std::int64_t id) const;
	std::optional<std::int64_t> id_of(std::size_t t, std::string_view uid) const;
	// The local id of `uid`, or a new id when this device has not seen the uid.
	std::int64_t id_for(std::size_t t, std::string_view uid) const;
	// One more than the highest id of the table, tombstones included.
	std::int64_t next_id(std::size_t t) const;
	// The JSON text of the whole store, as DesktopStore.persist writes it.
	std::expected<std::string, std::string> to_json() const;
	std::optional<std::string> plain_of(std::size_t t, std::int64_t id) const;
	std::expected<void, std::string> load(std::string_view text);

	std::filesystem::path file_;
	// Sync state by table, then by local id.
	std::array<std::map<std::int64_t, Meta>, 5> meta_;
	codec::ShortCuts short_cut_list_;
	std::int64_t since_ = 0;
	std::int64_t sc_rev_ = 0;
	bool sc_dirty_ = false;
	// The sync format that persist writes. A file without the key is format 1.
	std::int64_t sync_format_ = 2;
};

} // namespace palm::store
