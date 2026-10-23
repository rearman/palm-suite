//! The record model, repeat rules and display formats of Model.kt.

use crate::days::{dow, ymd};
use std::cmp::Ordering;
use std::collections::BTreeSet;

/// Repeat types, as in the Palm "Change Repeat" dialog.
pub mod repeat {
	pub const NONE: i32 = 0;
	pub const DAY: i32 = 1;
	pub const WEEK: i32 = 2;
	/// e.g. "3rd Monday of every month"
	pub const MONTH_DAY: i32 = 3;
	/// e.g. "the 12th of every month"
	pub const MONTH_DATE: i32 = 4;
	pub const YEAR: i32 = 5;
	/// A flag in `week_days` of a MONTH_DAY repeat, which uses no week days: the event falls on the last week
	/// of the month. It counts only for a first date in both the 4th and the last week, where the date alone
	/// cannot tell; a first date in the 5th week is the last week anyway.
	pub const LAST_WEEK: i32 = 0x80;
}

/// One Date Book record. A repeating record holds all of its occurrences.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Event {
	pub id: i64,
	pub title: String,
	pub note: String,
	/// First occurrence (epoch day).
	pub date: i64,
	pub untimed: bool,
	/// Minutes after midnight.
	pub start: i32,
	pub end: i32,
	pub alarm: bool,
	pub alarm_advance: i32,
	/// 0 minutes, 1 hours, 2 days.
	pub alarm_unit: i32,
	pub repeat: i32,
	pub every: i32,
	/// Epoch day, -1 = no end date.
	pub repeat_end: i64,
	/// Bit 0 = Sunday ... bit 6 = Saturday.
	pub week_days: i32,
	pub exceptions: BTreeSet<i64>,
	pub is_private: bool,
	/// The same id on every device, for sync.
	pub uid: String,
}

/// The Kotlin defaults, with date 0 (Kotlin's default is today; pass a date).
impl Default for Event {
	fn default() -> Event {
		Event {
			id: 0,
			title: String::new(),
			note: String::new(),
			date: 0,
			untimed: false,
			start: 8 * 60,
			end: 9 * 60,
			alarm: false,
			alarm_advance: 5,
			alarm_unit: 0,
			repeat: repeat::NONE,
			every: 1,
			repeat_end: -1,
			week_days: 0,
			exceptions: BTreeSet::new(),
			is_private: false,
			uid: String::new(),
		}
	}
}

impl Event {
	pub fn repeats(&self) -> bool {
		self.repeat != repeat::NONE
	}

	pub fn advance_minutes(&self) -> i64 {
		match self.alarm_unit {
			0 => self.alarm_advance as i64,
			1 => self.alarm_advance as i64 * 60,
			_ => self.alarm_advance as i64 * 1440,
		}
	}

	/// The record without its database id and sync uid and without the fields that change nothing on
	/// screen. Equal keys mean the same event. Import uses it to skip duplicates.
	pub fn dup_key(&self) -> Event {
		let mut k = Event {
			id: 0,
			uid: String::new(),
			date: first_day(self),
			..self.clone()
		};
		if self.untimed {
			k.start = 0;
			k.end = 0;
		}
		if !self.alarm {
			k.alarm_advance = 0;
			k.alarm_unit = 0;
		}
		if self.repeat == repeat::NONE {
			k.every = 1;
			k.repeat_end = -1;
			k.week_days = 0;
			k.exceptions = BTreeSet::new();
		} else if self.repeat == repeat::WEEK && self.week_days == 0 {
			k.week_days = 1 << dow(self.date);
		}
		k
	}
}

/// One To Do List record.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Todo {
	pub id: i64,
	pub title: String,
	pub note: String,
	pub priority: i32,
	/// Epoch day, -1 = no date.
	pub due: i64,
	pub done: bool,
	/// 0 = Unfiled.
	pub category: i32,
	pub is_private: bool,
	pub uid: String,
}

impl Default for Todo {
	fn default() -> Todo {
		Todo {
			id: 0,
			title: String::new(),
			note: String::new(),
			priority: 1,
			due: -1,
			done: false,
			category: 0,
			is_private: false,
			uid: String::new(),
		}
	}
}

impl Todo {
	pub fn dup_key(&self) -> Todo {
		Todo {
			id: 0,
			uid: String::new(),
			..self.clone()
		}
	}
}

