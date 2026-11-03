// Platform services of the desktop app: today's date in the local zone, the clock format, and the user's time zone.
#pragma once

#include <chrono>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <system_error>

namespace palm::platform {

// One row of the Windows-to-IANA table: a Windows time zone key name and the IANA zone it maps to.
struct WindowsZone {
	std::string_view windows;
	std::string_view iana;
};

// The Windows-to-IANA table, sorted by the ASCII lowercase of the Windows names.
std::span<const WindowsZone> windows_zone_table();

// The IANA name of a Windows time zone key name. The match ignores ASCII case. None when the table has no row for the
// name.
std::optional<std::string_view> iana_for_windows(std::string_view windows_name);

// Today's epoch day (days since 1970-01-01) in the local zone.
std::int64_t today();

// True when the system shows the time in 24-hour form.
bool uses_24_hours();

// The user's time zone for the iCalendar import: the zone named by TZ when it is set, else the system zone. A system
// zone with no IANA mapping, or any zone lookup that fails, gives UTC.
const std::chrono::time_zone* local_zone();

// The text of an OS error code as Rust's std::io::Error shows it: the system message, then " (os error N)",
// e.g. "Access is denied. (os error 5)" on Windows or "Permission denied (os error 13)" elsewhere.
std::string os_error_text(int code);

// os_error_text of an error_code that carries an OS code (system_category, or generic_category's errno).
std::string os_error_text(const std::error_code& ec);

// os_error_text of the last OS error: GetLastError() on Windows, errno elsewhere. Call it right after the
// failed call, before anything else can change the code.
std::string last_os_error_text();

} // namespace palm::platform
