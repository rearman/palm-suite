#include "palm/days.hpp"

namespace palm::days {

namespace {

// Euclidean division as Rust's div_euclid gives it, for a divisor above zero: the remainder is never negative.
// The known limit is a divisor of zero or below, which the callers never pass.
std::int64_t div_euclid(std::int64_t a, std::int64_t b) {
	std::int64_t q = a / b;
	if (a % b < 0) {
		--q;
	}
	return q;
}

// Euclidean remainder as Rust's rem_euclid gives it, for a divisor above zero: the result lies in 0 to b - 1.
// The known limit is a divisor of zero or below, which the callers never pass.
std::int64_t rem_euclid(std::int64_t a, std::int64_t b) {
	std::int64_t r = a % b;
	if (r < 0) {
		r += b;
	}
	return r;
}

} // namespace

// The date of an epoch day (H. Hinnant's civil_from_days).
Ymd ymd(std::int64_t epoch_day) {
	std::int64_t z = wrapping_add(epoch_day, 719468);
	std::int64_t era = div_euclid(z, 146097);
	std::int64_t doe = rem_euclid(z, 146097);
	std::int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
	std::int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
	std::int64_t mp = (5 * doy + 2) / 153;
	auto day = static_cast<std::uint32_t>(doy - (153 * mp + 2) / 5 + 1);
	auto month = static_cast<std::uint32_t>(mp < 10 ? mp + 3 : mp - 9);
	std::int64_t year = yoe + era * 400 + (month <= 2 ? 1 : 0);
	return Ymd{year, month, day};
}

// The epoch day of a date (H. Hinnant's days_from_civil). The day may run past the month end.
std::int64_t epoch_day(std::int64_t year, std::uint32_t month, std::uint32_t day) {
	std::int64_t y = month <= 2 ? wrapping_sub(year, 1) : year;
	std::int64_t era = div_euclid(y, 400);
	std::int64_t yoe = rem_euclid(y, 400);
	auto m = static_cast<std::int64_t>(month);
	std::int64_t doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + static_cast<std::int64_t>(day) - 1;
	std::int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
	return wrapping_sub(wrapping_add(wrapping_mul(era, 146097), doe), 719468);
}

bool is_leap(std::int64_t year) {
	return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
}

std::uint32_t month_length(std::int64_t year, std::uint32_t month) {
	switch (month) {
	case 2:
		return is_leap(year) ? 29 : 28;
	case 4:
	case 6:
	case 9:
	case 11:
		return 30;
	default:
		return 31;
	}
}

// Day of the week with Sunday = 0, as Recur.dow.
std::uint32_t dow(std::int64_t epoch_day) {
	// 1970-01-01 was a Thursday.
	return static_cast<std::uint32_t>(rem_euclid(wrapping_add(epoch_day, 4), 7));
}

} // namespace palm::days
