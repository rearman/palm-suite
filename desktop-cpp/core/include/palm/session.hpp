// The round loop of one sync run, as desktop-rs/core/src/session.rs (SyncSession.kt), as a state machine that
// the caller drives.
//
// A run has one or more rounds. Each round collects the dirty rows on the UI thread (SyncSession::start or the
// Send step of the round before), sends the plan on a worker thread, and applies the server answer on the UI
// thread (SyncSession::applied). A run ends when the server reports no conflicts, or after MAX_ROUNDS rounds.
#pragma once

#include "palm/client.hpp"
#include "palm/runner.hpp"

#include <cstdint>
#include <expected>
#include <functional>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <variant>

namespace palm::session {

// Runs before each collect. An error there ends the run with its text.
using BeforeCollect = std::function<std::expected<void, std::string>()>;

// Send these push items on the worker thread, then pass the answer to SyncSession::applied.
struct Send {
	runner::SyncPlan plan;
};

// The run ended: the error text, or nullopt on success; whether any round changed records; and whether
// SyncSession::request_again asked for one more run.
struct Done {
	std::optional<std::string> error;
	bool changed = false;
	bool again = false;
};

// What the caller does next.
using Step = std::variant<Send, Done>;

class SyncSession {
public:
	// A session with no run in progress.
	SyncSession() = default;

	// True from start until the run ends.
	bool running() const;

	// Starts a run and collects its first round. nullopt, and no change, when a run is in progress.
	// `before_collect` runs before each collect; an error there ends the run with its text.
	std::optional<Step> start(const runner::SyncStore& store, std::span<const std::uint8_t> key,
	                          const BeforeCollect& before_collect);

	// Asks for one more run when the run in progress ends. Does nothing when no run is in progress.
	void request_again();

	// Applies the answer to the plan of the last Send step (or the send error) and returns the next step,
	// with the outcome of the round when it applied.
	std::pair<std::optional<runner::SyncOutcome>, Step>
	applied(runner::SyncStore& store, std::span<const std::uint8_t> key, const runner::SyncPlan& plan,
	        std::expected<client::SyncResult, std::string> net, const BeforeCollect& before_collect);

private:
	// Collects the next round. A Done step when the collect fails.
	Step next_round(const runner::SyncStore& store, std::span<const std::uint8_t> key,
	                const BeforeCollect& before_collect);

	// Ends the run with `error` and reports whether another run was requested.
	Step finish(std::optional<std::string> error);

	// The Rust field `running` is `running_` here, because a member function is named running().
	bool running_ = false;
	std::uint32_t rounds = 0;
	bool changed = false;
	bool again_requested = false;
};

} // namespace palm::session
