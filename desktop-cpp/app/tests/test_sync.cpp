// links: sync msg
// The paths of desktop-rs/app/src/sync.rs that need no server and no display: a run that cannot start reports why,
// a turned-off sync does nothing, and a set up that failed or gave no key says so.
#include "palm/sync.hpp"

#include <doctest/doctest.h>

#include <atomic>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <optional>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <process.h> // _getpid
#else
#include <unistd.h> // getpid
#endif

namespace fs = std::filesystem;
namespace sync = palm::sync;
namespace settings = palm::settings;
namespace store = palm::store;

namespace {

// A new empty folder under the system temp folder, as test_settings.cpp.
fs::path temp_dir() {
	static std::atomic<std::uint32_t> n{0};
#ifdef _WIN32
	const int pid = _getpid();
#else
	const int pid = getpid();
#endif
	const fs::path dir =
	    fs::temp_directory_path() / ("palm-sync-" + std::to_string(pid) + "-" + std::to_string(n.fetch_add(1)));
	std::error_code ec;
	fs::remove_all(dir, ec);
	fs::create_directories(dir);
	return dir;
}

} // namespace

TEST_CASE("sync_refusals_name_the_reason") {
	const fs::path dir = temp_dir();
	auto opened = store::DesktopStore::open(dir / store::FILE_NAME);
	REQUIRE(opened.has_value());
	store::DesktopStore desktop_store = std::move(*opened);
	settings::Settings set = settings::Settings::open(dir);
	auto channel = palm::msg::channel();
	sync::DesktopSync desktop(channel.first, true);

	// Sync is off: a quiet run says nothing, the menu gets an alert, the dialog gets the text.
	CHECK(desktop.sync_now(desktop_store, set, sync::Report::Quiet).empty());
	CHECK(desktop.sync_now(desktop_store, set, sync::Report::Menu) ==
	      std::vector<sync::Effect>{sync::Alert{"Sync is off."}});
	CHECK(desktop.sync_now(desktop_store, set, sync::Report::Dialog) ==
	      std::vector<sync::Effect>{sync::DialogDone{"Sync is off."}});
	CHECK_FALSE(desktop.running());

	// Sync is on with no key and no token, then with a key that is not base64.
	REQUIRE(set.set_bool("syncOn", true).has_value());
	CHECK(desktop.sync_now(desktop_store, set, sync::Report::Menu) ==
	      std::vector<sync::Effect>{sync::Alert{"Set up sync again."}});
	REQUIRE(set.set("key", "not base64!").has_value());
	CHECK(desktop.sync_now(desktop_store, set, sync::Report::Dialog) ==
	      std::vector<sync::Effect>{sync::DialogDone{"Set up sync again."}});

	// A good key with no token is refused the same way.
	REQUIRE(set.set("key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").has_value());
	CHECK(desktop.sync_now(desktop_store, set, sync::Report::Menu) ==
	      std::vector<sync::Effect>{sync::Alert{"Set up sync again."}});
	CHECK_FALSE(desktop.running());

	// The automatic syncs do nothing while sync is off, and nothing is left running.
	REQUIRE(set.set_bool("syncOn", false).has_value());
	CHECK(desktop.request_if_stale(desktop_store, set).empty());
	CHECK(desktop.request(desktop_store, set).empty());
	fs::remove_all(dir);
}

TEST_CASE("sync_turn_off_removes_token_and_key") {
	const fs::path dir = temp_dir();
	settings::Settings set = settings::Settings::open(dir);
	REQUIRE(set.set_bool("syncOn", true).has_value());
	REQUIRE(set.set("token", "t0ken").has_value());
	REQUIRE(set.set("key", "AAAA").has_value());
	REQUIRE(sync::DesktopSync::turn_off(set).has_value());
	CHECK_FALSE(sync::DesktopSync::is_on(set));
	CHECK(set.get("token", "") == "");
	CHECK(set.get("key", "") == "");
	fs::remove_all(dir);
}

TEST_CASE("sync_finish_set_up_reports_failures") {
	const fs::path dir = temp_dir();
	auto opened = store::DesktopStore::open(dir / store::FILE_NAME);
	REQUIRE(opened.has_value());
	store::DesktopStore desktop_store = std::move(*opened);
	settings::Settings set = settings::Settings::open(dir);
	auto channel = palm::msg::channel();
	sync::DesktopSync desktop(channel.first, true);

	// An error of the call is shown as it is; a wrong passphrase gives no key.
	CHECK(desktop.finish_set_up(desktop_store, set, std::unexpected(std::string("Server is down.")),
	                            "https://sync.example.com",
	                            "t0ken") == std::vector<sync::Effect>{sync::DialogDone{"Server is down."}});
	CHECK(desktop.finish_set_up(desktop_store, set, std::optional<std::vector<std::uint8_t>>{},
	                            "https://sync.example.com",
	                            "t0ken") == std::vector<sync::Effect>{sync::DialogDone{"Wrong passphrase."}});
	CHECK_FALSE(sync::DesktopSync::is_on(set));
	fs::remove_all(dir);
}
