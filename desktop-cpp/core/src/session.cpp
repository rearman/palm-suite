// The sync session state machine of session.hpp, as desktop-rs/core/src/session.rs.
#include "palm/session.hpp"

#include <cstddef>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::session {
namespace {

// The error text when a send fails without a message.
constexpr std::string_view FAILED = "Sync failed.";

} // namespace

std::string held_text(const std::vector<std::string>& held) {
	if (held.empty()) {
		return {};
	}
	std::string out = "Not synced (too large): ";
	for (std::size_t i = 0; i < held.size(); ++i) {
		if (i > 0) {
			out += ", ";
		}
		out += held[i];
	}
	return out;
}

bool SyncSession::running() const {
	return running_;
}

std::optional<Step> SyncSession::start(const runner::SyncStore& store, std::span<const std::uint8_t> key) {
	if (running_) {
		return std::nullopt;
	}
	running_ = true;
	rounds = 0;
	changed = false;
	held.clear();
	return next_round(store, key);
}

void SyncSession::request_again() {
	if (running_) {
		again_requested = true;
	}
}

Step SyncSession::next_round(const runner::SyncStore& store, std::span<const std::uint8_t> key) {
	rounds += 1;
	std::expected<runner::SyncPlan, std::string> plan = runner::collect(store, key);
	if (!plan) {
		return finish(plan.error());
	}
	held = plan->held;
	return Send{std::move(*plan)};
}

std::pair<std::optional<runner::SyncOutcome>, Step>
SyncSession::applied(runner::SyncStore& store, std::span<const std::uint8_t> key, const runner::SyncPlan& plan,
                     std::expected<client::SyncResult, std::string> net) {
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
	Step step = (outcome.no_conflicts || rounds >= runner::MAX_ROUNDS) ? finish(std::nullopt) : next_round(store, key);
	return {outcome, std::move(step)};
}

Step SyncSession::finish(std::optional<std::string> error) {
	running_ = false;
	const bool again = std::exchange(again_requested, false);
	return Done{
	    .error = std::move(error),
	    .changed = changed,
	    .again = again,
	    .held = std::move(held),
	};
}

} // namespace palm::session
