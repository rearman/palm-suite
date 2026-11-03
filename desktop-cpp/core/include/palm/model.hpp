// The record model, repeat rules and display formats of Model.kt, as desktop-rs/core/src/model.rs.
#pragma once

#include <array>
#include <compare>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::model {

// Repeat types, as in the Palm "Change Repeat" dialog.
namespace repeat {
inline constexpr std::int32_t NONE = 0;
inline constexpr std::int32_t DAY = 1;
inline constexpr std::int32_t WEEK = 2;
// e.g. "3rd Monday of every month"
inline constexpr std::int32_t MONTH_DAY = 3;
// e.g. "the 12th of every month"
inline constexpr std::int32_t MONTH_DATE = 4;
inline constexpr std::int32_t YEAR = 5;
// A flag in `week_days` of a MONTH_DAY repeat, which uses no week days: the event falls on the last week
// of the month. It counts only for a first date in both the 4th and the last week, where the date alone
// cannot tell; a first date in the 5th week is the last week anyway.
inline constexpr std::int32_t LAST_WEEK = 0x80;
} // namespace repeat

// One Date Book record. A repeating record holds all of its occurrences.
// The Kotlin defaults, with date 0 (Kotlin's default is today; pass a date).
struct Event {
	std::int64_t id = 0;
	std::string title;
	std::string note;
	// First occurrence (epoch day).
	std::int64_t date = 0;
	bool untimed = false;
	// Minutes after midnight.
	std::int32_t start = 8 * 60;
	std::int32_t end = 9 * 60;
	bool alarm = false;
	std::int32_t alarm_advance = 5;
	// 0 minutes, 1 hours, 2 days.
	std::int32_t alarm_unit = 0;
	std::int32_t repeat = repeat::NONE;
	std::int32_t every = 1;
	// Epoch day, -1 = no end date.
	std::int64_t repeat_end = -1;
	// Bit 0 = Sunday ... bit 6 = Saturday.
	std::int32_t week_days = 0;
	std::set<std::int64_t> exceptions;
	bool is_private = false;
	// The same id on every device, for sync.
	std::string uid;

	bool operator==(const Event&) const = default;

	bool repeats() const;

	std::int64_t advance_minutes() const;

	// The record without its database id and sync uid and without the fields that change nothing on
	// screen. Equal keys mean the same event. Import uses it to skip duplicates.
	Event dup_key() const;
};

// One To Do List record.
struct Todo {
	std::int64_t id = 0;
	std::string title;
	std::string note;
	std::int32_t priority = 1;
	// Epoch day, -1 = no date.
	std::int64_t due = -1;
	bool done = false;
	// 0 = Unfiled.
	std::int32_t category = 0;
	bool is_private = false;
	std::string uid;

	bool operator==(const Todo&) const = default;

	Todo dup_key() const;
};

// A category of one app: `app` 0 is To Do, 1 is Memo Pad, 2 is Address Book.
struct Category {
	std::int32_t id = 0;
	std::string name;
	std::int32_t app = 0;
	std::string uid;

	bool operator==(const Category&) const = default;
};

// Compares as Java's String.CASE_INSENSITIVE_ORDER: char by char, after upper and then lower case.
// The case mapping covers ASCII, Latin-1 Supplement and Latin Extended-A, which give the same results as
// Rust's char::to_uppercase and char::to_lowercase on those characters. Other characters keep their case
// here, so the order of text beyond Latin Extended-A can differ from Rust. The upgrade path is ICU's simple
// case mapping (u_toupper and u_tolower).
std::strong_ordering compare_ignore_case(std::string_view a, std::string_view b);

// Sorts by name, ignoring case; equal names keep id order.
void sort_categories(std::span<Category> list);

// A Memo Pad record. Its first line is its title in the Memo List.
struct Memo {
	std::int64_t id = 0;
	std::string text;
	std::int32_t category = 0;
	bool is_private = false;
	std::int32_t pos = 0;
	std::string uid;

	bool operator==(const Memo&) const = default;

	std::string title() const;

	Memo dup_key() const;
};

// Memo List order: by first line (ignoring case) when `alphabetic`, else by manual position.
std::vector<Memo> memo_order(std::span<const Memo> memos, bool alphabetic);

// Moves the memo at `from` to `to` in `ordered` and numbers the positions 0 up in the new order.
// Returns the indexes, in the new order, of the memos whose position changed. Throws std::runtime_error
// when an index is out of range, as Rust's Vec::remove and Vec::insert panic.
std::vector<std::size_t> move_memo(std::vector<Memo>& ordered, std::size_t from, std::size_t to);

