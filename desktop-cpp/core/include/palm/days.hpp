// Calendar dates as epoch days (days since 1970-01-01), as Kotlin's LocalDate.ofEpochDay gives them.
// The conversions work for every std::int64_t day that maps to a std::int64_t year, so stored data never fails here.
#pragma once

#include <compare>
#include <cstdint>

namespace palm::days {

// A proleptic Gregorian date.
struct Ymd {
	std::int64_t year;
	// 1 to 12.
	std::uint32_t month;
	// 1 to 31.
	std::uint32_t day;

	auto operator<=>(const Ymd&) const = default;
};

// The date of an epoch day (H. Hinnant's civil_from_days).
Ymd ymd(std::int64_t epoch_day);

// The epoch day of a date (H. Hinnant's days_from_civil). The day may run past the month end.
std::int64_t epoch_day(std::int64_t year, std::uint32_t month, std::uint32_t day);

bool is_leap(std::int64_t year);

std::uint32_t month_length(std::int64_t year, std::uint32_t month);

// Day of the week with Sunday = 0, as Recur.dow.
std::uint32_t dow(std::int64_t epoch_day);

// Rust's i64::wrapping_add, as the release build and Kotlin's Long behave.
constexpr std::int64_t wrapping_add(std::int64_t a, std::int64_t b) {
	return static_cast<std::int64_t>(static_cast<std::uint64_t>(a) + static_cast<std::uint64_t>(b));
}

// Rust's i64::wrapping_sub, as the release build and Kotlin's Long behave.
constexpr std::int64_t wrapping_sub(std::int64_t a, std::int64_t b) {
	return static_cast<std::int64_t>(static_cast<std::uint64_t>(a) - static_cast<std::uint64_t>(b));
}

// Rust's i64::wrapping_mul, as the release build and Kotlin's Long behave.
constexpr std::int64_t wrapping_mul(std::int64_t a, std::int64_t b) {
	return static_cast<std::int64_t>(static_cast<std::uint64_t>(a) * static_cast<std::uint64_t>(b));
}

} // namespace palm::days
