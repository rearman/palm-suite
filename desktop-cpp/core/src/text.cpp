#include "palm/text.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iterator>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace palm::text {

// The code point at byte `i` of UTF-8 text, and its length in bytes. Rust strings are valid UTF-8, so the
// malformed branch serves only bytes that did not come from a Rust string: such a byte reads as itself.
Decoded decode_at(std::string_view s, std::size_t i) {
	auto b0 = static_cast<unsigned char>(s[i]);
	if (b0 < 0x80) {
		return Decoded{b0, 1};
	}
	std::size_t n = 0;
	char32_t cp = 0;
	if (b0 >= 0xC2 && b0 <= 0xDF) {
		n = 2;
		cp = b0 & 0x1F;
	} else if (b0 >= 0xE0 && b0 <= 0xEF) {
		n = 3;
		cp = b0 & 0x0F;
	} else if (b0 >= 0xF0 && b0 <= 0xF4) {
		n = 4;
		cp = b0 & 0x07;
	} else {
		return Decoded{b0, 1};
	}
	if (i + n > s.size()) {
		return Decoded{b0, 1};
	}
	for (std::size_t k = 1; k < n; ++k) {
		auto c = static_cast<unsigned char>(s[i + k]);
		if ((c & 0xC0) != 0x80) {
			return Decoded{b0, 1};
		}
		cp = (cp << 6) | (c & 0x3F);
	}
	return Decoded{cp, n};
}

// Appends the UTF-8 bytes of one code point.
void append_utf8(std::string& out, char32_t cp) {
	if (cp < 0x80) {
		out += static_cast<char>(cp);
	} else if (cp < 0x800) {
		out += static_cast<char>(0xC0 | (cp >> 6));
		out += static_cast<char>(0x80 | (cp & 0x3F));
	} else if (cp < 0x10000) {
		out += static_cast<char>(0xE0 | (cp >> 12));
		out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
		out += static_cast<char>(0x80 | (cp & 0x3F));
	} else {
		out += static_cast<char>(0xF0 | (cp >> 18));
		out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
		out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
		out += static_cast<char>(0x80 | (cp & 0x3F));
	}
}

