#include "palm/model.hpp"

#include "palm/days.hpp"
#include "palm/text.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <format>
#include <limits>
#include <optional>
#include <set>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::model {

namespace {

// The first line, as Kotlin's lineSequence splits at "\r\n", "\n" and "\r".
std::string_view first_line(std::string_view s) {
	std::size_t i = s.find_first_of("\r\n");
	if (i == std::string_view::npos) {
		return s;
	}
	return s.substr(0, i);
}

// Joins two names with ", ", leaving out a blank one, as the Address List does.
std::string join_names(std::string_view a, std::string_view b) {
	std::string out;
	for (std::string_view part : {a, b}) {
		if (is_blank(part)) {
			continue;
		}
		if (!out.empty()) {
			out += ", ";
		}
		out += part;
	}
	return out;
}

constexpr std::array<std::string_view, 6> ORDINAL_WEEK = {"", "1st", "2nd", "3rd", "4th", "last"};

// The whole months from `from` to `to`. The callers pass a `to` that is not before `from`, so the count is not
// negative.
std::int64_t months_between(std::int64_t from, std::int64_t to) {
	days::Ymd a = days::ymd(from);
	days::Ymd b = days::ymd(to);
	return (b.year - a.year) * 12 + (static_cast<std::int64_t>(b.month) - static_cast<std::int64_t>(a.month));
}

// Kotlin's "%02d".format(year % 100). Rust and std::format both pad a negative value after its sign.
std::string yy(std::int64_t year) {
	return std::format("{:02}", year % 100);
}

} // namespace

bool Event::repeats() const {
	return repeat != repeat::NONE;
}

std::int64_t Event::advance_minutes() const {
	switch (alarm_unit) {
	case 0:
		return alarm_advance;
	case 1:
		return static_cast<std::int64_t>(alarm_advance) * 60;
	default:
		return static_cast<std::int64_t>(alarm_advance) * 1440;
	}
}

Event Event::dup_key() const {
	Event k = *this;
	k.id = 0;
	k.uid.clear();
	k.date = first_day(*this);
	if (untimed) {
		k.start = 0;
		k.end = 0;
	}
	if (!alarm) {
		k.alarm_advance = 0;
		k.alarm_unit = 0;
	}
	if (repeat == repeat::NONE) {
		k.every = 1;
		k.repeat_end = -1;
		k.week_days = 0;
		k.exceptions.clear();
	} else if (repeat == repeat::WEEK && week_days == 0) {
		k.week_days = std::int32_t{1} << days::dow(date);
	}
	return k;
}

Todo Todo::dup_key() const {
	Todo k = *this;
	k.id = 0;
	k.uid.clear();
	return k;
}

std::strong_ordering compare_ignore_case(std::string_view a, std::string_view b) {
	std::size_t i = 0;
	std::size_t j = 0;
	while (true) {
		bool more_a = i < a.size();
		bool more_b = j < b.size();
		if (!more_a && !more_b) {
			return std::strong_ordering::equal;
		}
		if (!more_a) {
			return std::strong_ordering::less;
		}
		if (!more_b) {
			return std::strong_ordering::greater;
		}
		text::Decoded c1 = text::decode_at(a, i);
		text::Decoded c2 = text::decode_at(b, j);
		i += c1.len;
		j += c2.len;
		if (c1.cp != c2.cp) {
			char32_t u1 = text::upper_one(c1.cp);
			char32_t u2 = text::upper_one(c2.cp);
			if (u1 != u2) {
				char32_t l1 = text::lower_one(u1);
				char32_t l2 = text::lower_one(u2);
				if (l1 != l2) {
					return l1 <=> l2;
				}
			}
		}
	}
}

void sort_categories(std::span<Category> list) {
	std::stable_sort(list.begin(), list.end(), [](const Category& a, const Category& b) {
		auto order = compare_ignore_case(a.name, b.name);
		if (order != 0) {
			return order < 0;
		}
		return a.id < b.id;
	});
}

std::string Memo::title() const {
	return std::string(text::trim(first_line(text)));
}

Memo Memo::dup_key() const {
	Memo k = *this;
	k.id = 0;
	k.pos = 0;
	k.uid.clear();
	return k;
}

