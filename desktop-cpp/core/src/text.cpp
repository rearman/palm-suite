#include "palm/text.hpp"

#include <cstddef>
#include <cstdint>
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

// The single-code-point uppercase of `c` as Rust's char::to_uppercase gives it, or `c` itself when that
// mapping yields several code points (U+00DF gives "SS" and U+0149 gives two). The known limit is the
// characters beyond Latin Extended-A, which keep their case; the upgrade path is ICU's simple case mapping (u_toupper
// and u_tolower).
char32_t upper_one(char32_t c) {
	if (c < 0x80) {
		return (c >= 'a' && c <= 'z') ? c - 0x20 : c;
	}
	if (c >= 0xE0 && c <= 0xFE && c != 0xF7) {
		return c - 0x20;
	}
	if (c == 0xFF) {
		return 0x178;
	}
	if (c == 0xB5) {
		return 0x39C;
	}
	if (c == 0x131) {
		return 'I';
	}
	if (c == 0x17F) {
		return 'S';
	}
	if (c < 0x100 || c > 0x17F) {
		return c;
	}
	// Latin Extended-A: the odd code point is the lowercase of its pair in 0100 to 0137 and 014A to 0177,
	// the even one in 0139 to 0148 and 0179 to 017E. 0130, 0138, 0149 and 0178 have no pair.
	bool odd_lower = (c <= 0x137) || (c >= 0x14A && c <= 0x177);
	bool even_lower = (c >= 0x139 && c <= 0x148) || (c >= 0x179 && c <= 0x17E);
	if (odd_lower && c % 2 == 1) {
		return c - 1;
	}
	if (even_lower && c % 2 == 0) {
		return c - 1;
	}
	return c;
}

// The single-code-point lowercase of `c` as Rust's char::to_lowercase gives it, or `c` itself when that
// mapping yields several code points (U+0130 gives "i" and U+0307). The known limit is as in upper_one.
char32_t lower_one(char32_t c) {
	if (c < 0x80) {
		return (c >= 'A' && c <= 'Z') ? c + 0x20 : c;
	}
	if (c >= 0xC0 && c <= 0xDE && c != 0xD7) {
		return c + 0x20;
	}
	if (c == 0x178) {
		return 0xFF;
	}
	if (c < 0x100 || c > 0x17F) {
		return c;
	}
	if (c == 0x130 || c == 0x131 || c == 0x138 || c == 0x149 || c == 0x17F) {
		return c;
	}
	// The even code point is the uppercase of its pair in 0100 to 0137 and 014A to 0177, the odd one in
	// 0139 to 0148 and 0179 to 017E.
	bool even_upper = (c <= 0x137) || (c >= 0x14A && c <= 0x177);
	bool odd_upper = (c >= 0x139 && c <= 0x148) || (c >= 0x179 && c <= 0x17E);
	if (even_upper && c % 2 == 0) {
		return c + 1;
	}
	if (odd_upper && c % 2 == 1) {
		return c + 1;
	}
	return c;
}

// The lowercase of a whole string, as Rust's str::to_lowercase gives it for the same characters. The Greek
// final-sigma rule is not reproduced, since Greek is beyond the mapping (see upper_one).
std::string to_lowercase(std::string_view s) {
	std::string out;
	std::size_t i = 0;
	while (i < s.size()) {
		Decoded d = decode_at(s, i);
		i += d.len;
		if (d.cp == 0x130) {
			out += 'i';
			append_utf8(out, 0x307);
		} else {
			append_utf8(out, lower_one(d.cp));
		}
	}
	return out;
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
		std::size_t start = e - 1;
		while (start > b && (static_cast<unsigned char>(s[start]) & 0xC0) == 0x80) {
			--start;
		}
		if (!is_white_space(decode_at(s, start).cp)) {
			break;
		}
		e = start;
	}
	return s.substr(b, e - b);
}

// Rust's str::to_uppercase over the characters of upper_one. U+00DF gives "SS" and U+0149 gives U+02BC U+004E, as in
// Rust.
std::string to_uppercase(std::string_view s) {
	std::string out;
	std::size_t i = 0;
	while (i < s.size()) {
		Decoded d = decode_at(s, i);
		i += d.len;
		if (d.cp == 0xDF) {
			out += "SS";
		} else if (d.cp == 0x149) {
			// U+0149 uppercases to U+02BC U+004E in Rust.
			append_utf8(out, 0x2BC);
			out += 'N';
		} else {
			append_utf8(out, upper_one(d.cp));
		}
	}
	return out;
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
