// An in-memory SyncStore for the round and server tests, as FakeStore in SyncRunnerTest.kt. Rows keep
// their insertion order; a category from the server gets the next free local id.
#pragma once

#include "palm/codec.hpp"
#include "palm/model.hpp"
#include "palm/runner.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace mem {

using Record = palm::runner::Record;

// One row of the store. Rust keeps the table as &'static str; a string_view of a literal does the same here.
struct Entry {
	std::string_view table;
	std::string uid;
	std::int64_t rev = 0;
	bool dirty = false;
	bool deleted = false;
	Record rec;
};

// The sync tables in push order, as SyncRunner sorts the dirty rows.
inline constexpr std::array<std::string_view, 5> TABLES = {"categories", "events", "todos", "memos", "addresses"};

// The position of a table in TABLES.
inline std::size_t table_position(std::string_view table) {
	return static_cast<std::size_t>(std::find(TABLES.begin(), TABLES.end(), table) - TABLES.begin());
}

// The table name of a record.
inline std::string_view table_of(const Record& rec) {
	if (std::holds_alternative<palm::model::Event>(rec)) {
		return "events";
	}
	if (std::holds_alternative<palm::model::Todo>(rec)) {
		return "todos";
	}
	if (std::holds_alternative<palm::model::Memo>(rec)) {
		return "memos";
	}
	if (std::holds_alternative<palm::model::Address>(rec)) {
		return "addresses";
	}
	return "categories";
}

// The record with its uid set to `uid`.
inline Record with_uid(Record rec, std::string_view uid) {
	std::visit([uid](auto& x) { x.uid = std::string(uid); }, rec);
	return rec;
}

// The sync row of a store entry.
inline palm::runner::SyncRow row(const Entry& e) {
	return palm::runner::SyncRow{std::string(e.table), 0, e.uid, e.rev, e.dirty, e.deleted};
}

// New uids for every store of the test process.
inline std::uint32_t next_uid() {
	static std::atomic<std::uint32_t> next{1};
	return next.fetch_add(1, std::memory_order_relaxed);
}

// The Rust fields short_cuts, short_cuts_dirty, short_cuts_rev, and sync_since are named shortcuts,
// shortcuts_dirty, shortcuts_rev, and since here, because a C++ member cannot share the name of a member
// function. The SyncStore methods keep their names.
class MemStore final : public palm::runner::SyncStore {
public:
	std::vector<Entry> rows;
	std::vector<Record> copies;
	palm::codec::ShortCuts shortcuts;
	bool shortcuts_dirty = false;
	std::int64_t shortcuts_rev = 0;
	std::int64_t since = 0;

	// The first row with this uid, or nullptr.
	const Entry* entry(std::string_view uid) const {
		for (const Entry& e : rows) {
			if (e.uid == uid) {
				return &e;
			}
		}
		return nullptr;
	}

	// The first row with this uid, or nullptr.
	Entry* entry_mut(std::string_view uid) {
		for (Entry& e : rows) {
			if (e.uid == uid) {
				return &e;
			}
		}
		return nullptr;
	}

	// Adds a row with this uid, as a local edit unless `dirty` is false.
	void add(std::string_view uid, Record rec, std::int64_t rev, bool dirty, bool deleted) {
		Record stamped = with_uid(std::move(rec), uid);
		std::erase_if(rows, [uid](const Entry& e) { return e.uid == uid; });
		const std::string_view table = table_of(stamped);
		rows.push_back(Entry{table, std::string(uid), rev, dirty, deleted, std::move(stamped)});
	}

	// A new local record with a new uid.
	std::string create(Record rec) {
		const std::string uid = "local-" + std::to_string(next_uid());
		add(uid, std::move(rec), 0, true, false);
		return uid;
	}

	// Changes a record locally, as an edit in the app.
	template <typename Edit> void edit(std::string_view uid, Edit f) {
		Entry* e = entry_mut(uid);
		if (e == nullptr) {
			throw std::runtime_error("no such row");
		}
		f(e->rec);
		e->dirty = true;
	}

	// Deletes a record locally: it stays as a dirty tombstone until sync pushes it. Named delete_row,
	// because delete is a C++ keyword.
	void delete_row(std::string_view uid) {
		Entry* e = entry_mut(uid);
		if (e == nullptr) {
			throw std::runtime_error("no such row");
		}
		e->deleted = true;
		e->dirty = true;
	}

