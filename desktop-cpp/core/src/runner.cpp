// The sync round logic of runner.hpp, as desktop-rs/core/src/runner.rs.
#include "palm/runner.hpp"

#include "palm/crypto.hpp"
#include "palm/logic.hpp"

#include <algorithm>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <variant>
#include <vector>

namespace palm::runner {
namespace {

// The uid of the ShortCuts list on the server.
constexpr std::string_view SHORTCUTS_ID = "shortcuts";

// The text of a record in the format of the codec. A category has no category uid.
std::string encode(const SyncStore& store, const Record& rec) {
	if (const auto* e = std::get_if<model::Event>(&rec)) {
		return codec::event(*e);
	}
	if (const auto* t = std::get_if<model::Todo>(&rec)) {
		return codec::todo(*t, store.category_uid(t->category));
	}
	if (const auto* m = std::get_if<model::Memo>(&rec)) {
		return codec::memo(*m, store.category_uid(m->category));
	}
	if (const auto* a = std::get_if<model::Address>(&rec)) {
		return codec::address(*a, store.category_uid(a->category));
	}
	return codec::category(std::get<model::Category>(rec));
}

// Seals the text of one record. The bytes sealed are the packed plaintext.
std::expected<std::string, std::string> seal(std::span<const std::uint8_t> key, std::string_view id,
                                             std::string_view plain) {
	const std::vector<std::uint8_t> packed = crypto::plain_bytes::pack(plain);
	return crypto::seal(key, id, packed);
}

// The plaintext that the plan pushed for a uid, or nullopt when nothing was pushed or it was a delete.
std::optional<std::string> pushed_of(const SyncPlan& plan, std::string_view uid) {
	const auto it = plan.pushed.find(std::string(uid));
	if (it == plan.pushed.end()) {
		return std::nullopt;
	}
	return it->second;
}

// The record of a parsed blob, or nullopt when the parse failed.
template <typename T> std::optional<Record> as_record(std::expected<T, std::string>&& parsed) {
	if (!parsed) {
		return std::nullopt;
	}
	return Record(std::move(*parsed));
}

// Step C.2 check: true when the two records are of one kind and have the same content.
template <typename T> bool same_key(const Record& local, const Record& remote) {
	const T* a = std::get_if<T>(&local);
	const T* b = std::get_if<T>(&remote);
	return a != nullptr && b != nullptr && a->dup_key() == b->dup_key();
}

// Step C.2 check: true when the local record has the same content as the server copy.
bool same_content(const Record& local, const Record& remote) {
	if (same_key<model::Event>(local, remote) || same_key<model::Todo>(local, remote) ||
	    same_key<model::Memo>(local, remote) || same_key<model::Address>(local, remote)) {
		return true;
	}
	const auto* a = std::get_if<model::Category>(&local);
	const auto* b = std::get_if<model::Category>(&remote);
	return a != nullptr && b != nullptr && a->name == b->name && a->app == b->app;
}

// The server copy of a record, with the local ids of its categories.
std::optional<Record> remote_record(const SyncStore& store, std::string_view kind, std::string_view text,
                                    std::string_view uid) {
	if (kind == "event") {
		return as_record(codec::to_event(text, uid));
	}
	if (kind == "todo") {
		return as_record(
		    codec::to_todo(text, uid, [&store](std::string_view u) { return store.category_id_for_uid(u, 0); }));
	}
	if (kind == "memo") {
		return as_record(
		    codec::to_memo(text, uid, [&store](std::string_view u) { return store.category_id_for_uid(u, 1); }));
	}
	if (kind == "address") {
		return as_record(
		    codec::to_address(text, uid, [&store](std::string_view u) { return store.category_id_for_uid(u, 2); }));
	}
	if (kind == "category") {
		return as_record(codec::to_category(text, uid, 0));
	}
	return std::nullopt;
}

// Step C.3 for the ShortCuts list. True when the list was applied.
bool apply_short_cuts(SyncStore& store, const client::Change& c, std::string_view text) {
	const logic::LocalState local = store.short_cuts_dirty() ? logic::LocalState::Dirty : logic::LocalState::Clean;
	switch (logic::decide(local, c.deleted, false, true)) {
	case logic::Action::Apply: {
		std::expected<codec::ShortCuts, std::string> list = codec::to_shortcuts(text);
		if (!list) {
			return false;
		}
		store.set_short_cuts_from_sync(std::move(*list), c.rev);
		return true;
	}
	case logic::Action::Rebase:
		store.set_short_cuts_rev(c.rev);
		return false;
	default:
		return false;
	}
}

// Step C.3 for one record change. True when a record changed.
bool apply_change(SyncStore& store, const client::Change& c, std::string_view text) {
	const std::optional<SyncRow> row = store.sync_row(c.id);
	logic::LocalState local = logic::LocalState::Clean;
	if (!row) {
		local = logic::LocalState::None;
	} else if (row->deleted) {
		local = logic::LocalState::Tomb;
	} else if (row->dirty) {
		local = logic::LocalState::Dirty;
	}
	const std::string kind = c.deleted ? std::string() : codec::kind(text);
	if (!c.deleted && kind.empty()) {
		return false; // unknown kind: skip
	}
	const std::optional<Record> mine = row ? store.record(*row) : std::nullopt;
	const std::optional<Record> remote = c.deleted ? std::nullopt : remote_record(store, kind, text, c.id);
	const bool same = local == logic::LocalState::Dirty && !c.deleted && mine && remote && same_content(*mine, *remote);

	const auto put = [&store, &remote, &c]() {
		if (!remote) {
			return false;
		}
		store.put_remote(*remote, c.rev);
		return true;
	};
	const auto drop = [&store, &row, &c]() {
		if (!row) {
			return false;
		}
		store.drop_remote(row->table, c.id);
		return true;
	};
	const auto copy = [&store, &mine]() {
		if (!mine) {
			return false;
		}
		store.save_copy(*mine);
		return true;
	};
	switch (logic::decide(local, c.deleted, same, false)) {
	case logic::Action::Skip:
	case logic::Action::Rebase:
		return false;
	case logic::Action::Apply:
		return put();
	case logic::Action::CopyThenApply: {
		const bool copied = copy();
		return put() || copied;
	}
	case logic::Action::CopyThenDrop: {
		const bool copied = copy();
		return drop() || copied;
	}
	case logic::Action::Drop:
		return drop();
	}
	return false;
}

} // namespace

std::expected<SyncPlan, std::string> collect(const SyncStore& store, std::span<const std::uint8_t> key) {
	SyncPlan plan;
	for (const SyncRow& row : store.dirty_rows()) {
		if (row.deleted) {
			plan.items.push_back(client::PushItem{
			    .id = row.uid,
			    .base_rev = row.rev,
			    .deleted = true,
			    .blob = std::string(),
			});
			plan.pushed[row.uid] = std::nullopt;
			continue;
		}
		const std::optional<Record> rec = store.record(row);
		if (!rec) {
			continue;
		}
		const std::string plain = encode(store, *rec);
		std::expected<std::string, std::string> blob = seal(key, row.uid, plain);
		if (!blob) {
			return std::unexpected(blob.error());
		}
		plan.items.push_back(client::PushItem{
		    .id = row.uid,
		    .base_rev = row.rev,
		    .deleted = false,
		    .blob = std::move(*blob),
		});
		plan.pushed[row.uid] = plain;
	}
	if (store.short_cuts_dirty()) {
		const std::string plain = codec::shortcuts(store.short_cuts());
		std::expected<std::string, std::string> blob = seal(key, SHORTCUTS_ID, plain);
		if (!blob) {
			return std::unexpected(blob.error());
		}
		plan.items.push_back(client::PushItem{
		    .id = std::string(SHORTCUTS_ID),
		    .base_rev = store.short_cuts_rev(),
		    .deleted = false,
		    .blob = std::move(*blob),
		});
		plan.pushed[std::string(SHORTCUTS_ID)] = plain;
	}
	plan.since = store.sync_since();
	return plan;
}

std::expected<SyncOutcome, std::string> apply(SyncStore& store, std::span<const std::uint8_t> key, const SyncPlan& plan,
                                              const client::SyncResult& result) {
	// The changes that are not the accepted pushes of this round.
	std::vector<const client::Change*> changes;
	for (const client::Change& c : result.changes) {
		const auto accepted_it = result.accepted.find(c.id);
		if (accepted_it != result.accepted.end() && accepted_it->second == c.rev) {
			continue;
		}
		changes.push_back(&c);
	}

	// Every live change is decrypted before any change is applied.
	std::unordered_map<std::string, std::string> plains;
	for (const client::Change* c : changes) {
		if (c->deleted) {
			continue;
		}
		std::expected<std::vector<std::uint8_t>, std::string> raw = crypto::open(key, c->id, c->blob);
		if (!raw) {
			return std::unexpected(std::string(DECRYPT_MESSAGE));
		}
		std::expected<std::string, std::string> plain = crypto::plain_bytes::unpack(*raw);
		if (!plain) {
			return std::unexpected(std::string(DECRYPT_MESSAGE));
		}
		plains[c->id] = std::move(*plain);
	}
	std::unordered_map<std::string_view, std::string> kind_of;
	for (const auto& [id, p] : plains) {
		kind_of.emplace(id, codec::kind(p));
	}

	// C.1: the accepted pushes. A row that did not change since the push becomes clean. The pushes are
	// visited in uid order, as the Rust code sorts them.
	std::vector<std::pair<std::string, std::int64_t>> accepted(result.accepted.begin(), result.accepted.end());
	std::sort(accepted.begin(), accepted.end());
	for (const auto& [uid, rev] : accepted) {
		if (uid == SHORTCUTS_ID) {
			codec::ShortCuts list = store.short_cuts();
			const std::optional<std::string> pushed = pushed_of(plan, uid);
			if (pushed && *pushed == codec::shortcuts(list)) {
				store.set_short_cuts_from_sync(std::move(list), rev);
			} else {
				store.set_short_cuts_rev(rev);
			}
			continue;
		}
		const std::optional<SyncRow> row = store.sync_row(uid);
		if (!row) {
			continue;
		}
		const std::optional<std::string> pushed = pushed_of(plan, uid);
		bool same = false;
		if (row->deleted) {
			same = !pushed.has_value();
		} else {
			const std::optional<Record> rec = store.record(*row);
			std::optional<std::string> now;
			if (rec) {
				now = encode(store, *rec);
			}
			same = now.has_value() && now == pushed;
		}
		store.mark_synced(row->table, uid, rev, same);
	}

	// C.3: live categories first, then other live changes, then deleted ones.
	const auto is_cat = [&kind_of](const client::Change* c) {
		const auto it = kind_of.find(c->id);
		return it != kind_of.end() && it->second == "category";
	};
	std::vector<const client::Change*> live;
	for (const client::Change* c : changes) {
		if (!c->deleted) {
			live.push_back(c);
		}
	}
	std::vector<const client::Change*> order;
	for (const client::Change* c : live) {
		if (is_cat(c)) {
			order.push_back(c);
		}
	}
	for (const client::Change* c : live) {
		if (!is_cat(c)) {
			order.push_back(c);
		}
	}
	for (const client::Change* c : changes) {
		if (c->deleted) {
			order.push_back(c);
		}
	}
	bool changed = false;
	bool short_cuts_changed = false;
	for (const client::Change* c : order) {
		const auto text_it = plains.find(c->id);
		const std::string_view text = text_it != plains.end() ? std::string_view(text_it->second) : std::string_view();
		if (c->id == SHORTCUTS_ID) {
			if (apply_short_cuts(store, *c, text)) {
				short_cuts_changed = true;
			}
		} else if (apply_change(store, *c, text)) {
			changed = true;
		}
	}

	// C.4: the server revision is saved only when every change above applied.
	store.set_sync_since(result.rev);
	return SyncOutcome{
	    .no_conflicts = result.conflicts.empty(),
	    .changed = changed,
	    .short_cuts_changed = short_cuts_changed,
	};
}

} // namespace palm::runner