// The phone field labels of the Palm Address Book, in pop-up order.
inline constexpr std::array<std::string_view, 8> LABELS = {"Work",   "Home", "Fax",   "Other",
                                                           "E-mail", "Main", "Pager", "Mobile"};
// The letter the Address List shows after a number, one per label.
inline constexpr std::string_view LETTERS = "WHFOEMPM";
inline constexpr std::int32_t WORK = 0;
inline constexpr std::int32_t HOME = 1;
inline constexpr std::int32_t FAX = 2;
inline constexpr std::int32_t OTHER = 3;
inline constexpr std::int32_t EMAIL = 4;
inline constexpr std::int32_t MAIN = 5;
inline constexpr std::int32_t PAGER = 6;
inline constexpr std::int32_t MOBILE = 7;

// An Address Book record, after the Palm OS 3.5 Address Book.
struct Address {
	std::int64_t id = 0;
	std::string last_name;
	std::string first_name;
	std::string title;
	std::string company;
	std::vector<std::int32_t> phone_labels{0, 1, 2, 3, 4};
	std::vector<std::string> phones = std::vector<std::string>(5);
	std::string address;
	std::string city;
	std::string state;
	std::string zip;
	std::string country;
	std::vector<std::string> custom = std::vector<std::string>(4);
	std::string note;
	// The phone field (0 to 4) that the Address List shows.
	std::int32_t show_phone = 0;
	std::int32_t category = 0;
	bool is_private = false;
	std::string uid;

	bool operator==(const Address&) const = default;

	// True when no field has text.
	bool is_empty() const;

	// The Address List name, by last name or by company.
	std::string list_name(bool by_company) const;

	// The number the Address List shows and its letter, or nullopt when all phones are empty.
	std::optional<std::pair<std::string, char>> list_phone() const;

	Address dup_key() const;
};

// Kotlin's isBlank: empty or only whitespace.
bool is_blank(std::string_view s);

// Address List order: by the list name, ignoring case, then by id.
std::vector<Address> address_order(std::span<const Address> list, bool by_company);

// Start times of the empty Day view lines (see Model.kt freeTimes).
std::vector<std::int32_t> free_times(std::span<const std::pair<std::int32_t, std::int32_t>> timed,
                                     std::int32_t day_start, std::int32_t day_end, bool ends);

// Week of the month, 1 to 5.
std::uint32_t week_index(std::int64_t day);

bool is_last_week(std::int64_t day);

// The week of the month of a MONTH_DAY repeat, 1 to 4, or 5 for the last week.
std::uint32_t month_week(const Event& e);

// The first day `e` occurs, exceptions aside. A weekly event can start on a day that is not one of its days.
std::int64_t first_day(const Event& e);

bool occurs_on(const Event& e, std::int64_t day);

// True when the event repeats every day (used by the Month view option).
bool is_daily(const Event& e);

// The last day the event can occur, or INT64_MAX if it has no end.
std::int64_t last_day(const Event& e);

std::string describe(const Event& e);

std::string_view short_name(const Event& e);

inline constexpr std::array<std::string_view, 7> DOW_SHORT = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
inline constexpr std::array<std::string_view, 7> DOW_LONG = {"Sunday",   "Monday", "Tuesday", "Wednesday",
                                                             "Thursday", "Friday", "Saturday"};
inline constexpr std::array<std::string_view, 7> DOW_LETTER = {"S", "M", "T", "W", "T", "F", "S"};
inline constexpr std::array<std::string_view, 12> MONTH_SHORT = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                                                                 "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

std::string ordinal(std::int32_t n);

// Day view title, "YY MMM D": "26 Oct 13".
std::string day_title(std::int64_t day);

// "10/12/26".
std::string short_date(std::int64_t day);

// "Mon 10/12/26".
std::string dow_date(std::int64_t day);

// Time with am/pm, or 24 h: "8:00 am".
std::string time(std::int32_t min, bool h24);

// Short time for the Day view time column: "8:00".
std::string time_short(std::int32_t min, bool h24);

std::string range(const Event& e, bool h24);

// Splits a repeating series at `occ_day` for "this & all Future". Returns the shortened original, or
// nullopt when `occ_day` is the first occurrence, and the new series that starts at `work`.
std::pair<std::optional<Event>, Event> split_future(const Event& orig, std::int64_t occ_day, const Event& work);

} // namespace palm::model
