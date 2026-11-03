// links: model codec crypto client runner session
#include "mem_store.hpp"

#include "palm/client.hpp"
#include "palm/codec.hpp"
#include "palm/crypto.hpp"
#include "palm/model.hpp"
#include "palm/runner.hpp"
#include "palm/session.hpp"

#include <algorithm>
#include <cstdint>
#include <doctest/doctest.h>
#include <expected>
#include <initializer_list>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <utility>
#include <variant>
#include <vector>

using namespace palm;
using mem::MemStore;

namespace {

using Net = std::expected<client::SyncResult, std::string>;

// The fields of a Done step, compared as one value.
struct Ended {
	std::optional<std::string> error;
	bool changed = false;
	bool again = false;
	bool operator==(const Ended&) const = default;
};

// The 32-byte test key: byte i is 3 * i + 1.
std::vector<std::uint8_t> key() {
	std::vector<std::uint8_t> k(32);
	for (std::size_t i = 0; i < k.size(); ++i) {
		k[i] = static_cast<std::uint8_t>(i * 3 + 1);
	}
	return k;
}

// An event on day 19000, 9:00 to 10:00, with this title.
model::Event day_event(const std::string& title) {
	model::Event e;
	e.title = title;
	e.date = 19000;
	e.start = 540;
	e.end = 600;
	return e;
}

runner::Record event(const std::string& title) {
	return day_event(title);
}

// The title of the event row `uid`.
std::string title(const MemStore& store, std::string_view uid) {
	const mem::Entry* e = store.entry(uid);
	if (e == nullptr) {
		throw std::runtime_error("no such row");
	}
	const auto* ev = std::get_if<model::Event>(&e->rec);
	if (ev == nullptr) {
		throw std::runtime_error("not an event");
	}
	return ev->title;
}

// A live change when `text` has a value, otherwise a delete.
client::Change change(std::string_view uid, std::int64_t rev, const std::optional<std::string>& text,
                      std::span<const std::uint8_t> k) {
	if (!text) {
		return client::Change{std::string(uid), rev, true, ""};
	}
	const std::vector<std::uint8_t> plain(text->begin(), text->end());
	const auto blob = crypto::seal(k, uid, plain);
	REQUIRE(blob.has_value());
	return client::Change{std::string(uid), rev, false, *blob};
}

// A server answer with these accepted pushes and these changes.
client::SyncResult result(std::int64_t rev, std::initializer_list<std::pair<std::string, std::int64_t>> accepted,
                          std::vector<client::Change> changes) {
	client::SyncResult r;
	r.rev = rev;
	r.accepted.insert(accepted.begin(), accepted.end());
	r.changes = std::move(changes);
	return r;
}

// The sealed text of a server event with this title.
std::string remote_event(const std::string& t) {
	return codec::event(day_event(t));
}

// An empty server answer with these conflicts.
client::SyncResult empty_result(std::initializer_list<std::string> conflicts) {
	client::SyncResult r;
	r.conflicts.insert(conflicts.begin(), conflicts.end());
	return r;
}

// A pre-collect step that never fails.
const session::BeforeCollect no_prep = [] { return std::expected<void, std::string>{}; };

// Drives a run to its end with `send` answering each round; returns the Done step and the round count.
template <typename Answer>
std::pair<session::Step, std::uint32_t> drive(session::SyncSession& sess, MemStore& store, Answer send) {
	const auto k = key();
	std::optional<session::Step> first = sess.start(store, k, no_prep);
	REQUIRE(first.has_value());
	session::Step step = *first;
	std::uint32_t rounds = 0;
	while (std::holds_alternative<session::Send>(step)) {
		runner::SyncPlan plan = std::move(std::get<session::Send>(step).plan);
		++rounds;
		Net net = send(sess);
		step = sess.applied(store, k, plan, std::move(net), no_prep).second;
	}
	return {step, rounds};
}

// The fields of a Done step; any other step ends the test.
Ended done(const session::Step& step) {
	const auto* d = std::get_if<session::Done>(&step);
	REQUIRE(d != nullptr);
	return Ended{d->error, d->changed, d->again};
}

} // namespace

TEST_CASE("collect_seals_dirty_rows_and_sends_tombstones") {
	MemStore store;
	store.add("e1", event("Dentist"), 0, true, false);
	store.add("e2", event("Gone"), 3, true, true);
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	REQUIRE(plan->items.size() == 2);
	const auto& live = plan->items[0];
	CHECK(live.id == "e1");
	CHECK_FALSE(live.deleted);
	const auto raw = crypto::open(k, "e1", live.blob);
	REQUIRE(raw.has_value());
	const auto plain = crypto::plain_bytes::unpack(*raw);
	REQUIRE(plain.has_value());
	CHECK(codec::kind(*plain) == "event");
	const auto ev = codec::to_event(*plain, "e1");
	REQUIRE(ev.has_value());
	CHECK(ev->title == "Dentist");
	CHECK(plan->pushed.at("e1") == *plain);
	const auto& tomb = plan->items[1];
	CHECK(tomb.id == "e2");
	CHECK(tomb.deleted);
	CHECK(tomb.blob.empty());
	CHECK(tomb.base_rev == 3);
	CHECK_FALSE(plan->pushed.at("e2").has_value());
	CHECK(plan->since == store.sync_since());
}

