// links: assets draw msg mnemonics platform_ui dialogs combo text_edit note_dialog category address_dialog
// The tests of desktop-rs/app/src/address_dialog.rs that need no display: the custom labels, the default labels, and
// the phone labels.
#include "palm/address_dialog.hpp"

#include "palm/store.hpp"

#include <doctest/doctest.h>

#include <array>
#include <filesystem>
#include <initializer_list>
#include <string>
#include <string_view>
#include <system_error>
#include <utility>

namespace address_dialog = palm::address_dialog;
namespace settings = palm::settings;

namespace {

// A new folder under the temp folder, removed when the guard goes.
struct TempDir {
	std::filesystem::path path = std::filesystem::temp_directory_path() / ("palm-labels-" + palm::store::new_uid());

	TempDir() { std::filesystem::create_directories(path); }
	~TempDir() {
		std::error_code ec;
		std::filesystem::remove_all(path, ec);
	}
	TempDir(const TempDir&) = delete;
	TempDir& operator=(const TempDir&) = delete;
};

// The settings of a file in `dir`, with `pairs` set.
settings::Settings settings_with(const std::filesystem::path& dir,
                                 std::initializer_list<std::pair<std::string_view, std::string_view>> pairs) {
	auto s = settings::Settings::open(dir);
	for (const auto& [key, value] : pairs) {
		REQUIRE(s.set(key, value).has_value());
	}
	return s;
}

} // namespace

TEST_CASE("custom_labels fill blank and missing names") {
	TempDir good;
	const auto s = settings_with(good.path, {{"addressLabels", R"(["Birthday", " ", 7])"}});
	CHECK(address_dialog::custom_labels(s) == std::array<std::string, 4>{"Birthday", "Custom 2", "7", "Custom 4"});
	TempDir bad;
	const auto b = settings_with(bad.path, {{"addressLabels", "not json"}});
	CHECK(address_dialog::custom_labels(b) ==
	      std::array<std::string, 4>{"Custom 1", "Custom 2", "Custom 3", "Custom 4"});
}

TEST_CASE("default_labels clamp and fall back") {
	TempDir with;
	const auto s = settings_with(with.path, {{"addressDefaultLabels", "[7, 9, -1]"}});
	CHECK(address_dialog::default_labels(s) == std::array<std::int32_t, 5>{7, 7, 0, 3, 4});
	TempDir none;
	const auto n = settings_with(none.path, {});
	CHECK(address_dialog::default_labels(n) == std::array<std::int32_t, 5>{0, 1, 2, 3, 4});
}

TEST_CASE("phone labels read as Palm Desktop") {
	CHECK(address_dialog::detail::phone_label("E-mail") == "E-Mail:");
	CHECK(address_dialog::detail::phone_label("Work") == "Work:");
}