std::vector<Memo> memo_order(std::span<const Memo> memos, bool alphabetic) {
	std::vector<Memo> v(memos.begin(), memos.end());
	if (alphabetic) {
		std::stable_sort(v.begin(), v.end(), [](const Memo& a, const Memo& b) {
			std::string la = text::to_lowercase(a.title());
			std::string lb = text::to_lowercase(b.title());
			if (la != lb) {
				return la < lb;
			}
			if (a.pos != b.pos) {
				return a.pos < b.pos;
			}
			return a.id < b.id;
		});
	} else {
		std::stable_sort(v.begin(), v.end(), [](const Memo& a, const Memo& b) {
			if (a.pos != b.pos) {
				return a.pos < b.pos;
			}
			return a.id < b.id;
		});
	}
	return v;
}

std::vector<std::size_t> move_memo(std::vector<Memo>& ordered, std::size_t from, std::size_t to) {
	std::size_t n = ordered.size();
	if (from >= n) {
		throw std::runtime_error("move_memo: removal index is out of range");
	}
	if (to >= n) {
		throw std::runtime_error("move_memo: insertion index is out of range");
	}
	Memo m = ordered[from];
	ordered.erase(ordered.begin() + static_cast<std::ptrdiff_t>(from));
	ordered.insert(ordered.begin() + static_cast<std::ptrdiff_t>(to), std::move(m));
	std::vector<std::size_t> changed;
	for (std::size_t i = 0; i < ordered.size(); ++i) {
		auto pos = static_cast<std::int32_t>(i);
		if (ordered[i].pos != pos) {
			ordered[i].pos = pos;
			changed.push_back(i);
		}
	}
	return changed;
}

bool Address::is_empty() const {
	const std::array<const std::string*, 10> text = {&last_name, &first_name, &title, &company, &address,
	                                                 &city,      &state,      &zip,   &country, &note};
	for (const std::string* field : text) {
		if (!is_blank(*field)) {
			return false;
		}
	}
	for (const std::string& p : phones) {
		if (!is_blank(p)) {
			return false;
		}
	}
	for (const std::string& c : custom) {
		if (!is_blank(c)) {
			return false;
		}
	}
	return true;
}

std::string Address::list_name(bool by_company) const {
	std::string name = by_company ? join_names(company, last_name) : join_names(last_name, first_name);
	if (is_blank(name)) {
		name = by_company ? first_name : company;
	}
	if (is_blank(name)) {
		return "-Unnamed-";
	}
	return name;
}

std::optional<std::pair<std::string, char>> Address::list_phone() const {
	bool shown = show_phone >= 0 && static_cast<std::size_t>(show_phone) < phones.size() &&
	             !is_blank(phones[static_cast<std::size_t>(show_phone)]);
	std::size_t i = 0;
	if (shown) {
		i = static_cast<std::size_t>(show_phone);
	} else {
		bool found = false;
		for (std::size_t k = 0; k < phones.size(); ++k) {
			if (!is_blank(phones[k])) {
				i = k;
				found = true;
				break;
			}
		}
		if (!found) {
			return std::nullopt;
		}
	}
	char letter = 'O';
	if (i < phone_labels.size()) {
		std::int32_t label = phone_labels[i];
		if (label >= 0 && static_cast<std::size_t>(label) < LETTERS.size()) {
			letter = LETTERS[static_cast<std::size_t>(label)];
		}
	}
	return std::pair<std::string, char>{phones[i], letter};
}

Address Address::dup_key() const {
	Address k = *this;
	k.id = 0;
	k.uid.clear();
	return k;
}

bool is_blank(std::string_view s) {
	std::size_t i = 0;
	while (i < s.size()) {
		text::Decoded d = text::decode_at(s, i);
		if (!text::is_white_space(d.cp)) {
			return false;
		}
		i += d.len;
	}
	return true;
}

std::vector<Address> address_order(std::span<const Address> list, bool by_company) {
	std::vector<Address> v(list.begin(), list.end());
	std::stable_sort(v.begin(), v.end(), [by_company](const Address& a, const Address& b) {
		std::string la = text::to_lowercase(a.list_name(by_company));
		std::string lb = text::to_lowercase(b.list_name(by_company));
		if (la != lb) {
			return la < lb;
		}
		return a.id < b.id;
	});
	return v;
}

