//! Calendar dates as epoch days (days since 1970-01-01), as Kotlin's LocalDate.ofEpochDay gives them.
//! The conversions work for every i64 day that maps to an i64 year, so stored data never fails here.

/// A proleptic Gregorian date.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct Ymd {
	pub year: i64,
	/// 1 to 12.
	pub month: u32,
	/// 1 to 31.
	pub day: u32,
}

/// The date of an epoch day (H. Hinnant's civil_from_days).
pub fn ymd(epoch_day: i64) -> Ymd {
	let z = epoch_day + 719_468;
	let era = z.div_euclid(146_097);
	let doe = z.rem_euclid(146_097);
	let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
	let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
	let mp = (5 * doy + 2) / 153;
	let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
	let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
	let year = yoe + era * 400 + if month <= 2 { 1 } else { 0 };
	Ymd { year, month, day }
}

/// The epoch day of a date (H. Hinnant's days_from_civil). The day may run past the month end.
pub fn epoch_day(year: i64, month: u32, day: u32) -> i64 {
	let y = if month <= 2 { year - 1 } else { year };
	let era = y.div_euclid(400);
	let yoe = y.rem_euclid(400);
	let m = month as i64;
	let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + day as i64 - 1;
	let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
	era * 146_097 + doe - 719_468
}

pub fn is_leap(year: i64) -> bool {
	year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}

pub fn month_length(year: i64, month: u32) -> u32 {
	match month {
		2 if is_leap(year) => 29,
		2 => 28,
		4 | 6 | 9 | 11 => 30,
		_ => 31,
	}
}

/// Day of the week with Sunday = 0, as Recur.dow.
pub fn dow(epoch_day: i64) -> u32 {
	// 1970-01-01 was a Thursday.
	(epoch_day + 4).rem_euclid(7) as u32
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn round_trips_over_wide_ranges() {
		for d in (-800_000..800_000).step_by(37) {
			let y = ymd(d);
			assert_eq!(epoch_day(y.year, y.month, y.day), d, "{d}");
			assert!(y.day >= 1 && y.day <= month_length(y.year, y.month));
		}
		assert_eq!(
			ymd(0),
			Ymd {
				year: 1970,
				month: 1,
				day: 1
			}
		);
		assert_eq!(epoch_day(2024, 2, 29), 19_782);
		assert_eq!(dow(epoch_day(2026, 10, 22)), 4);
	}
}