/// A category of one app: `app` 0 is To Do, 1 is Memo Pad, 2 is Address Book.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct Category {
	pub id: i32,
	pub name: String,
	pub app: i32,
	pub uid: String,
}

/// Compares as Java's String.CASE_INSENSITIVE_ORDER: char by char, after upper and then lower case.
pub fn compare_ignore_case(a: &str, b: &str) -> Ordering {
	fn upper(c: char) -> char {
		let mut u = c.to_uppercase();
		match (u.next(), u.next()) {
			(Some(x), None) => x,
			_ => c,
		}
	}
	fn lower(c: char) -> char {
		let mut l = c.to_lowercase();
		match (l.next(), l.next()) {
			(Some(x), None) => x,
			_ => c,
		}
	}
	let (mut x, mut y) = (a.chars(), b.chars());
	loop {
		match (x.next(), y.next()) {
			(None, None) => return Ordering::Equal,
			(None, Some(_)) => return Ordering::Less,
			(Some(_), None) => return Ordering::Greater,
			(Some(c1), Some(c2)) if c1 != c2 => {
				let (u1, u2) = (upper(c1), upper(c2));
				if u1 != u2 {
					let (l1, l2) = (lower(u1), lower(u2));
					if l1 != l2 {
						return l1.cmp(&l2);
					}
				}
			}
			_ => {}
		}
	}
}

/// Sorts by name, ignoring case; equal names keep id order.
pub fn sort_categories(list: &mut [Category]) {
	list.sort_by(|a, b| compare_ignore_case(&a.name, &b.name).then(a.id.cmp(&b.id)));
}

/// A Memo Pad record. Its first line is its title in the Memo List.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct Memo {
	pub id: i64,
	pub text: String,
	pub category: i32,
	pub is_private: bool,
	pub pos: i32,
	pub uid: String,
}

impl Memo {
	pub fn title(&self) -> String {
		first_line(&self.text).trim().to_string()
	}

	pub fn dup_key(&self) -> Memo {
		Memo {
			id: 0,
			pos: 0,
			uid: String::new(),
			..self.clone()
		}
	}
}

/// The first line, as Kotlin's lineSequence splits at "\r\n", "\n" and "\r".
fn first_line(s: &str) -> &str {
	match s.find(['\n', '\r']) {
		Some(i) => &s[..i],
		None => s,
	}
}

/// Memo List order: by first line (ignoring case) when `alphabetic`, else by manual position.
pub fn memo_order(memos: &[Memo], alphabetic: bool) -> Vec<Memo> {
	let mut v = memos.to_vec();
	if alphabetic {
		v.sort_by(|a, b| {
			a.title()
				.to_lowercase()
				.cmp(&b.title().to_lowercase())
				.then(a.pos.cmp(&b.pos))
				.then(a.id.cmp(&b.id))
		});
	} else {
		v.sort_by(|a, b| a.pos.cmp(&b.pos).then(a.id.cmp(&b.id)));
	}
	v
}

/// Moves the memo at `from` to `to` in `ordered` and numbers the positions 0 up in the new order.
/// Returns the indexes, in the new order, of the memos whose position changed.
pub fn move_memo(ordered: &mut Vec<Memo>, from: usize, to: usize) -> Vec<usize> {
	let m = ordered.remove(from);
	ordered.insert(to, m);
	let mut changed = Vec::new();
	for (i, m) in ordered.iter_mut().enumerate() {
		if m.pos != i as i32 {
			m.pos = i as i32;
			changed.push(i);
		}
	}
	changed
}

/// The phone field labels of the Palm Address Book, in pop-up order.
pub const LABELS: [&str; 8] = ["Work", "Home", "Fax", "Other", "E-mail", "Main", "Pager", "Mobile"];
/// The letter the Address List shows after a number, one per label.
pub const LETTERS: &str = "WHFOEMPM";
pub const WORK: i32 = 0;
pub const HOME: i32 = 1;
pub const FAX: i32 = 2;
pub const OTHER: i32 = 3;
pub const EMAIL: i32 = 4;
pub const MAIN: i32 = 5;
pub const PAGER: i32 = 6;
pub const MOBILE: i32 = 7;

