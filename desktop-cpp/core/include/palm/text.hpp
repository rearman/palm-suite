// Text helpers for what Rust's str and char have built in: ASCII case, UTF-8 code points, White_Space,
// trim, and case mapping. Several modules use them.
#pragma once

#include <algorithm>
#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

namespace palm::text {

// ASCII lowercase of one byte; other bytes stay as they are.
constexpr char ascii_lower(char c) {
	return (c >= 'A' && c <= 'Z') ? static_cast<char>(c + ('a' - 'A')) : c;
}

// Equality of two strings with ASCII letters folded, as Rust's str::eq_ignore_ascii_case.
constexpr bool eq_ignore_ascii_case(std::string_view a, std::string_view b) {
	return std::ranges::equal(a, b, [](char x, char y) { return ascii_lower(x) == ascii_lower(y); });
}

// The code point at byte `i` of UTF-8 text, and its length in bytes.
struct Decoded {
	char32_t cp;
	std::size_t len;
};

// Decodes the code point at byte `i`; a malformed byte reads as itself, one byte long.
Decoded decode_at(std::string_view s, std::size_t i);

// Appends the UTF-8 bytes of a code point.
void append_utf8(std::string& out, char32_t cp);

// Whether a code point has the Unicode White_Space property, as Rust's char::is_whitespace.
bool is_white_space(char32_t c);

// Rust's str::trim: the text trimmed of White_Space code points at both ends.
std::string_view trim(std::string_view s);

// The single-code-point upper and lower case of a code point as Rust's char::to_uppercase and to_lowercase
// give them, or the code point itself when the mapping yields several. They cover ASCII, Latin-1 Supplement,
// and Latin Extended-A; other code points keep their case, which is the known limit. The upgrade path is
// ICU's simple case mapping (u_toupper and u_tolower).
char32_t upper_one(char32_t c);
char32_t lower_one(char32_t c);

// Rust's str::to_lowercase and str::to_uppercase over the characters of lower_one and upper_one, with the
// multi-code-point cases U+0130 (lowercase "i" and U+0307), U+00DF (uppercase "SS"), and U+0149 (uppercase
// U+02BC and "N").
std::string to_lowercase(std::string_view s);
std::string to_uppercase(std::string_view s);

// Rust's str::split at a one-byte separator: every part, the empty ones too; an empty text gives one empty part.
std::vector<std::string_view> split(std::string_view s, char sep);

// Rust's String::from_utf8_lossy: bytes as UTF-8, each invalid sequence one U+FFFD (the maximal-subpart rule).
std::string from_utf8_lossy(std::string_view bytes);

} // namespace palm::text