std::vector<std::int32_t> free_times(std::span<const std::pair<std::int32_t, std::int32_t>> timed,
                                     std::int32_t day_start, std::int32_t day_end, bool ends) {
	auto busy = [timed](std::int32_t a, std::int32_t b) {
		return std::any_of(timed.begin(), timed.end(), [a, b](const std::pair<std::int32_t, std::int32_t>& p) {
			std::int32_t s = p.first;
			std::int32_t e = p.second;
			return s < b && std::max(e, s + 1) > a;
		});
	};
	std::vector<std::int32_t> out;
	for (std::int32_t hr = day_start; hr < day_end; ++hr) {
		std::int32_t a = hr * 60;
		if (!busy(a, ends ? a + 1 : a + 60)) {
			out.push_back(a);
		}
	}
	if (ends) {
		for (const std::pair<std::int32_t, std::int32_t>& p : timed) {
			std::int32_t t = p.second;
			if (t % 60 != 0 && t < 24 * 60 && !busy(t, t + 1) && std::find(out.begin(), out.end(), t) == out.end()) {
				out.push_back(t);
			}
		}
	}
	std::sort(out.begin(), out.end());
	return out;
}

std::uint32_t week_index(std::int64_t day) {
	return (days::ymd(day).day - 1) / 7 + 1;
}

bool is_last_week(std::int64_t day) {
	return days::ymd(days::wrapping_add(day, 7)).month != days::ymd(day).month;
}

std::uint32_t month_week(const Event& e) {
	std::uint32_t w = week_index(e.date);
	if (w == 4 && is_last_week(e.date) && (e.week_days & repeat::LAST_WEEK) != 0) {
		return 5;
	}
	return w;
}

std::int64_t first_day(const Event& e) {
	if (e.repeat != repeat::WEEK || e.week_days == 0 || (e.week_days & (std::int32_t{1} << days::dow(e.date))) != 0) {
		return e.date;
	}
	Event plain = e;
	plain.exceptions.clear();
	std::int64_t weeks = static_cast<std::int64_t>(std::max<std::int32_t>(e.every, 1)) + 1;
	std::int64_t stop = days::wrapping_add(e.date, days::wrapping_mul(7, weeks));
	for (std::int64_t d = e.date; d < stop; ++d) {
		if (occurs_on(plain, d)) {
			return d;
		}
	}
	return e.date;
}

bool occurs_on(const Event& e, std::int64_t day) {
	if (day < e.date) {
		return false;
	}
	if (e.repeat == repeat::NONE) {
		return day == e.date;
	}
	if (e.repeat_end >= 0 && day > e.repeat_end) {
		return false;
	}
	if (e.exceptions.contains(day)) {
		return false;
	}
	days::Ymd s = days::ymd(e.date);
	days::Ymd d = days::ymd(day);
	auto n = static_cast<std::int64_t>(std::max<std::int32_t>(e.every, 1));
	switch (e.repeat) {
	case repeat::DAY:
		return days::wrapping_sub(day, e.date) % n == 0;
	case repeat::WEEK: {
		std::int32_t mask = e.week_days == 0 ? (std::int32_t{1} << days::dow(e.date)) : e.week_days;
		if ((mask & (std::int32_t{1} << days::dow(day))) == 0) {
			return false;
		}
		std::int64_t week_of_start = days::wrapping_sub(e.date, static_cast<std::int64_t>(days::dow(e.date)));
		std::int64_t week_of_day = days::wrapping_sub(day, static_cast<std::int64_t>(days::dow(day)));
		return (days::wrapping_sub(week_of_day, week_of_start) / 7) % n == 0;
	}
	case repeat::MONTH_DATE:
		return d.day == s.day && months_between(e.date, day) % n == 0;
	case repeat::MONTH_DAY:
		if (days::dow(day) != days::dow(e.date) || months_between(e.date, day) % n != 0) {
			return false;
		}
		if (month_week(e) == 5) {
			return is_last_week(day);
		}
		return week_index(day) == week_index(e.date);
	case repeat::YEAR:
		return d.month == s.month && d.day == s.day && (d.year - s.year) % n == 0;
	default:
		return false;
	}
}

bool is_daily(const Event& e) {
	return (e.repeat == repeat::DAY && e.every == 1) ||
	       (e.repeat == repeat::WEEK && e.every == 1 && e.week_days == 0x7F);
}

std::int64_t last_day(const Event& e) {
	if (e.repeat == repeat::NONE) {
		return e.date;
	}
	if (e.repeat_end >= 0) {
		return e.repeat_end;
	}
	return std::numeric_limits<std::int64_t>::max();
}

