// The sync session state machine of session.hpp, as desktop-rs/core/src/session.rs.
#include "palm/session.hpp"

#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>

namespace palm::session {
namespace {

// The error text when a send fails without a message.
constexpr std::string_view FAILED = "Sync failed.";

} // namespace

bool SyncSession::running() const {
	return running_;
}

std::optional<Step> SyncSession::start(const runner::SyncStore& store, std::span<const std::uint8_t> key,
                                       const BeforeCollect& before_collect) {
	if (running_) {
		return std::nullopt;
	}
	running_ = true;
	rounds = 0;
	changed = false;
	return next_round(store, key, before_collect);
}

void SyncSession::request_again() {
	if (running_) {
		again_requested = true;
	}
}

Step SyncSession::next_round(const runner::SyncStore& store, std::span<const std::uint8_t> key,
                             const BeforeCollect& before_collect) {
	rounds += 1;
	const std::expected<void, std::string> ready = before_collect();
	if (!ready) {
		return finish(ready.error());
	}
	std::expected<runner::SyncPlan, std::string> plan = runner::collect(store, key);
	if (!plan) {
		return finish(plan.error());
	}
	return Send{std::move(*plan)};
}

std::pair<std::optional<runner::SyncOutcome>, Step>
SyncSession::applied(runner::SyncStore& store, std::span<const std::uint8_t> key, const runner::SyncPlan& plan,
                     std::expected<client::SyncResult, std::string> net, const BeforeCollect& before_collect) {
	if (!net) {
		std::string e = net.error();
		if (e.empty()) {
			e = FAILED;
		}
		return {std::nullopt, finish(std::move(e))};
	}
	const std::expected<runner::SyncOutcome, std::string> outcome_or = runner::apply(store, key, plan, *net);
	if (!outcome_or) {
		return {std::nullopt, finish(outcome_or.error())};
	}
	const runner::SyncOutcome outcome = *outcome_or;
	if (outcome.changed) {
		changed = true;
	}
	Step step = (outcome.no_conflicts || rounds >= runner::MAX_ROUNDS) ? finish(std::nullopt)
	                                                                   : next_round(store, key, before_collect);
	return {outcome, std::move(step)};
}

Step SyncSession::finish(std::optional<std::string> error) {
	running_ = false;
	const bool again = std::exchange(again_requested, false);
	return Done{
	    .error = std::move(error),
	    .changed = changed,
	    .again = again,
	};
}

} // namespace palm::session