TEST_CASE("accepted_unchanged_row_becomes_clean") {
	MemStore store;
	store.add("e1", event("Dentist"), 0, true, false);
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	const auto outcome = runner::apply(store, k, *plan, result(5, {{"e1", 5}}, {}));
	REQUIRE(outcome.has_value());
	const mem::Entry* e = store.entry("e1");
	REQUIRE(e != nullptr);
	CHECK(e->rev == 5);
	CHECK_FALSE(e->dirty);
	CHECK(outcome->no_conflicts);
	CHECK_FALSE(outcome->changed);
}

TEST_CASE("accepted_row_changed_after_collect_stays_dirty") {
	MemStore store;
	store.add("e1", event("Dentist"), 0, true, false);
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	mem::Entry* row = store.entry_mut("e1");
	REQUIRE(row != nullptr);
	row->rec = event("Dentist, moved");
	const auto outcome = runner::apply(store, k, *plan, result(5, {{"e1", 5}}, {}));
	REQUIRE(outcome.has_value());
	const mem::Entry* e = store.entry("e1");
	REQUIRE(e != nullptr);
	CHECK(e->rev == 5);
	CHECK(e->dirty);
}

TEST_CASE("remote_new_event_is_inserted") {
	MemStore store;
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	const auto outcome = runner::apply(store, k, *plan, result(4, {}, {change("e9", 4, remote_event("Remote"), k)}));
	REQUIRE(outcome.has_value());
	CHECK(title(store, "e9") == "Remote");
	const mem::Entry* e = store.entry("e9");
	REQUIRE(e != nullptr);
	CHECK(e->rev == 4);
	CHECK_FALSE(e->dirty);
	CHECK(outcome->changed);
	CHECK(store.sync_since() == 4);
}

TEST_CASE("remote_new_address_is_inserted_with_address_category") {
	MemStore store;
	store.add("cat-a", model::Category{6, "Work", 2, ""}, 0, false, false);
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	model::Address a;
	a.last_name = "Earman";
	a.first_name = "Ross";
	const std::string remote = codec::address(a, "cat-a");
	const auto outcome = runner::apply(store, k, *plan, result(4, {}, {change("a9", 4, remote, k)}));
	REQUIRE(outcome.has_value());
	const mem::Entry* e = store.entry("a9");
	REQUIRE(e != nullptr);
	const auto* addr = std::get_if<model::Address>(&e->rec);
	REQUIRE(addr != nullptr);
	CHECK(addr->last_name == "Earman");
	CHECK(addr->category == 6);
	CHECK(outcome->changed);
}

TEST_CASE("dirty_local_event_with_different_remote_keeps_both") {
	MemStore store;
	store.add("e1", event("Local"), 0, true, false);
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	const auto outcome = runner::apply(store, k, *plan, result(7, {}, {change("e1", 7, remote_event("Remote"), k)}));
	REQUIRE(outcome.has_value());
	REQUIRE(store.copies.size() == 1);
	const auto* copy = std::get_if<model::Event>(&store.copies[0]);
	REQUIRE(copy != nullptr);
	CHECK(copy->title == "Local");
	CHECK(title(store, "e1") == "Remote");
	const mem::Entry* e = store.entry("e1");
	REQUIRE(e != nullptr);
	CHECK(e->rev == 7);
	CHECK_FALSE(e->dirty);
	CHECK(outcome->changed);
}

TEST_CASE("dirty_local_event_with_same_content_takes_remote_without_copy") {
	MemStore store;
	store.add("e1", event("Same"), 0, true, false);
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	REQUIRE(runner::apply(store, k, *plan, result(7, {}, {change("e1", 7, remote_event("Same"), k)})).has_value());
	CHECK(store.copies.empty());
	const mem::Entry* e = store.entry("e1");
	REQUIRE(e != nullptr);
	CHECK(e->rev == 7);
	CHECK_FALSE(e->dirty);
}

TEST_CASE("remote_delete_of_clean_row_drops_it") {
	MemStore store;
	store.add("e1", event("Old"), 2, false, false);
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	const auto outcome = runner::apply(store, k, *plan, result(8, {}, {change("e1", 8, std::nullopt, k)}));
	REQUIRE(outcome.has_value());
	CHECK_FALSE(store.sync_row("e1").has_value());
	CHECK(outcome->changed);
}