/// An Address Book record, after the Palm OS 3.5 Address Book.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Address {
	pub id: i64,
	pub last_name: String,
	pub first_name: String,
	pub title: String,
	pub company: String,
	pub phone_labels: Vec<i32>,
	pub phones: Vec<String>,
	pub address: String,
	pub city: String,
	pub state: String,
	pub zip: String,
	pub country: String,
	pub custom: Vec<String>,
	pub note: String,
	/// The phone field (0 to 4) that the Address List shows.
	pub show_phone: i32,
	pub category: i32,
	pub is_private: bool,
	pub uid: String,
}

impl Default for Address {
	fn default() -> Address {
		Address {
			id: 0,
			last_name: String::new(),
			first_name: String::new(),
			title: String::new(),
			company: String::new(),
			phone_labels: vec![0, 1, 2, 3, 4],
			phones: vec![String::new(); 5],
			address: String::new(),
			city: String::new(),
			state: String::new(),
			zip: String::new(),
			country: String::new(),
			custom: vec![String::new(); 4],
			note: String::new(),
			show_phone: 0,
			category: 0,
			is_private: false,
			uid: String::new(),
		}
	}
}

/// Kotlin's isBlank: empty or only whitespace.
pub fn is_blank(s: &str) -> bool {
	s.chars().all(char::is_whitespace)
}

impl Address {
	/// True when no field has text.
	pub fn is_empty(&self) -> bool {
		[
			&self.last_name,
			&self.first_name,
			&self.title,
			&self.company,
			&self.address,
			&self.city,
			&self.state,
			&self.zip,
			&self.country,
			&self.note,
		]
		.iter()
		.all(|s| is_blank(s))
			&& self.phones.iter().all(|s| is_blank(s))
			&& self.custom.iter().all(|s| is_blank(s))
	}

	/// The Address List name, by last name or by company.
	pub fn list_name(&self, by_company: bool) -> String {
		fn join(a: &str, b: &str) -> String {
			[a, b]
				.iter()
				.filter(|s| !is_blank(s))
				.copied()
				.collect::<Vec<_>>()
				.join(", ")
		}
		let mut name = if by_company {
			join(&self.company, &self.last_name)
		} else {
			join(&self.last_name, &self.first_name)
		};
		if is_blank(&name) {
			name = if by_company {
				self.first_name.clone()
			} else {
				self.company.clone()
			};
		}
		if is_blank(&name) {
			"-Unnamed-".to_string()
		} else {
			name
		}
	}

	/// The number the Address List shows and its letter, or None when all phones are empty.
	pub fn list_phone(&self) -> Option<(String, char)> {
		let shown = self
			.phones
			.get(self.show_phone as usize)
			.filter(|p| self.show_phone >= 0 && !is_blank(p));
		let i = if shown.is_some() {
			self.show_phone as usize
		} else {
			self.phones.iter().position(|p| !is_blank(p))?
		};
		let letter = self
			.phone_labels
			.get(i)
			.and_then(|l| LETTERS.chars().nth(*l as usize))
			.unwrap_or('O');
		Some((self.phones[i].clone(), letter))
	}

	pub fn dup_key(&self) -> Address {
		Address {
			id: 0,
			uid: String::new(),
			..self.clone()
		}
	}
}

/// Address List order: by the list name, ignoring case, then by id.
pub fn address_order(list: &[Address], by_company: bool) -> Vec<Address> {
	let mut v = list.to_vec();
	v.sort_by(|a, b| {
		a.list_name(by_company)
			.to_lowercase()
			.cmp(&b.list_name(by_company).to_lowercase())
			.then(a.id.cmp(&b.id))
	});
	v
}

/// Start times of the empty Day view lines (see Model.kt freeTimes).
pub fn free_times(timed: &[(i32, i32)], day_start: i32, day_end: i32, ends: bool) -> Vec<i32> {
	let busy = |a: i32, b: i32| timed.iter().any(|&(s, e)| s < b && e.max(s + 1) > a);
	let mut out = Vec::new();
	for hr in day_start..day_end {
		let a = hr * 60;
		if !busy(a, if ends { a + 1 } else { a + 60 }) {
			out.push(a);
		}
	}
	if ends {
		for &(_, t) in timed {
			if t % 60 != 0 && t < 24 * 60 && !busy(t, t + 1) && !out.contains(&t) {
				out.push(t);
			}
		}
	}
	out.sort();
	out
}

