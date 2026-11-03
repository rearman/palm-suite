// links: days platform
#include "palm/platform.hpp"
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <ctime>
#include <doctest/doctest.h>
#include <exception>
#include <optional>
#include <string>
#include <string_view>

#ifdef _WIN32
#include <stdlib.h> // _putenv_s
#endif

using namespace palm::platform;

namespace {

// Sets the TZ variable to value, or clears it when value is null.
void set_tz(const char* value) {
#ifdef _WIN32
	_putenv_s("TZ", value != nullptr ? value : "");
	_tzset();
#else
	if (value != nullptr) {
		setenv("TZ", value, 1);
	} else {
		unsetenv("TZ");
	}
#endif
}

// Sets TZ for one test and puts back the value it had when the test ends.
class TzScope {
public:
	explicit TzScope(const char* value) {
		if (const char* old = std::getenv("TZ"); old != nullptr) {
			saved = old;
		}
		set_tz(value);
	}
	~TzScope() { set_tz(saved ? saved->c_str() : nullptr); }
	TzScope(const TzScope&) = delete;
	TzScope& operator=(const TzScope&) = delete;

private:
	std::optional<std::string> saved;
};

// The name of the zone that local_zone gives under TZ=value.
std::string zone_name_for(const char* value) {
	TzScope scope(value);
	const std::chrono::time_zone* zone = local_zone();
	return zone != nullptr ? std::string(zone->name()) : std::string();
}

// The name the database gives UTC (Etc/UTC in the tzdb of MSVC and libstdc++, UTC in some others).
std::string utc_name() {
	return std::string(std::chrono::locate_zone("UTC")->name());
}

// The epoch day of the date that a zone shows now.
std::int64_t zone_today(const std::chrono::time_zone* zone) {
	const std::chrono::zoned_time now{zone, std::chrono::system_clock::now()};
	const std::chrono::year_month_day date{std::chrono::floor<std::chrono::days>(now.get_local_time())};
	return std::chrono::sys_days(date).time_since_epoch().count();
}

} // namespace

TEST_CASE("every_table_zone_locates") {
	CHECK(windows_zone_table().size() == 139);
	for (const WindowsZone& row : windows_zone_table()) {
		try {
			CHECK(std::chrono::locate_zone(row.iana) != nullptr);
		} catch (const std::exception& e) {
			FAIL_CHECK("zone " << row.iana << ": " << e.what());
		}
	}
}

TEST_CASE("windows_names_map_to_iana") {
	CHECK(iana_for_windows("Central Standard Time").value_or("") == "America/Chicago");
	CHECK(iana_for_windows("W. Europe Standard Time").value_or("") == "Europe/Berlin");
	CHECK(iana_for_windows("central standard time").value_or("") == "America/Chicago");
	CHECK(!iana_for_windows("Nowhere Standard Time").has_value());
}

TEST_CASE("local_zone_is_set") {
	CHECK(local_zone() != nullptr);
}

TEST_CASE("tz_variable_names_the_zone") {
	CHECK(zone_name_for("Asia/Tokyo") == "Asia/Tokyo");
}

#ifndef _WIN32
// Not on Windows: _putenv_s with an empty value deletes the variable, so the CRT cannot hold TZ= empty there, and
// the empty-TZ rule is untested on that platform.
TEST_CASE("tz_empty_is_utc") {
	CHECK(zone_name_for("") == utc_name());
}
#endif

TEST_CASE("tz_leading_colon_is_dropped") {
	CHECK(zone_name_for(":Asia/Tokyo") == "Asia/Tokyo");
}

TEST_CASE("tz_zoneinfo_path_gives_the_name_after_zoneinfo") {
	CHECK(zone_name_for("/usr/share/zoneinfo/Asia/Tokyo") == "Asia/Tokyo");
	CHECK(zone_name_for(":/usr/share/zoneinfo/Asia/Tokyo") == "Asia/Tokyo");
	CHECK(zone_name_for("../zoneinfo/Asia/Tokyo") == "Asia/Tokyo");
}

TEST_CASE("tz_posix_rule_falls_back_to_utc") {
	CHECK(zone_name_for("JST-9") == utc_name());
	CHECK(zone_name_for("EST5EDT,M3.2.0,M11.1.0") == utc_name());
}

TEST_CASE("tz_unknown_name_falls_back_to_utc") {
	CHECK(zone_name_for("Nowhere/City") == utc_name());
}

TEST_CASE("today_is_the_tokyo_date_under_tz_tokyo") {
	TzScope scope("Asia/Tokyo");
	const std::chrono::time_zone* tokyo = std::chrono::locate_zone("Asia/Tokyo");
	const std::int64_t before = zone_today(tokyo);
	const std::int64_t got = today();
	const std::int64_t after = zone_today(tokyo);
	// The date may turn over between the reads, so either bracketing date is right.
	CHECK((got == before || got == after));
}

TEST_CASE("today_is_within_a_day_of_the_utc_date") {
	const auto utc_day = std::chrono::floor<std::chrono::days>(std::chrono::system_clock::now());
	const auto epoch_day = static_cast<std::int64_t>(utc_day.time_since_epoch().count());
	const std::int64_t diff = today() - epoch_day;
	CHECK(diff >= -1);
	CHECK(diff <= 1);
}
