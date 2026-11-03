// links: model codec crypto client runner session
#include "palm/client.hpp"
#include "palm/codec.hpp"
#include "palm/runner.hpp"
#include "palm/session.hpp"

#include <cstdint>
#include <doctest/doctest.h>
#include <expected>
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

using namespace palm;

namespace {

// A store with no records: every read is empty and every write is ignored.
class EmptyStore : public runner::SyncStore {
public:
	std::vector<runner::SyncRow> dirty_rows() const override { return {}; }
	std::optional<runner::SyncRow> sync_row(std::string_view) const override { return std::nullopt; }
	std::optional<runner::Record> record(const runner::SyncRow&) const override { return std::nullopt; }
	std::string category_uid(std::int32_t) const override { return {}; }
	std::int32_t category_id_for_uid(std::string_view, std::int32_t) const override { return 0; }
	void mark_synced(std::string_view, std::string_view, std::int64_t, bool) override {}
	void put_remote(runner::Record, std::int64_t) override {}
	void drop_remote(std::string_view, std::string_view) override {}
	void save_copy(runner::Record) override {}
	codec::ShortCuts short_cuts() const override { return {}; }
	bool short_cuts_dirty() const override { return false; }
	std::int64_t short_cuts_rev() const override { return 0; }
	void set_short_cuts_rev(std::int64_t) override {}
	void set_short_cuts_from_sync(codec::ShortCuts, std::int64_t) override {}
	std::int64_t sync_since() const override { return 0; }
	void set_sync_since(std::int64_t) override {}
};

} // namespace

TEST_CASE("new session is not running") {
	const session::SyncSession s{};
	CHECK_FALSE(s.running());
}

TEST_CASE("start with an empty store sends an empty plan") {
	EmptyStore store;
	session::SyncSession s{};
	const session::BeforeCollect before = [] { return std::expected<void, std::string>{}; };
	const std::span<const std::uint8_t> key{};
	const std::optional<session::Step> step = s.start(store, key, before);
	REQUIRE(step.has_value());
	REQUIRE(std::holds_alternative<session::Send>(*step));
	const runner::SyncPlan& plan = std::get<session::Send>(*step).plan;
	CHECK(plan.items.empty());
	CHECK(plan.pushed.empty());
	CHECK(plan.since == 0);
	CHECK(s.running());
}
