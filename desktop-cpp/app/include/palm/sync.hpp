// Sync with the sync server, as desktop-rs/app/src/sync.rs (DesktopSync, DesktopSync.kt). Each record is sealed with
// the sync key before it leaves this computer; the wire format is in sync-server/PROTOCOL.md.
//
// Threads: the store and the settings stay on the UI thread. Each network call and the key derivation of a set up
// run on a thread of their own, and send their result to the event loop as a msg::Msg.
#pragma once

#include "palm/client.hpp"
#include "palm/msg.hpp"
#include "palm/runner.hpp"
#include "palm/session.hpp"
#include "palm/settings.hpp"
#include "palm/store.hpp"

#include <chrono>
#include <cstdint>
#include <expected>
#include <optional>
#include <string>
#include <string_view>
#include <variant>
#include <vector>

namespace palm::sync {

inline constexpr std::string_view DEFAULT_SERVER = "https://sync.earman.xyz";

// The window coming to the front syncs when the last sync ended longer ago than this.
inline constexpr std::chrono::seconds STALE{60};

// Who waits for the end of a run.
enum class Report {
	// An automatic sync: the status bar only.
	Quiet,
	// Sync > Sync Now: an alert on an error, else "Sync done." in the status bar.
	Menu,
	// The Sync Options dialog: the dialog shows the result.
	Dialog,
};

// What the UI does after a sync step. A Rust enum with data is a variant of one struct per variant.
struct Status {
	std::string text;
	bool operator==(const Status&) const = default;
};

// A round changed records: the views read the store again.
struct Refresh {
	bool operator==(const Refresh&) const = default;
};

struct Alert {
	std::string text;
	bool operator==(const Alert&) const = default;
};

// The result of a dialog run or set up: no value on success, else the error text.
struct DialogDone {
	std::optional<std::string> error;
	bool operator==(const DialogDone&) const = default;
};

using Effect = std::variant<Status, Refresh, Alert, DialogDone>;

class DesktopSync {
public:
	DesktopSync(msg::Sender sender, bool h24);

	static bool is_on(const settings::Settings& settings);
	static std::string server(const settings::Settings& settings);

	bool running() const;

	// Runs one sync. A run that cannot start reports why at once.
	std::vector<Effect> sync_now(store::DesktopStore& store, settings::Settings& settings, Report report);

	// Syncs now, or once more when the running sync ends. For the automatic syncs.
	std::vector<Effect> request(store::DesktopStore& store, settings::Settings& settings);

	// Syncs when sync is on and the last sync ended more than a minute ago.
	std::vector<Effect> request_if_stale(store::DesktopStore& store, settings::Settings& settings);

	// Applies the answer of the round on the wire.
	std::vector<Effect> answer(store::DesktopStore& store, settings::Settings& settings,
	                           std::expected<client::SyncResult, std::string> net);

	// Sets up sync on the server: joins its passphrase, or creates one when the server has none. The key
	// derivation runs on a thread; finish_set_up takes the result. Safe to call from the UI thread only.
	void set_up(std::string server, std::string token, std::string passphrase) const;

	// Saves the settings of a good set up, then runs the first sync.
	std::vector<Effect> finish_set_up(store::DesktopStore& store, settings::Settings& settings,
	                                  std::expected<std::optional<std::vector<std::uint8_t>>, std::string> result,
	                                  const std::string& server, const std::string& token);

	// Turns sync off on this computer. The token and the key are removed.
	static std::expected<void, std::string> turn_off(settings::Settings& settings);

private:
	// Applies one step of a run: a Send starts the worker thread, a Done ends the run.
	std::vector<Effect> step(session::Step next, std::vector<std::uint8_t> key, store::DesktopStore& store,
	                         settings::Settings& settings);

	// The plan of the Send step on the wire, and the key of its run.
	struct Pending {
		runner::SyncPlan plan;
		std::vector<std::uint8_t> key;
	};

	// The Rust field `session` is `session_` here, so it does not share a name with the palm::session namespace.
	session::SyncSession session_;
	std::optional<Pending> pending_;
	std::optional<std::chrono::steady_clock::time_point> last_end_;
	std::vector<Report> reports_;
	msg::Sender sender_;
	bool h24_;
};

} // namespace palm::sync