std::string describe(const Event& e) {
	days::Ymd s = days::ymd(e.date);
	std::int32_t n = std::max<std::int32_t>(e.every, 1);
	std::string base;
	switch (e.repeat) {
	case repeat::NONE:
		return "None";
	case repeat::DAY:
		base = n == 1 ? std::string("Every day") : std::format("Every {} days", n);
		break;
	case repeat::WEEK: {
		std::int32_t mask = e.week_days == 0 ? (std::int32_t{1} << days::dow(e.date)) : e.week_days;
		std::string names;
		for (std::int32_t i = 0; i < 7; ++i) {
			if ((mask & (std::int32_t{1} << i)) != 0) {
				if (!names.empty()) {
					names += ", ";
				}
				names += DOW_SHORT[static_cast<std::size_t>(i)];
			}
		}
		base = n == 1 ? std::format("Every week on {}", names) : std::format("Every {} weeks on {}", n, names);
		break;
	}
	case repeat::MONTH_DAY:
		base = std::format("The {} {} of {}", ORDINAL_WEEK[month_week(e)], DOW_LONG[days::dow(e.date)],
		                   n == 1 ? std::string("every month") : std::format("every {} months", n));
		break;
	case repeat::MONTH_DATE:
		base = std::format("The {} of {}", ordinal(static_cast<std::int32_t>(s.day)),
		                   n == 1 ? std::string("every month") : std::format("every {} months", n));
		break;
	case repeat::YEAR:
		base = std::format("{}{} {}", n == 1 ? std::string("Every year on ") : std::format("Every {} years on ", n),
		                   MONTH_SHORT[s.month - 1], s.day);
		break;
	default:
		break;
	}
	if (e.repeat_end >= 0) {
		return std::format("{} until {}", base, short_date(e.repeat_end));
	}
	return base;
}

std::string_view short_name(const Event& e) {
	switch (e.repeat) {
	case repeat::DAY:
		return "Daily";
	case repeat::WEEK:
		return "Weekly";
	case repeat::MONTH_DAY:
	case repeat::MONTH_DATE:
		return "Monthly";
	case repeat::YEAR:
		return "Yearly";
	default:
		return "None";
	}
}

std::string ordinal(std::int32_t n) {
	std::string_view suf = "th";
	if (!(n >= 11 && n <= 13)) {
		switch (n % 10) {
		case 1:
			suf = "st";
			break;
		case 2:
			suf = "nd";
			break;
		case 3:
			suf = "rd";
			break;
		default:
			suf = "th";
			break;
		}
	}
	return std::format("{}{}", n, suf);
}

std::string day_title(std::int64_t day) {
	days::Ymd d = days::ymd(day);
	return std::format("{} {} {}", yy(d.year), MONTH_SHORT[d.month - 1], d.day);
}

std::string short_date(std::int64_t day) {
	days::Ymd d = days::ymd(day);
	return std::format("{}/{}/{}", d.month, d.day, yy(d.year));
}

std::string dow_date(std::int64_t day) {
	return std::format("{} {}", DOW_SHORT[days::dow(day)], short_date(day));
}

std::string time(std::int32_t min, bool h24) {
	std::int32_t h = (min / 60) % 24;
	std::int32_t m = min % 60;
	if (h24) {
		return std::format("{}:{:02}", h, m);
	}
	std::int32_t h12 = h % 12 == 0 ? 12 : h % 12;
	return std::format("{}:{:02} {}", h12, m, h < 12 ? "am" : "pm");
}

std::string time_short(std::int32_t min, bool h24) {
	std::int32_t h = (min / 60) % 24;
	std::int32_t m = min % 60;
	if (h24) {
		return std::format("{}:{:02}", h, m);
	}
	std::int32_t h12 = h % 12 == 0 ? 12 : h % 12;
	return std::format("{}:{:02}", h12, m);
}

std::string range(const Event& e, bool h24) {
	if (e.untimed) {
		return "No time";
	}
	return std::format("{} - {}", time(e.start, h24), time(e.end, h24));
}

std::pair<std::optional<Event>, Event> split_future(const Event& orig, std::int64_t occ_day, const Event& work) {
	std::optional<Event> shortened;
	if (occ_day > orig.date) {
		Event s = orig;
		s.repeat_end = days::wrapping_sub(occ_day, 1);
		std::erase_if(s.exceptions, [occ_day](std::int64_t x) { return x >= occ_day; });
		shortened = s;
	}
	std::int64_t shift = days::wrapping_sub(work.date, occ_day);
	Event series = work;
	series.id = 0;
	series.exceptions.clear();
	for (std::int64_t x : work.exceptions) {
		if (x >= occ_day) {
			series.exceptions.insert(days::wrapping_add(x, shift));
		}
	}
	return {shortened, series};
}

} // namespace palm::model
