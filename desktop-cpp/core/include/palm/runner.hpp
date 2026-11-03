// One sync round over a SyncStore, as desktop-rs/core/src/runner.rs (SyncRunner.kt). The wire format is in
// sync-server/PROTOCOL.md.
//
// Keep-both rule: a record that changed here and on the server is kept twice. The local copy becomes a new
// record with its own uid, and the server copy takes the place of the original. ShortCuts do not follow this
// rule: the last writer wins.
#pragma once

#include "palm/client.hpp"
#include "palm/codec.hpp"
#include "palm/model.hpp"

#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <variant>
#include <vector>

namespace palm::runner {

// One row of a sync table. A tombstone has deleted = true and no live record.
struct SyncRow {
	std::string table;
	std::int64_t id = 0;
	std::string uid;
	std::int64_t rev = 0;
	bool dirty = false;
	bool deleted = false;
	bool operator==(const SyncRow&) const = default;
};

// The live record of a row. A round moves few records, so the Address size does not need a Box.
// The alternatives are the Rust variants Event, Todo, Memo, Address, and Category, in that order.
using Record = std::variant<model::Event, model::Todo, model::Memo, model::Address, model::Category>;

// The local data that one sync round reads and writes. Each app implements it over its own store.
class SyncStore {
public:
	virtual ~SyncStore() = default;

	// Dirty rows of "categories", "events", "todos", "memos", "addresses", categories first.
	virtual std::vector<SyncRow> dirty_rows() const = 0;
	// Any table, live or tombstone.
	virtual std::optional<SyncRow> sync_row(std::string_view uid) const = 0;
	// The live record of the row, or nullopt.
	virtual std::optional<Record> record(const SyncRow& row) const = 0;
	// "" for 0 or unknown.
	virtual std::string category_uid(std::int32_t id) const = 0;
	// 0 when blank or unknown.
	virtual std::int32_t category_id_for_uid(std::string_view uid, std::int32_t app) const = 0;
	virtual void mark_synced(std::string_view table, std::string_view uid, std::int64_t rev, bool clean) = 0;
	// Saves the server copy of a record; its uid is in the record.
	virtual void put_remote(Record record, std::int64_t rev) = 0;
	virtual void drop_remote(std::string_view table, std::string_view uid) = 0;
	// Keep-both: saves the record as a new dirty record with a new uid.
	virtual void save_copy(Record record) = 0;
	virtual codec::ShortCuts short_cuts() const = 0;
	virtual bool short_cuts_dirty() const = 0;
	virtual std::int64_t short_cuts_rev() const = 0;
	virtual void set_short_cuts_rev(std::int64_t rev) = 0;
	virtual void set_short_cuts_from_sync(codec::ShortCuts list, std::int64_t rev) = 0;
	virtual std::int64_t sync_since() const = 0;
	virtual void set_sync_since(std::int64_t rev) = 0;
};

// The push items of one round, with the plaintext of each pushed row (nullopt for a delete).
struct SyncPlan {
	std::vector<client::PushItem> items;
	std::unordered_map<std::string, std::optional<std::string>> pushed;
	std::int64_t since = 0;
};

// What applying one server answer changed.
struct SyncOutcome {
	bool no_conflicts = false;
	bool changed = false;
	bool short_cuts_changed = false;
	bool operator==(const SyncOutcome&) const = default;
};

// Each run stops after this many rounds. A later run finishes what is left.
inline constexpr std::uint32_t MAX_ROUNDS = 5;
inline constexpr std::string_view DECRYPT_MESSAGE = "A record could not be decrypted. Check the passphrase.";

// Step A: builds the push items from the dirty rows and the ShortCuts list.
std::expected<SyncPlan, std::string> collect(const SyncStore& store, std::span<const std::uint8_t> key);

// Steps C.1 to C.4. Fails with a message for the user when a live change cannot be decrypted. A bad key
// changes nothing, because every live change is decrypted before any change is applied.
std::expected<SyncOutcome, std::string> apply(SyncStore& store, std::span<const std::uint8_t> key, const SyncPlan& plan,
                                              const client::SyncResult& result);

} // namespace palm::runner