	// The live records of a table, in row order.
	std::vector<const Record*> live(std::string_view table) const {
		std::vector<const Record*> out;
		for (const Entry& e : rows) {
			if (e.table == table && !e.deleted) {
				out.push_back(&e.rec);
			}
		}
		return out;
	}

	// One more than the largest category id, or 1 when there is no category.
	std::int32_t next_category_id() const {
		std::optional<std::int32_t> best;
		for (const Entry& e : rows) {
			const auto* c = std::get_if<palm::model::Category>(&e.rec);
			if (c != nullptr && (!best || c->id > *best)) {
				best = c->id;
			}
		}
		return 1 + best.value_or(0);
	}

	// Dirty rows of the sync tables, categories first; the sort is stable.
	std::vector<palm::runner::SyncRow> dirty_rows() const override {
		std::vector<const Entry*> dirty;
		for (const Entry& e : rows) {
			if (e.dirty) {
				dirty.push_back(&e);
			}
		}
		std::stable_sort(dirty.begin(), dirty.end(), [](const Entry* a, const Entry* b) {
			return table_position(a->table) < table_position(b->table);
		});
		std::vector<palm::runner::SyncRow> out;
		for (const Entry* e : dirty) {
			out.push_back(row(*e));
		}
		return out;
	}

	std::optional<palm::runner::SyncRow> sync_row(std::string_view uid) const override {
		const Entry* e = entry(uid);
		if (e == nullptr) {
			return std::nullopt;
		}
		return row(*e);
	}

	std::optional<Record> record(const palm::runner::SyncRow& r) const override {
		const Entry* e = entry(r.uid);
		if (e == nullptr || e->deleted) {
			return std::nullopt;
		}
		return e->rec;
	}

	std::string category_uid(std::int32_t id) const override {
		if (id == 0) {
			return {};
		}
		for (const Entry& e : rows) {
			const auto* c = std::get_if<palm::model::Category>(&e.rec);
			if (!e.deleted && c != nullptr && c->id == id) {
				return e.uid;
			}
		}
		return {};
	}

	std::int32_t category_id_for_uid(std::string_view uid, std::int32_t app) const override {
		const Entry* e = entry(uid);
		if (e == nullptr || e->deleted) {
			return 0;
		}
		const auto* c = std::get_if<palm::model::Category>(&e->rec);
		if (c != nullptr && c->app == app) {
			return c->id;
		}
		return 0;
	}

	// A tombstone that the server took goes away, as in the app stores.
	void mark_synced(std::string_view, std::string_view uid, std::int64_t rev, bool clean) override {
		if (Entry* e = entry_mut(uid)) {
			e->rev = rev;
			e->dirty = !clean;
		}
		std::erase_if(rows, [uid, clean](const Entry& e) { return e.uid == uid && e.deleted && clean; });
	}

	// Saves the server copy of a record. A category keeps the local id of its row, or takes the next free id.
	void put_remote(Record rec, std::int64_t rev) override {
		const std::string uid = std::visit([](const auto& x) { return x.uid; }, rec);
		if (auto* c = std::get_if<palm::model::Category>(&rec)) {
			const Entry* old = entry(uid);
			const auto* old_cat = old != nullptr ? std::get_if<palm::model::Category>(&old->rec) : nullptr;
			c->id = old_cat != nullptr ? old_cat->id : next_category_id();
		}
		add(uid, std::move(rec), rev, false, false);
	}

	void drop_remote(std::string_view, std::string_view uid) override {
		std::erase_if(rows, [uid](const Entry& e) { return e.uid == uid; });
	}

	// Keeps a copy as a new dirty row with the uid copy-N, where N counts the copies.
	void save_copy(Record rec) override {
		const std::string uid = "copy-" + std::to_string(copies.size() + 1);
		if (auto* c = std::get_if<palm::model::Category>(&rec)) {
			c->id = next_category_id();
		}
		copies.push_back(rec);
		add(uid, std::move(rec), 0, true, false);
	}

	palm::codec::ShortCuts short_cuts() const override { return shortcuts; }

	bool short_cuts_dirty() const override { return shortcuts_dirty; }

	std::int64_t short_cuts_rev() const override { return shortcuts_rev; }

	void set_short_cuts_rev(std::int64_t rev) override { shortcuts_rev = rev; }

	void set_short_cuts_from_sync(palm::codec::ShortCuts list, std::int64_t rev) override {
		shortcuts = std::move(list);
		shortcuts_rev = rev;
		shortcuts_dirty = false;
	}

	std::int64_t sync_since() const override { return since; }

	void set_sync_since(std::int64_t rev) override { since = rev; }
};

} // namespace mem
