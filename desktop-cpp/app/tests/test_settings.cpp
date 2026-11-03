// links: text days platform settings
// The two settings tests of desktop-rs/app/tests/store.rs: the text that Java writes is read, and a save reopens.
#include "palm/settings.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <map>
#include <string>

#ifdef _WIN32
#include <process.h> // _getpid
#else
#include <unistd.h> // getpid
#endif

namespace fs = std::filesystem;
namespace settings = palm::settings;

namespace {

// A new empty folder under the system temp folder, as store.rs's temp_dir.
fs::path temp_dir() {
	static std::atomic<std::uint32_t> n{0};
#ifdef _WIN32
	const int pid = _getpid();
#else
	const int pid = getpid();
#endif
	const fs::path dir =
	    fs::temp_directory_path() / ("palm-store-" + std::to_string(pid) + "-" + std::to_string(n.fetch_add(1)));
	std::error_code ec;
	fs::remove_all(dir, ec);
	fs::create_directories(dir);
	return dir;
}

} // namespace

TEST_CASE("settings_read_what_java_writes") {
	const std::string text =
	    "#comment\n! another\n#Thu Oct 22 15:49:22 EDT 2026\nserver=https\\://sync.example.com\n"
	    "name=M\\u00FCller \\u2713\nspaced\\ key = value with  spaces  \nempty=\ncolon:yes\n  indented=1\n"
	    "multi=first \\\n    second\nescapes=a\\tb\\\\c\\=d\\#e\nemoji=\\uD83D\\uDE00\nbare\n";
	const std::map<std::string, std::string> want = {
	    {"server", "https://sync.example.com"},
	    {"name", "M\u00FCller \u2713"},
	    {"spaced key", "value with  spaces  "},
	    {"empty", ""},
	    {"colon", "yes"},
	    {"indented", "1"},
	    {"multi", "first second"},
	    {"escapes", "a\tb\\c=d#e"},
	    {"emoji", "\U0001F600"},
	    {"bare", ""},
	};
	const auto p = settings::parse(text);
	REQUIRE(p == want);
	// What store writes reads back the same, and is pure ASCII.
	const std::string out = settings::store(p, "c", "date");
	CHECK(std::ranges::all_of(out, [](char c) { return static_cast<unsigned char>(c) < 0x80; }));
	REQUIRE(settings::parse(out) == want);
	CHECK(out.contains("\nname=M\\u00FCller \\u2713\n"));
	CHECK(out.contains("\nspaced\\ key=value with  spaces  \n"));
}

TEST_CASE("settings_unicode_escape_takes_a_plus_sign") {
	// Rust's u16::from_str_radix takes one leading '+', so "\u+041" is "A".
	const auto p = settings::parse("a=\\u+041\n");
	REQUIRE(p.contains("a"));
	CHECK(p.at("a") == "A");
	// A '-' is not a sign for an unsigned unit, so the escape stays as the letter u.
	CHECK(settings::parse("b=\\u-041\n").at("b") == "u-041");
}

TEST_CASE("settings_save_and_reopen") {
	const fs::path dir = temp_dir();
	auto s = settings::Settings::open(dir);
	CHECK(s.get_int("todoSort", 3) == 3);
	CHECK(s.get_bool("syncOn", false) == false);
	REQUIRE(s.set("server", "https://sync.example.com").has_value());
	REQUIRE(s.set_int("todoSort", 2).has_value());
	REQUIRE(s.set_bool("syncOn", true).has_value());
	REQUIRE(s.set("token", "a=b c").has_value());
	const auto r = settings::Settings::open(dir);
	CHECK(r.get("server", "") == "https://sync.example.com");
	CHECK(r.get_int("todoSort", 0) == 2);
	CHECK(r.get_bool("syncOn", false) == true);
	CHECK(r.get("token", "") == "a=b c");
#ifndef _WIN32
	const auto perms = fs::status(dir / fs::path(settings::FILE_NAME)).permissions();
	CHECK((perms & fs::perms::mask) == (fs::perms::owner_read | fs::perms::owner_write));
#endif
}