// White_Space, as Rust's char::is_whitespace.
bool is_white_space(char32_t c) {
	return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680 ||
	       (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
}

namespace {

// Code points first to last, `step` apart, that map to themselves plus `delta`.
struct CaseRun {
	char32_t first;
	char32_t last;
	std::uint32_t step;
	std::int32_t delta;
};

// A code point whose mapping is several code points, as UTF-8.
struct CaseMulti {
	char32_t cp;
	const char* utf8;
};

// An inclusive range of code points.
struct CpRange {
	char32_t first;
	char32_t last;
};

// LOWER_RUNS, UPPER_RUNS, LOWER_MULTI, UPPER_MULTI, CASE_IGNORABLE, and CASED, generated from Rust's standard library.
#include "text_case.inc"

// The single-code-point mapping of `c` in the runs, or `c` when it has none.
template <std::size_t N> char32_t map_one(const std::array<CaseRun, N>& runs, char32_t c) {
	const auto it = std::ranges::upper_bound(runs, c, {}, &CaseRun::first);
	if (it == runs.begin()) {
		return c;
	}
	const CaseRun& r = *std::prev(it);
	if (c > r.last || (c - r.first) % r.step != 0) {
		return c;
	}
	return static_cast<char32_t>(static_cast<std::int64_t>(c) + r.delta);
}

// The several-code-point mapping of `c`, or nullptr when it has none.
template <std::size_t N> const char* map_multi(const std::array<CaseMulti, N>& list, char32_t c) {
	const auto it = std::ranges::lower_bound(list, c, {}, &CaseMulti::cp);
	return it != list.end() && it->cp == c ? it->utf8 : nullptr;
}

template <std::size_t N> bool in_ranges(const std::array<CpRange, N>& ranges, char32_t c) {
	const auto it = std::ranges::upper_bound(ranges, c, {}, &CpRange::first);
	return it != ranges.begin() && c <= std::prev(it)->last;
}

// Rust's case_ignorable_then_cased over the code points of `s` that `next` yields: the first one that is not
// Case_Ignorable is Cased.
template <typename Next> bool case_ignorable_then_cased(Next next) {
	while (const std::optional<char32_t> c = next()) {
		if (!in_ranges(CASE_IGNORABLE, *c)) {
			return in_ranges(CASED, *c);
		}
	}
	return false;
}

// The start of the code point that ends at byte `end` (above `floor`), found by stepping back over continuation
// bytes, no further than `floor`.
std::size_t start_before(std::string_view s, std::size_t end, std::size_t floor) {
	std::size_t start = end - 1;
	while (start > floor && (static_cast<unsigned char>(s[start]) & 0xC0) == 0x80) {
		--start;
	}
	return start;
}

// Rust's map_uppercase_sigma: U+03A3 at byte `at` of `s` lowercases to the final sigma when a cased letter comes
// before it and none after it, case-ignorable code points skipped on both sides.
bool final_sigma(std::string_view s, std::size_t at) {
	std::size_t back = at;
	const bool before = case_ignorable_then_cased([&]() -> std::optional<char32_t> {
		if (back == 0) {
			return std::nullopt;
		}
		std::size_t start = start_before(s, back, 0);
		Decoded d = decode_at(s, start);
		// A malformed tail reads as its last byte.
		if (start + d.len != back) {
			start = back - 1;
			d = decode_at(s, start);
		}
		back = start;
		return d.cp;
	});
	std::size_t ahead = at + 2;
	const bool after = case_ignorable_then_cased([&]() -> std::optional<char32_t> {
		if (ahead >= s.size()) {
			return std::nullopt;
		}
		const Decoded d = decode_at(s, ahead);
		ahead += d.len;
		return d.cp;
	});
	return before && !after;
}

// Rust's str::to_lowercase or to_uppercase. A byte that is not UTF-8 (which a Rust str cannot hold) passes through.
template <typename Map> std::string map_case(std::string_view s, Map map) {
	std::string out;
	out.reserve(s.size());
	std::size_t i = 0;
	while (i < s.size()) {
		const Decoded d = decode_at(s, i);
		if (d.len == 1 && d.cp >= 0x80) {
			out += s[i];
		} else {
			map(out, d.cp, i);
		}
		i += d.len;
	}
	return out;
}

} // namespace

// The single-code-point uppercase of `c` as Rust's char::to_uppercase gives it, or `c` itself when that mapping
// yields several code points.
char32_t upper_one(char32_t c) {
	return map_one(UPPER_RUNS, c);
}

// The single-code-point lowercase of `c` as Rust's char::to_lowercase gives it, or `c` itself when that mapping
// yields several code points (U+0130 gives "i" and U+0307).
char32_t lower_one(char32_t c) {
	return map_one(LOWER_RUNS, c);
}

// Rust's str::to_lowercase, with its final-sigma rule.
std::string to_lowercase(std::string_view s) {
	return map_case(s, [s](std::string& out, char32_t cp, std::size_t at) {
		if (cp == 0x3A3) {
			append_utf8(out, final_sigma(s, at) ? 0x3C2 : 0x3C3);
		} else if (const char* multi = map_multi(LOWER_MULTI, cp)) {
			out += multi;
		} else {
			append_utf8(out, lower_one(cp));
		}
	});
}

// Rust's str::trim: the text trimmed of White_Space code points at both ends.
std::string_view trim(std::string_view s) {
	std::size_t b = 0;
	while (b < s.size()) {
		Decoded d = decode_at(s, b);
		if (!is_white_space(d.cp)) {
			break;
		}
		b += d.len;
	}
	std::size_t e = s.size();
	while (e > b) {
		const std::size_t start = start_before(s, e, b);
		if (!is_white_space(decode_at(s, start).cp)) {
			break;
		}
		e = start;
	}
	return s.substr(b, e - b);
}

// Rust's str::to_uppercase.
std::string to_uppercase(std::string_view s) {
	return map_case(s, [](std::string& out, char32_t cp, std::size_t) {
		if (const char* multi = map_multi(UPPER_MULTI, cp)) {
			out += multi;
		} else {
			append_utf8(out, upper_one(cp));
		}
	});
}

// Rust's str::split with a one-byte separator: always at least one piece, and an empty piece at each end
// or between two separators.
std::vector<std::string_view> split(std::string_view s, char sep) {
	std::vector<std::string_view> out;
	std::size_t start = 0;
	for (;;) {
		std::size_t pos = s.find(sep, start);
		if (pos == std::string_view::npos) {
			out.push_back(s.substr(start));
			break;
		}
		out.push_back(s.substr(start, pos - start));
		start = pos + 1;
	}
	return out;
}

// The non-whitespace runs of `s`, split at the White_Space code points as Rust's str::split_whitespace does.
std::vector<std::string_view> split_whitespace(std::string_view s) {
	std::vector<std::string_view> out;
	std::size_t start = 0;
	bool in_word = false;
	for (std::size_t i = 0; i < s.size();) {
		const Decoded d = decode_at(s, i);
		if (is_white_space(d.cp)) {
			if (in_word) {
				out.push_back(s.substr(start, i - start));
				in_word = false;
			}
		} else if (!in_word) {
			start = i;
			in_word = true;
		}
		i += d.len;
	}
	if (in_word) {
		out.push_back(s.substr(start));
	}
	return out;
}

// Rust's String::from_utf8_lossy: the text of bytes as UTF-8, each invalid sequence one U+FFFD by the
// maximal-subpart rule.
std::string from_utf8_lossy(std::string_view bytes) {
	constexpr std::string_view REPLACEMENT = "\xEF\xBF\xBD";
	std::string out;
	out.reserve(bytes.size());
	std::size_t i = 0;
	while (i < bytes.size()) {
		const auto lead = static_cast<std::uint8_t>(bytes[i]);
		if (lead < 0x80) {
			out.push_back(static_cast<char>(lead));
			++i;
			continue;
		}
		// The length of the sequence, and the range of its second byte, from the table of well-formed UTF-8.
		std::size_t width = 0;
		std::uint8_t second_lo = 0x80;
		std::uint8_t second_hi = 0xBF;
		if (lead >= 0xC2 && lead <= 0xDF) {
			width = 2;
		} else if (lead == 0xE0) {
			width = 3;
			second_lo = 0xA0;
		} else if ((lead >= 0xE1 && lead <= 0xEC) || lead == 0xEE || lead == 0xEF) {
			width = 3;
		} else if (lead == 0xED) {
			width = 3;
			second_hi = 0x9F;
		} else if (lead == 0xF0) {
			width = 4;
			second_lo = 0x90;
		} else if (lead >= 0xF1 && lead <= 0xF3) {
			width = 4;
		} else if (lead == 0xF4) {
			width = 4;
			second_hi = 0x8F;
		}
		if (width == 0) {
			out += REPLACEMENT;
			++i;
			continue;
		}
		// The count of leading bytes of this sequence that are valid so far.
		std::size_t valid = 1;
		while (valid < width && i + valid < bytes.size()) {
			const auto b = static_cast<std::uint8_t>(bytes[i + valid]);
			const std::uint8_t lo = valid == 1 ? second_lo : 0x80;
			const std::uint8_t hi = valid == 1 ? second_hi : 0xBF;
			if (b < lo || b > hi) {
				break;
			}
			++valid;
		}
		if (valid == width) {
			out.append(bytes.substr(i, width));
			i += width;
		} else {
			out += REPLACEMENT;
			i += valid;
		}
	}
	return out;
}

// from_utf8_lossy replaces every invalid byte, so its result equals the input only when the input is valid UTF-8.
bool is_utf8(std::string_view bytes) {
	return from_utf8_lossy(bytes) == bytes;
}

} // namespace palm::text