/// Week of the month, 1 to 5.
pub fn week_index(day: i64) -> u32 {
	(ymd(day).day - 1) / 7 + 1
}

pub fn is_last_week(day: i64) -> bool {
	ymd(day + 7).month != ymd(day).month
}

/// The week of the month of a MONTH_DAY repeat, 1 to 4, or 5 for the last week.
pub fn month_week(e: &Event) -> u32 {
	let w = week_index(e.date);
	if w == 4 && is_last_week(e.date) && e.week_days & repeat::LAST_WEEK != 0 {
		5
	} else {
		w
	}
}

fn months_between(a: i64, b: i64) -> i64 {
	let (a, b) = (ymd(a), ymd(b));
	(b.year - a.year) * 12 + (b.month as i64 - a.month as i64)
}

/// The first day `e` occurs, exceptions aside. A weekly event can start on a day that is not one of its days.
pub fn first_day(e: &Event) -> i64 {
	if e.repeat != repeat::WEEK || e.week_days == 0 || (e.week_days & (1 << dow(e.date))) != 0 {
		return e.date;
	}
	let plain = Event {
		exceptions: BTreeSet::new(),
		..e.clone()
	};
	let stop = e.date + 7 * (e.every.max(1) as i64 + 1);
	(e.date..stop).find(|d| occurs_on(&plain, *d)).unwrap_or(e.date)
}

pub fn occurs_on(e: &Event, day: i64) -> bool {
	if day < e.date {
		return false;
	}
	if e.repeat == repeat::NONE {
		return day == e.date;
	}
	if e.repeat_end >= 0 && day > e.repeat_end {
		return false;
	}
	if e.exceptions.contains(&day) {
		return false;
	}
	let (s, d) = (ymd(e.date), ymd(day));
	let n = e.every.max(1) as i64;
	match e.repeat {
		repeat::DAY => (day - e.date) % n == 0,
		repeat::WEEK => {
			let mask = if e.week_days == 0 {
				1 << dow(e.date)
			} else {
				e.week_days
			};
			if mask & (1 << dow(day)) == 0 {
				false
			} else {
				let week_of_start = e.date - dow(e.date) as i64;
				let week_of_day = day - dow(day) as i64;
				((week_of_day - week_of_start) / 7) % n == 0
			}
		}
		repeat::MONTH_DATE => d.day == s.day && months_between(e.date, day) % n == 0,
		repeat::MONTH_DAY => {
			if dow(day) != dow(e.date) || months_between(e.date, day) % n != 0 {
				false
			} else if month_week(e) == 5 {
				is_last_week(day)
			} else {
				week_index(day) == week_index(e.date)
			}
		}
		repeat::YEAR => d.month == s.month && d.day == s.day && (d.year - s.year) % n == 0,
		_ => false,
	}
}

/// True when the event repeats every day (used by the Month view option).
pub fn is_daily(e: &Event) -> bool {
	(e.repeat == repeat::DAY && e.every == 1) || (e.repeat == repeat::WEEK && e.every == 1 && e.week_days == 0x7F)
}

/// The last day the event can occur, or i64::MAX if it has no end.
pub fn last_day(e: &Event) -> i64 {
	if e.repeat == repeat::NONE {
		e.date
	} else if e.repeat_end >= 0 {
		e.repeat_end
	} else {
		i64::MAX
	}
}

const ORDINAL_WEEK: [&str; 6] = ["", "1st", "2nd", "3rd", "4th", "last"];

pub fn describe(e: &Event) -> String {
	let s = ymd(e.date);
	let n = e.every.max(1);
	let base = match e.repeat {
		repeat::NONE => return "None".to_string(),
		repeat::DAY => {
			if n == 1 {
				"Every day".to_string()
			} else {
				format!("Every {n} days")
			}
		}
		repeat::WEEK => {
			let mask = if e.week_days == 0 {
				1 << dow(e.date)
			} else {
				e.week_days
			};
			let names = (0..7)
				.filter(|i| mask & (1 << i) != 0)
				.map(|i| DOW_SHORT[i])
				.collect::<Vec<_>>()
				.join(", ");
			if n == 1 {
				format!("Every week on {names}")
			} else {
				format!("Every {n} weeks on {names}")
			}
		}
		repeat::MONTH_DAY => format!(
			"The {} {} of {}",
			ORDINAL_WEEK[month_week(e) as usize],
			DOW_LONG[dow(e.date) as usize],
			if n == 1 {
				"every month".to_string()
			} else {
				format!("every {n} months")
			}
		),
		repeat::MONTH_DATE => format!(
			"The {} of {}",
			ordinal(s.day as i32),
			if n == 1 {
				"every month".to_string()
			} else {
				format!("every {n} months")
			}
		),
		repeat::YEAR => format!(
			"{}{} {}",
			if n == 1 {
				"Every year on ".to_string()
			} else {
				format!("Every {n} years on ")
			},
			MONTH_SHORT[s.month as usize - 1],
			s.day
		),
		_ => String::new(),
	};
	if e.repeat_end >= 0 {
		format!("{base} until {}", short_date(e.repeat_end))
	} else {
		base
	}
}

