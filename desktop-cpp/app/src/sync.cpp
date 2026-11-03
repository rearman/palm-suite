#include "palm/sync.hpp"

#include "palm/crypto.hpp"
#include "palm/days.hpp"
#include "palm/model.hpp"
#include "palm/platform.hpp"

#include <chrono>
#include <cstdint>
#include <expected>
#include <format>
#include <optional>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <variant>
#include <vector>

namespace palm::sync {

namespace {

// The key of a set up: the sync key of the passphrase, or the error text of the call.
using KeyResult = std::expected<std::optional<std::vector<std::uint8_t>>, std::string>;

// The check that runs before each collect. A sync from here has nothing to check.
std::expected<void, std::string> no_check() {
	return {};
}

// "10/22/26 3:49 pm" for the status bar.
std::string now_text(bool h24) {
	const std::chrono::zoned_time now{platform::local_zone(), std::chrono::system_clock::now()};
	const auto local = now.get_local_time();
	const auto day_start = std::chrono::floor<std::chrono::days>(local);
	const std::chrono::year_month_day date{day_start};
	const std::chrono::hh_mm_ss clock{local - day_start};
	const auto day = days::epoch_day(static_cast<std::int64_t>(static_cast<int>(date.year())),
	                                 static_cast<std::uint32_t>(date.month()), static_cast<std::uint32_t>(date.day()));
	const auto minute = static_cast<std::int32_t>(clock.hours().count() * 60 + clock.minutes().count());
	return std::format("{} {}", model::short_date(day), model::time(minute, h24));
}

// The network half of a sync round: a new client, then the push and the answer. The error text is the server's.
std::expected<client::SyncResult, std::string> fetch(const std::string& address, const std::string& token,
                                                     std::int64_t since, const std::vector<client::PushItem>& items) {
	auto client = client::SyncClient::new_client(address, token);
	if (!client) {
		return std::unexpected(client.error());
	}
	auto answer = client->sync(since, items);
	if (!answer) {
		return std::unexpected(answer.error().message);
	}
	return std::move(*answer);
}

// The key of a passphrase on the server: the client, then first_key. The error text of a failed call.
KeyResult derive(const std::string& server, const std::string& token, const std::string& passphrase) {
	auto client = client::SyncClient::new_client(server, token);
	if (!client) {
		return std::unexpected(client.error());
	}
	auto key = client::first_key(*client, passphrase);
	if (!key) {
		return std::unexpected(key.error().message);
	}
	return std::move(*key);
}

} // namespace

DesktopSync::DesktopSync(msg::Sender sender, bool h24) : sender_(std::move(sender)), h24_(h24) {}

bool DesktopSync::is_on(const settings::Settings& settings) {
	return settings.get_bool("syncOn", false);
}

std::string DesktopSync::server(const settings::Settings& settings) {
	return settings.get("server", DEFAULT_SERVER);
}

bool DesktopSync::running() const {
	return session_.running();
}

std::vector<Effect> DesktopSync::sync_now(store::DesktopStore& store, settings::Settings& settings, Report report) {
	const auto refuse = [report](std::string_view text) -> std::vector<Effect> {
		switch (report) {
		case Report::Quiet:
			return {};
		case Report::Menu:
			return {Alert{std::string(text)}};
		case Report::Dialog:
			return {DialogDone{std::string(text)}};
		}
		return {};
	};
	if (session_.running()) {
		return refuse("A sync is already running.");
	}
	if (!is_on(settings)) {
		return refuse("Sync is off.");
	}
	const std::string key_text = settings.get("key", "");
	const std::string token = settings.get("token", "");
	const auto key_bytes = crypto::unb64(key_text);
	if (!key_bytes) {
		return refuse("Set up sync again.");
	}
	if (key_bytes->empty() || token.empty()) {
		return refuse("Set up sync again.");
	}
	std::vector<std::uint8_t> key = *key_bytes;
	reports_.push_back(report);
	auto first = session_.start(store, key, no_check);
	if (!first) {
		throw std::runtime_error("no run is in progress");
	}
	return step(std::move(*first), std::move(key), store, settings);
}

std::vector<Effect> DesktopSync::request(store::DesktopStore& store, settings::Settings& settings) {
	if (session_.running()) {
		session_.request_again();
		return {};
	}
	return sync_now(store, settings, Report::Quiet);
}

std::vector<Effect> DesktopSync::request_if_stale(store::DesktopStore& store, settings::Settings& settings) {
	const bool stale = !last_end_ || std::chrono::steady_clock::now() - *last_end_ > STALE;
	if (is_on(settings) && stale) {
		return request(store, settings);
	}
	return {};
}

std::vector<Effect> DesktopSync::answer(store::DesktopStore& store, settings::Settings& settings,
                                        std::expected<client::SyncResult, std::string> net) {
	if (!pending_) {
		return {};
	}
	Pending p = std::move(*pending_);
	pending_.reset();
	auto [outcome, next] = session_.applied(store, p.key, p.plan, std::move(net), no_check);
	std::vector<Effect> effects;
	if (outcome && outcome->changed) {
		effects.push_back(Refresh{});
	}
	auto more = step(std::move(next), std::move(p.key), store, settings);
	effects.insert(effects.end(), more.begin(), more.end());
	return effects;
}

std::vector<Effect> DesktopSync::step(session::Step next, std::vector<std::uint8_t> key, store::DesktopStore& store,
                                      settings::Settings& settings) {
	if (auto* send = std::get_if<session::Send>(&next)) {
		const std::string address = server(settings);
		const std::string token = settings.get("token", "");
		const std::int64_t since = send->plan.since;
		const std::vector<client::PushItem> items = send->plan.items;
		msg::Sender sender = sender_;
		std::thread([address, token, since, items, sender]() {
			sender.send(msg::SyncAnswer{fetch(address, token, since, items)});
		}).detach();
		pending_ = Pending{std::move(send->plan), std::move(key)};
		return {};
	}
	const session::Done& done = std::get<session::Done>(next);
	const std::optional<std::string> error = done.error;
	const bool again = done.again;
	const std::string text =
	    error ? std::format("Sync failed: {}", *error) : std::format("Last sync: {}", now_text(h24_));
	last_end_ = std::chrono::steady_clock::now();
	std::vector<Effect> effects{Status{text}};
	if (const auto saved = settings.set("lastSync", text); !saved) {
		effects.push_back(Status{std::format("The settings were not saved: {}", saved.error())});
	}
	const std::vector<Report> reports = std::move(reports_);
	reports_.clear();
	for (const Report report : reports) {
		switch (report) {
		case Report::Quiet:
			break;
		case Report::Menu:
			if (error) {
				effects.push_back(Alert{*error});
			} else {
				effects.push_back(Status{"Sync done."});
			}
			break;
		case Report::Dialog:
			effects.push_back(DialogDone{error});
			break;
		}
	}
	if (again) {
		auto more = sync_now(store, settings, Report::Quiet);
		effects.insert(effects.end(), more.begin(), more.end());
	}
	return effects;
}

void DesktopSync::set_up(std::string server, std::string token, std::string passphrase) const {
	msg::Sender sender = sender_;
	std::thread([sender, server, token, passphrase]() {
		KeyResult result = derive(server, token, passphrase);
		sender.send(msg::SetUpDone{std::move(result), server, token});
	}).detach();
}

std::vector<Effect> DesktopSync::finish_set_up(store::DesktopStore& store, settings::Settings& settings,
                                               KeyResult result, const std::string& server, const std::string& token) {
	if (!result) {
		return {DialogDone{result.error()}};
	}
	if (!result->has_value()) {
		return {DialogDone{std::string("Wrong passphrase.")}};
	}
	const std::vector<std::uint8_t> key = std::move(**result);
	const auto saved = settings.set("server", server)
	                       .and_then([&] { return settings.set("token", token); })
	                       .and_then([&] { return settings.set("key", crypto::b64(key)); })
	                       .and_then([&] { return settings.set_bool("syncOn", true); });
	if (!saved) {
		return {DialogDone{std::format("The settings were not saved: {}", saved.error())}};
	}
	store.set_sync_since(0);
	return sync_now(store, settings, Report::Dialog);
}

std::expected<void, std::string> DesktopSync::turn_off(settings::Settings& settings) {
	return settings.set_bool("syncOn", false).and_then([&] { return settings.remove("token"); }).and_then([&] {
		return settings.remove("key");
	});
}

} // namespace palm::sync