TEST_CASE("short_cuts_dirty_with_remote_change_rebases") {
	MemStore store;
	store.shortcuts = {{"a", "1"}};
	store.shortcuts_dirty = true;
	store.shortcuts_rev = 3;
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	const auto it = std::find_if(plan->items.begin(), plan->items.end(),
	                             [](const client::PushItem& item) { return item.id == "shortcuts"; });
	REQUIRE(it != plan->items.end());
	CHECK(it->base_rev == 3);
	const codec::ShortCuts remote_list{{"b", "2"}};
	const std::string remote = codec::shortcuts(remote_list);
	const auto outcome = runner::apply(store, k, *plan, result(9, {}, {change("shortcuts", 9, remote, k)}));
	REQUIRE(outcome.has_value());
	CHECK(store.shortcuts_rev == 9);
	CHECK(store.shortcuts_dirty);
	const codec::ShortCuts want{{"a", "1"}};
	CHECK(store.shortcuts == want);
	CHECK_FALSE(outcome->short_cuts_changed);
	CHECK_FALSE(outcome->changed);
}

TEST_CASE("blob_sealed_with_another_key_fails_and_changes_nothing") {
	MemStore store;
	store.add("e1", event("Local"), 0, true, false);
	const auto k = key();
	const auto plan = runner::collect(store, k);
	REQUIRE(plan.has_value());
	const std::vector<std::uint8_t> other(32, 9);
	const auto err =
	    runner::apply(store, k, *plan, result(7, {{"e1", 5}}, {change("e1", 7, remote_event("Remote"), other)}));
	REQUIRE_FALSE(err.has_value());
	CHECK(err.error() == runner::DECRYPT_MESSAGE);
	const mem::Entry* e = store.entry("e1");
	REQUIRE(e != nullptr);
	CHECK(e->rev == 0);
	CHECK(e->dirty);
	CHECK(title(store, "e1") == "Local");
	CHECK(store.copies.empty());
	CHECK(store.sync_since() == 0);
}

// ---- the round loop ----

TEST_CASE("clean_run_finishes_once_with_no_error_and_no_change") {
	session::SyncSession sess;
	MemStore store;
	const auto run = drive(sess, store, [](session::SyncSession&) -> Net { return empty_result({}); });
	const Ended want{std::nullopt, false, false};
	CHECK(done(run.first) == want);
	CHECK(run.second == 1);
	CHECK_FALSE(sess.running());
}

TEST_CASE("start_while_running_gives_none") {
	session::SyncSession sess;
	MemStore store;
	std::optional<bool> inner;
	const auto run = drive(sess, store, [&inner](session::SyncSession& s) -> Net {
		const MemStore other{};
		const auto k = key();
		inner = !s.start(other, k, no_prep).has_value();
		return empty_result({});
	});
	CHECK(inner == std::optional<bool>{true});
	const Ended want{std::nullopt, false, false};
	CHECK(done(run.first) == want);
	CHECK_FALSE(sess.running());
}

TEST_CASE("failed_send_finishes_with_its_message") {
	session::SyncSession sess;
	MemStore store;
	const auto run = drive(sess, store, [](session::SyncSession&) -> Net { return std::unexpected("No network."); });
	const Ended want{std::string("No network."), false, false};
	CHECK(done(run.first) == want);
	CHECK_FALSE(sess.running());
}

TEST_CASE("failed_send_without_message_uses_default_text") {
	session::SyncSession sess;
	MemStore store;
	const auto run = drive(sess, store, [](session::SyncSession&) -> Net { return std::unexpected(std::string{}); });
	const Ended want{std::string("Sync failed."), false, false};
	CHECK(done(run.first) == want);
}

TEST_CASE("request_again_during_run_asks_once_after_finish") {
	session::SyncSession sess;
	MemStore store;
	const auto run = drive(sess, store, [](session::SyncSession& s) -> Net {
		s.request_again();
		return empty_result({});
	});
	const Ended want{std::nullopt, false, true};
	CHECK(done(run.first) == want);
	const auto again = drive(sess, store, [](session::SyncSession&) -> Net { return empty_result({}); });
	CHECK_FALSE(done(again.first).again);
}

TEST_CASE("request_again_without_run_does_nothing") {
	session::SyncSession sess;
	MemStore store;
	sess.request_again();
	const auto run = drive(sess, store, [](session::SyncSession&) -> Net { return empty_result({}); });
	CHECK_FALSE(done(run.first).again);
}

TEST_CASE("conflicts_run_up_to_max_rounds") {
	session::SyncSession sess;
	MemStore store;
	const auto run = drive(sess, store, [](session::SyncSession&) -> Net { return empty_result({"e1"}); });
	CHECK(run.second == runner::MAX_ROUNDS);
	const Ended want{std::nullopt, false, false};
	CHECK(done(run.first) == want);
}

TEST_CASE("before_collect_error_ends_the_run") {
	session::SyncSession sess;
	MemStore store;
	const session::BeforeCollect fail = []() -> std::expected<void, std::string> {
		return std::unexpected(std::string("Store busy."));
	};
	const auto k = key();
	const std::optional<session::Step> step = sess.start(store, k, fail);
	REQUIRE(step.has_value());
	const Ended want{std::string("Store busy."), false, false};
	CHECK(done(*step) == want);
	CHECK_FALSE(sess.running());
}
