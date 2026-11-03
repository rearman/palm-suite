#include "palm/platform.hpp"
#include "palm/days.hpp"
#include "palm/text.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <optional>
#include <ranges>
#include <string>
#include <string_view>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <cwchar>
#include <windows.h>
#endif

namespace palm::platform {

namespace {

// Compares two names by their ASCII lowercase bytes, then by length, as jiff's cmp_ignore_ascii_case does.
constexpr int ascii_case_compare(std::string_view a, std::string_view b) {
	const std::size_t n = std::min(a.size(), b.size());
	for (std::size_t i = 0; i < n; ++i) {
		const char x = text::ascii_lower(a[i]);
		const char y = text::ascii_lower(b[i]);
		if (x != y) {
			return x < y ? -1 : 1;
		}
	}
	if (a.size() == b.size()) {
		return 0;
	}
	return a.size() < b.size() ? -1 : 1;
}

// The CLDR windowsZones.xml mapping, copied from jiff 0.2.38 (src/tz/system/windows/windows_zones.rs, CLDR 2021a),
// 139 rows. The rows are sorted by the ASCII lowercase of the Windows name, so a binary search finds a name whatever
// its case. To refresh, take the new windows_zones.rs from a jiff release (or regenerate from CLDR's windowsZones.xml),
// rebuild the rows with
//   perl -ne 'print "\t{\"$1\", \"$2\"},\n" if /^\s*\(r"([^"]*)", r"([^"]*)"\),\s*$/' windows_zones.rs
// and replace the rows below. The static_assert and the tests in test_platform.cpp check the result.
constexpr std::array<WindowsZone, 139> windows_zones{{
    {"Afghanistan Standard Time", "Asia/Kabul"},
    {"Alaskan Standard Time", "America/Anchorage"},
    {"Aleutian Standard Time", "America/Adak"},
    {"Altai Standard Time", "Asia/Barnaul"},
    {"Arab Standard Time", "Asia/Riyadh"},
    {"Arabian Standard Time", "Asia/Dubai"},
    {"Arabic Standard Time", "Asia/Baghdad"},
    {"Argentina Standard Time", "America/Buenos_Aires"},
    {"Astrakhan Standard Time", "Europe/Astrakhan"},
    {"Atlantic Standard Time", "America/Halifax"},
    {"AUS Central Standard Time", "Australia/Darwin"},
    {"Aus Central W. Standard Time", "Australia/Eucla"},
    {"AUS Eastern Standard Time", "Australia/Sydney"},
    {"Azerbaijan Standard Time", "Asia/Baku"},
    {"Azores Standard Time", "Atlantic/Azores"},
    {"Bahia Standard Time", "America/Bahia"},
    {"Bangladesh Standard Time", "Asia/Dhaka"},
    {"Belarus Standard Time", "Europe/Minsk"},
    {"Bougainville Standard Time", "Pacific/Bougainville"},
    {"Canada Central Standard Time", "America/Regina"},
    {"Cape Verde Standard Time", "Atlantic/Cape_Verde"},
    {"Caucasus Standard Time", "Asia/Yerevan"},
    {"Cen. Australia Standard Time", "Australia/Adelaide"},
    {"Central America Standard Time", "America/Guatemala"},
    {"Central Asia Standard Time", "Asia/Bishkek"},
    {"Central Brazilian Standard Time", "America/Cuiaba"},
    {"Central Europe Standard Time", "Europe/Budapest"},
    {"Central European Standard Time", "Europe/Warsaw"},
    {"Central Pacific Standard Time", "Pacific/Guadalcanal"},
    {"Central Standard Time", "America/Chicago"},
    {"Central Standard Time (Mexico)", "America/Mexico_City"},
    {"Chatham Islands Standard Time", "Pacific/Chatham"},
    {"China Standard Time", "Asia/Shanghai"},
    {"Cuba Standard Time", "America/Havana"},
    {"Dateline Standard Time", "Etc/GMT+12"},
    {"E. Africa Standard Time", "Africa/Nairobi"},
    {"E. Australia Standard Time", "Australia/Brisbane"},
    {"E. Europe Standard Time", "Europe/Chisinau"},
    {"E. South America Standard Time", "America/Sao_Paulo"},
    {"Easter Island Standard Time", "Pacific/Easter"},
    {"Eastern Standard Time", "America/New_York"},
    {"Eastern Standard Time (Mexico)", "America/Cancun"},
    {"Egypt Standard Time", "Africa/Cairo"},
    {"Ekaterinburg Standard Time", "Asia/Yekaterinburg"},
    {"Fiji Standard Time", "Pacific/Fiji"},
    {"FLE Standard Time", "Europe/Kiev"},
    {"Georgian Standard Time", "Asia/Tbilisi"},
    {"GMT Standard Time", "Europe/London"},
    {"Greenland Standard Time", "America/Godthab"},
    {"Greenwich Standard Time", "Atlantic/Reykjavik"},
    {"GTB Standard Time", "Europe/Bucharest"},
    {"Haiti Standard Time", "America/Port-au-Prince"},
    {"Hawaiian Standard Time", "Pacific/Honolulu"},
    {"India Standard Time", "Asia/Calcutta"},
    {"Iran Standard Time", "Asia/Tehran"},
    {"Israel Standard Time", "Asia/Jerusalem"},
    {"Jordan Standard Time", "Asia/Amman"},
    {"Kaliningrad Standard Time", "Europe/Kaliningrad"},
    {"Korea Standard Time", "Asia/Seoul"},
    {"Libya Standard Time", "Africa/Tripoli"},
    {"Line Islands Standard Time", "Pacific/Kiritimati"},
    {"Lord Howe Standard Time", "Australia/Lord_Howe"},
    {"Magadan Standard Time", "Asia/Magadan"},
    {"Magallanes Standard Time", "America/Punta_Arenas"},
    {"Marquesas Standard Time", "Pacific/Marquesas"},
    {"Mauritius Standard Time", "Indian/Mauritius"},
    {"Middle East Standard Time", "Asia/Beirut"},
    {"Montevideo Standard Time", "America/Montevideo"},
    {"Morocco Standard Time", "Africa/Casablanca"},
    {"Mountain Standard Time", "America/Denver"},
    {"Mountain Standard Time (Mexico)", "America/Mazatlan"},
    {"Myanmar Standard Time", "Asia/Rangoon"},
    {"N. Central Asia Standard Time", "Asia/Novosibirsk"},
    {"Namibia Standard Time", "Africa/Windhoek"},
    {"Nepal Standard Time", "Asia/Katmandu"},
    {"New Zealand Standard Time", "Pacific/Auckland"},
    {"Newfoundland Standard Time", "America/St_Johns"},
    {"Norfolk Standard Time", "Pacific/Norfolk"},
    {"North Asia East Standard Time", "Asia/Irkutsk"},
    {"North Asia Standard Time", "Asia/Krasnoyarsk"},
    {"North Korea Standard Time", "Asia/Pyongyang"},
    {"Omsk Standard Time", "Asia/Omsk"},
    {"Pacific SA Standard Time", "America/Santiago"},
    {"Pacific Standard Time", "America/Los_Angeles"},
    {"Pacific Standard Time (Mexico)", "America/Tijuana"},
    {"Pakistan Standard Time", "Asia/Karachi"},
    {"Paraguay Standard Time", "America/Asuncion"},
    {"Qyzylorda Standard Time", "Asia/Qyzylorda"},
    {"Romance Standard Time", "Europe/Paris"},
    {"Russia Time Zone 10", "Asia/Srednekolymsk"},
    {"Russia Time Zone 11", "Asia/Kamchatka"},
    {"Russia Time Zone 3", "Europe/Samara"},
    {"Russian Standard Time", "Europe/Moscow"},
    {"SA Eastern Standard Time", "America/Cayenne"},
    {"SA Pacific Standard Time", "America/Bogota"},
    {"SA Western Standard Time", "America/La_Paz"},
    {"Saint Pierre Standard Time", "America/Miquelon"},
    {"Sakhalin Standard Time", "Asia/Sakhalin"},
    {"Samoa Standard Time", "Pacific/Apia"},
    {"Sao Tome Standard Time", "Africa/Sao_Tome"},
    {"Saratov Standard Time", "Europe/Saratov"},
    {"SE Asia Standard Time", "Asia/Bangkok"},
    {"Singapore Standard Time", "Asia/Singapore"},
    {"South Africa Standard Time", "Africa/Johannesburg"},
    {"South Sudan Standard Time", "Africa/Juba"},
    {"Sri Lanka Standard Time", "Asia/Colombo"},
    {"Sudan Standard Time", "Africa/Khartoum"},
    {"Syria Standard Time", "Asia/Damascus"},
    {"Taipei Standard Time", "Asia/Taipei"},
    {"Tasmania Standard Time", "Australia/Hobart"},
    {"Tocantins Standard Time", "America/Araguaina"},
    {"Tokyo Standard Time", "Asia/Tokyo"},
    {"Tomsk Standard Time", "Asia/Tomsk"},
    {"Tonga Standard Time", "Pacific/Tongatapu"},
    {"Transbaikal Standard Time", "Asia/Chita"},
    {"Turkey Standard Time", "Europe/Istanbul"},
    {"Turks And Caicos Standard Time", "America/Grand_Turk"},
    {"Ulaanbaatar Standard Time", "Asia/Ulaanbaatar"},
    {"US Eastern Standard Time", "America/Indianapolis"},
    {"US Mountain Standard Time", "America/Phoenix"},
    {"UTC", "Etc/UTC"},
    {"UTC+12", "Etc/GMT-12"},
    {"UTC+13", "Etc/GMT-13"},
    {"UTC-02", "Etc/GMT+2"},
    {"UTC-08", "Etc/GMT+8"},
    {"UTC-09", "Etc/GMT+9"},
    {"UTC-11", "Etc/GMT+11"},
    {"Venezuela Standard Time", "America/Caracas"},
    {"Vladivostok Standard Time", "Asia/Vladivostok"},
    {"Volgograd Standard Time", "Europe/Volgograd"},
    {"W. Australia Standard Time", "Australia/Perth"},
    {"W. Central Africa Standard Time", "Africa/Lagos"},
    {"W. Europe Standard Time", "Europe/Berlin"},
    {"W. Mongolia Standard Time", "Asia/Hovd"},
    {"West Asia Standard Time", "Asia/Tashkent"},
    {"West Bank Standard Time", "Asia/Hebron"},
    {"West Pacific Standard Time", "Pacific/Port_Moresby"},
    {"Yakutsk Standard Time", "Asia/Yakutsk"},
    {"Yukon Standard Time", "America/Whitehorse"},
}};

static_assert(std::ranges::is_sorted(windows_zones, [](const WindowsZone& a, const WindowsZone& b) {
	return ascii_case_compare(a.windows, b.windows) < 0;
}));

#ifdef _WIN32
// The IANA zone of the Windows zone key name of the system, or none when the name has no row. Windows zone names are
// ASCII, so a name with any other character has no row.
const std::chrono::time_zone* windows_system_zone() {
	DYNAMIC_TIME_ZONE_INFORMATION info{};
	if (GetDynamicTimeZoneInformation(&info) == TIME_ZONE_ID_INVALID) {
		return nullptr;
	}
	std::string name;
	for (const wchar_t c : info.TimeZoneKeyName) {
		if (c == L'\0') {
			break;
		}
		if (c > 0x7F) {
			return nullptr;
		}
		name.push_back(static_cast<char>(c));
	}
	const std::optional<std::string_view> iana = iana_for_windows(name);
	if (!iana) {
		return nullptr;
	}
	return std::chrono::locate_zone(*iana);
}
#endif

// The zone that a set TZ names, read as jiff 0.2.38 reads it (src/tz/system/mod.rs, get_env_tz). An empty value is
// UTC. A leading ':' is dropped. When the value holds "zoneinfo/", the part after its last "zoneinfo/" is the IANA
// name; otherwise the value is the IANA name. A name the database lacks throws, and local_zone gives UTC for it.
// Limit: a POSIX rule string such as "JST-9" or "EST5EDT,M3.2.0,M11.1.0" is not parsed, so it gives UTC, and
// jiff's TZif file path is not read. Upgrade path: a POSIX TZ parser, as jiff's jcore::tz::posix, that builds the
// zone from the rule.
const std::chrono::time_zone* zone_named_by_tz(std::string_view value) {
	if (value.empty()) {
		return std::chrono::locate_zone("UTC");
	}
	if (value.starts_with(':')) {
		value.remove_prefix(1);
	}
	constexpr std::string_view needle = "zoneinfo/";
	if (const std::size_t at = value.rfind(needle); at != std::string_view::npos) {
		value.remove_prefix(at + needle.size());
	}
	return std::chrono::locate_zone(value);
}

} // namespace

std::span<const WindowsZone> windows_zone_table() {
	return windows_zones;
}

std::optional<std::string_view> iana_for_windows(std::string_view windows_name) {
	const auto row = std::lower_bound(
	    windows_zones.begin(), windows_zones.end(), windows_name,
	    [](const WindowsZone& r, std::string_view name) { return ascii_case_compare(r.windows, name) < 0; });
	if (row == windows_zones.end() || ascii_case_compare(row->windows, windows_name) != 0) {
		return std::nullopt;
	}
	return row->iana;
}

std::int64_t today() {
	const std::chrono::zoned_time now{local_zone(), std::chrono::system_clock::now()};
	const std::chrono::year_month_day date{std::chrono::floor<std::chrono::days>(now.get_local_time())};
	return days::epoch_day(static_cast<std::int64_t>(static_cast<int>(date.year())),
	                       static_cast<std::uint32_t>(date.month()), static_cast<std::uint32_t>(date.day()));
}

bool uses_24_hours() {
#ifdef _WIN32
	// The short time format comes from the Regional settings in the registry, as Main.kt reads it.
	wchar_t format[64]{};
	DWORD bytes = sizeof(format);
	if (RegGetValueW(HKEY_CURRENT_USER, L"Control Panel\\International", L"sShortTime", RRF_RT_REG_SZ, nullptr, format,
	                 &bytes) == ERROR_SUCCESS) {
		return std::wcschr(format, L'H') != nullptr;
	}
#endif
	// Shortcut: elsewhere the locale name decides, and only the United States English locales use the
	// 12-hour clock. Upgrade path: read LC_TIME's t_fmt through nl_langinfo.
	const char* locale = nullptr;
	for (const char* key : {"LC_ALL", "LC_TIME", "LANG"}) {
		const char* value = std::getenv(key);
		if (value != nullptr && *value != '\0') {
			locale = value;
			break;
		}
	}
	if (locale == nullptr) {
		return false;
	}
	const std::string_view l = locale;
	return !(l.starts_with("en_US") || l == "C" || l == "POSIX");
}

const std::chrono::time_zone* local_zone() {
	try {
		// TZ set but empty is UTC, so getenv's empty string is told apart from an unset variable.
		if (const char* tz = std::getenv("TZ"); tz != nullptr) {
			return zone_named_by_tz(tz);
		}
#ifdef _WIN32
		if (const std::chrono::time_zone* zone = windows_system_zone()) {
			return zone;
		}
#else
		return std::chrono::current_zone();
#endif
	} catch (const std::exception&) {
		// A TZ name or a mapped zone that the database lacks falls through to UTC.
	}
	return std::chrono::locate_zone("UTC");
}

std::string os_error_text(int code) {
	std::string message;
#ifdef _WIN32
	// FormatMessageW with no inserts, in the user's language, as Rust's sys::os::error_string.
	wchar_t* buffer = nullptr;
	const DWORD n =
	    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
	                   nullptr, static_cast<DWORD>(code), 0, reinterpret_cast<wchar_t*>(&buffer), 0, nullptr);
	if (n > 0 && buffer != nullptr) {
		const int bytes = WideCharToMultiByte(CP_UTF8, 0, buffer, static_cast<int>(n), nullptr, 0, nullptr, nullptr);
		message.resize(static_cast<std::size_t>(bytes));
		WideCharToMultiByte(CP_UTF8, 0, buffer, static_cast<int>(n), message.data(), bytes, nullptr, nullptr);
	}
	if (buffer != nullptr) {
		LocalFree(buffer);
	}
	// Rust trims the trailing line end that FormatMessage adds.
	while (!message.empty() && (message.back() == '\n' || message.back() == '\r' || message.back() == ' ')) {
		message.pop_back();
	}
	if (message.empty()) {
		message = "Unknown error";
	}
#else
	message = std::generic_category().message(code);
#endif
	return message + " (os error " + std::to_string(code) + ")";
}

std::string os_error_text(const std::error_code& ec) {
	return os_error_text(ec.value());
}

std::string last_os_error_text() {
#ifdef _WIN32
	return os_error_text(static_cast<int>(GetLastError()));
#else
	return os_error_text(errno);
#endif
}

} // namespace palm::platform
