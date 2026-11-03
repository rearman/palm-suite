// links: model codec crypto client runner session
// Two devices sync through a real palmsync server. The test runs when PALMSYNC_URL and PALMSYNC_TOKEN
// name a fresh server (CI builds one from sync-server/ and starts it); without them it passes at once.
#include "mem_store.hpp"

#include "palm/client.hpp"
#include "palm/model.hpp"
#include "palm/runner.hpp"
#include "palm/session.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <doctest/doctest.h>
#include <expected>
#include <iostream>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

using namespace palm;
using mem::MemStore;

namespace {

// The server of the environment, or nullopt when PALMSYNC_URL or PALMSYNC_TOKEN is unset.
std::optional<client::SyncClient> server() {
	// CI sets PALMSYNC_REQUIRED where it starts a server, so a lost server fails the test there.
	if (std::getenv("PALMSYNC_REQUIRED") != nullptr && std::getenv("PALMSYNC_URL") == nullptr) {
		FAIL("PALMSYNC_REQUIRED is set but PALMSYNC_URL is not");
	}
	const char* url = std::getenv("PALMSYNC_URL");
	const char* token = std::getenv("PALMSYNC_TOKEN");
	if (url == nullptr || token == nullptr) {
		return std::nullopt;
	}
	auto made = client::SyncClient::new_client(url, token);
	REQUIRE_MESSAGE(made.has_value(), "a valid server address");
	return std::move(*made);
}

// One full sync run of `store` against the server; fails the test on an error.
bool run_sync(const client::SyncClient& server_client, MemStore& store, std::span<const std::uint8_t> key) {
	session::SyncSession sess;
	const session::BeforeCollect prep = [] { return std::expected<void, std::string>{}; };
	std::optional<session::Step> first = sess.start(store, key, prep);
	REQUIRE(first.has_value());
	session::Step step = *first;
	while (std::holds_alternative<session::Send>(step)) {
		runner::SyncPlan plan = std::move(std::get<session::Send>(step).plan);
		std::expected<client::SyncResult, std::string> net =
		    server_client.sync(plan.since, plan.items).transform_error([](const client::SyncError& e) {
			    return e.message;
		    });
		step = sess.applied(store, key, plan, std::move(net), prep).second;
	}
	const auto& done = std::get<session::Done>(step);
	REQUIRE_MESSAGE(!done.error.has_value(), done.error.value_or(""));
	return done.changed;
}

// The sync key of `passphrase` on the server; fails the test when the server gives no key.
std::vector<std::uint8_t> key_for(const client::SyncClient& server_client, std::string_view passphrase,
                                  const char* message) {
	auto got = client::first_key(server_client, passphrase);
	REQUIRE_MESSAGE(got.has_value(), message);
	REQUIRE_MESSAGE(got->has_value(), message);
	return **got;
}

// The titles of the live events of a store, sorted.
std::vector<std::string> titles(const MemStore& store) {
	std::vector<std::string> t;
	for (const runner::Record* r : store.live("events")) {
		const auto* e = std::get_if<model::Event>(r);
		REQUIRE(e != nullptr);
		t.push_back(e->title);
	}
	std::ranges::sort(t);
	return t;
}

// The first live record of `table` as a T; fails the test when there is none.
template <typename T> const T& first_live(const MemStore& store, std::string_view table) {
	const auto rows = store.live(table);
	REQUIRE(!rows.empty());
	const auto* rec = std::get_if<T>(rows.front());
	REQUIRE(rec != nullptr);
	return *rec;
}

} // namespace

TEST_CASE("two devices sync through the server") {
	const auto maybe_server = server();
	if (!maybe_server) {
		std::cerr << "PALMSYNC_URL and PALMSYNC_TOKEN are not set; the server test does not run.\n";
		return;
	}
	const client::SyncClient& server_client = *maybe_server;

	// Setup: the first device stores the salt and the check blob; the second one derives the same key.
	const std::vector<std::uint8_t> key_a =
	    key_for(server_client, "correct horse", "the first passphrase sets up the server");
	const std::vector<std::uint8_t> key_b =
	    key_for(server_client, "correct horse", "the same passphrase gives the key");
	REQUIRE(key_a == key_b);
	const auto wrong = client::first_key(server_client, "wrong");
	REQUIRE(wrong.has_value());
	REQUIRE(!wrong->has_value());

	// Device A makes one record of each kind, a category and the ShortCuts.
	MemStore a;
	model::Category work_cat;
	work_cat.id = 4;
	work_cat.name = "Work";
	const std::string work = a.create(work_cat);
	model::Todo todo;
	todo.title = "Order VFD";
	todo.category = 4;
	todo.priority = 2;
	a.create(todo);
	model::Event meeting;
	meeting.title = "Staff meeting";
	meeting.date = 20'750;
	const std::string shared = a.create(meeting);
	model::Memo memo_rec;
	memo_rec.text = "Grüße\nbody";
	const std::string memo = a.create(memo_rec);
	model::Address addr;
	addr.last_name = "Müller";
	a.create(addr);
	a.shortcuts = {{"br", "Breakfast"}};
	a.shortcuts_dirty = true;
	run_sync(server_client, a, key_a);
	REQUIRE(std::ranges::all_of(a.rows, [](const mem::Entry& e) { return !e.dirty; }));
	REQUIRE(!a.shortcuts_dirty);

	// Device B gets all of it; the To Do keeps its category under B's own category id.
	MemStore b;
	REQUIRE(run_sync(server_client, b, key_b));
	REQUIRE(b.live("events").size() == 1);
	REQUIRE(b.shortcuts == a.shortcuts);
	const auto* work_row = b.entry(work);
	REQUIRE(work_row != nullptr);
	const auto* work_b = std::get_if<model::Category>(&work_row->rec);
	REQUIRE(work_b != nullptr);
	const std::int32_t b_work = work_b->id;
	const auto& b_todo = first_live<model::Todo>(b, "todos");
	REQUIRE(b_todo.title == "Order VFD");
	REQUIRE(b_todo.category == b_work);
	REQUIRE(first_live<model::Memo>(b, "memos").text == "Grüße\nbody");
	REQUIRE(first_live<model::Address>(b, "addresses").last_name == "Müller");

	// Both devices change the same event: keep-both gives each device both versions.
	a.edit(shared, [](runner::Record& r) {
		if (auto* e = std::get_if<model::Event>(&r)) {
			e->title = "Staff meeting (A)";
		}
	});
	b.edit(shared, [](runner::Record& r) {
		if (auto* e = std::get_if<model::Event>(&r)) {
			e->title = "Staff meeting (B)";
		}
	});
	run_sync(server_client, a, key_a);
	run_sync(server_client, b, key_b);
	run_sync(server_client, a, key_a);
	const std::vector<std::string> both = {"Staff meeting (A)", "Staff meeting (B)"};
	REQUIRE(titles(a) == both);
	REQUIRE(titles(b) == both);

	// A delete on one device removes the record on the other.
	a.delete_row(memo);
	run_sync(server_client, a, key_a);
	run_sync(server_client, b, key_b);
	REQUIRE(b.live("memos").empty());
	REQUIRE_MESSAGE(a.entry(memo) == nullptr, "the pushed tombstone is gone once the server takes it");
}