pub fn short_name(e: &Event) -> &'static str {
	match e.repeat {
		repeat::DAY => "Daily",
		repeat::WEEK => "Weekly",
		repeat::MONTH_DAY | repeat::MONTH_DATE => "Monthly",
		repeat::YEAR => "Yearly",
		_ => "None",
	}
}

pub const DOW_SHORT: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
pub const DOW_LONG: [&str; 7] = [
	"Sunday",
	"Monday",
	"Tuesday",
	"Wednesday",
	"Thursday",
	"Friday",
	"Saturday",
];
pub const DOW_LETTER: [&str; 7] = ["S", "M", "T", "W", "T", "F", "S"];
pub const MONTH_SHORT: [&str; 12] = [
	"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

pub fn ordinal(n: i32) -> String {
	let suf = if (11..=13).contains(&n) {
		"th"
	} else {
		match n % 10 {
			1 => "st",
			2 => "nd",
			3 => "rd",
			_ => "th",
		}
	};
	format!("{n}{suf}")
}

/// Kotlin's "%02d".format(year % 100).
fn yy(year: i64) -> String {
	format!("{:02}", year % 100)
}

/// Day view title, "YY MMM D": "26 Oct 13".
pub fn day_title(day: i64) -> String {
	let d = ymd(day);
	format!("{} {} {}", yy(d.year), MONTH_SHORT[d.month as usize - 1], d.day)
}

/// "10/12/26".
pub fn short_date(day: i64) -> String {
	let d = ymd(day);
	format!("{}/{}/{}", d.month, d.day, yy(d.year))
}

/// "Mon 10/12/26".
pub fn dow_date(day: i64) -> String {
	format!("{} {}", DOW_SHORT[dow(day) as usize], short_date(day))
}

/// Time with am/pm, or 24 h: "8:00 am".
pub fn time(min: i32, h24: bool) -> String {
	let h = (min / 60) % 24;
	let m = min % 60;
	if h24 {
		return format!("{h}:{m:02}");
	}
	let h12 = if h % 12 == 0 { 12 } else { h % 12 };
	format!("{h12}:{m:02} {}", if h < 12 { "am" } else { "pm" })
}

/// Short time for the Day view time column: "8:00".
pub fn time_short(min: i32, h24: bool) -> String {
	let h = (min / 60) % 24;
	let m = min % 60;
	if h24 {
		return format!("{h}:{m:02}");
	}
	let h12 = if h % 12 == 0 { 12 } else { h % 12 };
	format!("{h12}:{m:02}")
}

pub fn range(e: &Event, h24: bool) -> String {
	if e.untimed {
		"No time".to_string()
	} else {
		format!("{} - {}", time(e.start, h24), time(e.end, h24))
	}
}

/// Splits a repeating series at `occ_day` for "this & all Future". Returns the shortened original, or
/// None when `occ_day` is the first occurrence, and the new series that starts at `work`.
pub fn split_future(orig: &Event, occ_day: i64, work: &Event) -> (Option<Event>, Event) {
	let shortened = if occ_day <= orig.date {
		None
	} else {
		let mut s = orig.clone();
		s.repeat_end = occ_day - 1;
		s.exceptions.retain(|x| *x < occ_day);
		Some(s)
	};
	let shift = work.date - occ_day;
	let mut series = work.clone();
	series.id = 0;
	series.exceptions = work
		.exceptions
		.iter()
		.filter(|x| **x >= occ_day)
		.map(|x| x + shift)
		.collect();
	(shortened, series)
}
