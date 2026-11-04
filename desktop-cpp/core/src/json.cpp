// The lenient reads of org.json (optString, optInt, optLong, optBoolean, optJSONArray) over JSON values, as in
// desktop-rs/core/src/json.rs.

#include "palm/json.hpp"

#include "palm/text.hpp"

#include <algorithm>
#include <array>
#include <charconv>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <format>
#include <limits>
#include <optional>
#include <span>
#include <string>
#include <string_view>

namespace palm::json {

namespace {

// org.json's text of a non-string value: numbers and booleans as serde_json writes them, objects and arrays as JSON.
std::string text_of(const nlohmann::json& v) {
	if (v.is_string()) {
		return v.get<std::string>();
	}
	return dump(v);
}

// The value of a float, truncated toward zero and saturated to the int64 range, as Rust's `as` cast does.
std::int64_t trunc_saturate(double f) {
	if (std::isnan(f)) {
		return 0;
	}
	// 2^63 is exact in double; the upper bound is exclusive, the lower bound -2^63 is INT64_MIN exactly.
	if (f >= 9223372036854775808.0) {
		return std::numeric_limits<std::int64_t>::max();
	}
	if (f <= -9223372036854775808.0) {
		return std::numeric_limits<std::int64_t>::min();
	}
	return static_cast<std::int64_t>(f);
}

// True when a decimal text underflows rather than overflows: a negative exponent, or no exponent and no nonzero
// digit before the point. An exponent-less text out of range is otherwise an overflow.
bool underflows(std::string_view t) {
	std::size_t e = t.find_first_of("eE");
	if (e != std::string_view::npos) {
		return t.substr(e + 1).starts_with('-');
	}
	std::size_t point = t.find('.');
	return t.substr(0, point).find_first_of("123456789") == std::string_view::npos;
}

// A syntax error of Reader: serde_json's text of it, "<reason> at line <l> column <c>".
struct SyntaxError {
	std::string text;
};

// serde_json 1.0.154's from_str::<Value> (de.rs and read.rs, with its default features: no float_roundtrip, no
// arbitrary_precision), ported so a text reads to the same value and fails with the same error text and position.
// Floats are read as serde_json reads them without float_roundtrip (the significand times or over a power of ten),
// which can differ from the nearest double in the last bit. Errors throw SyntaxError.
class Reader {
public:
	explicit Reader(std::string_view s) : s_(s) {}

	// The value of the whole text: one value, then only whitespace.
	nlohmann::json read() {
		nlohmann::json v = value();
		if (whitespace()) {
			peek_error("trailing characters");
		}
		return v;
	}

private:
	std::string_view s_;
	std::size_t i_ = 0;
	// serde_json's recursion limit: the 128th level of arrays and objects fails.
	int remaining_depth_ = 128;

	std::optional<unsigned char> peek() const {
		if (i_ < s_.size()) {
			return static_cast<unsigned char>(s_[i_]);
		}
		return std::nullopt;
	}
	unsigned char peek_or_null() const { return peek().value_or(0); }
	std::optional<unsigned char> next() {
		if (i_ < s_.size()) {
			return static_cast<unsigned char>(s_[i_++]);
		}
		return std::nullopt;
	}

	// SliceRead::position_of_index: the line counts from 1, the column is the count of bytes after the last
	// newline before `index`.
	[[noreturn]] void fail_at(std::size_t index, std::string_view reason) const {
		const std::string_view before = s_.substr(0, index);
		const std::size_t nl = before.rfind('\n');
		const std::size_t start = nl == std::string_view::npos ? 0 : nl + 1;
		const auto line = 1 + std::ranges::count(s_.substr(0, start), '\n');
		throw SyntaxError{std::format("{} at line {} column {}", reason, line, index - start)};
	}
	// An error at the byte last read (Deserializer::error).
	[[noreturn]] void error(std::string_view reason) const { fail_at(i_, reason); }
	// An error at the byte last peeked (Deserializer::peek_error).
	[[noreturn]] void peek_error(std::string_view reason) const { fail_at(std::min(s_.size(), i_ + 1), reason); }

	// The first byte that is not JSON whitespace, unread, or nothing at the end.
	std::optional<unsigned char> whitespace() {
		while (true) {
			const auto c = peek();
			if (c && (*c == ' ' || *c == '\n' || *c == '\t' || *c == '\r')) {
				++i_;
			} else {
				return c;
			}
		}
	}

	nlohmann::json value() {
		const auto c = whitespace();
		if (!c) {
			peek_error("EOF while parsing a value");
		}
		switch (*c) {
		case 'n':
			++i_;
			ident("ull");
			return nullptr;
		case 't':
			++i_;
			ident("rue");
			return true;
		case 'f':
			++i_;
			ident("alse");
			return false;
		case '-':
			++i_;
			return integer(false);
		case '"':
			++i_;
			return string();
		case '[':
		case '{': {
			if (--remaining_depth_ == 0) {
				peek_error("recursion limit exceeded");
			}
			++i_;
			nlohmann::json v = *c == '[' ? array() : object();
			++remaining_depth_;
			return v;
		}
		default:
			if (*c >= '0' && *c <= '9') {
				return integer(true);
			}
			peek_error("expected value");
		}
	}

	void ident(std::string_view rest) {
		for (char expected : rest) {
			const auto c = next();
			if (!c) {
				error("EOF while parsing a value");
			}
			if (*c != static_cast<unsigned char>(expected)) {
				error("expected ident");
			}
		}
	}

	// SeqAccess, then end_seq.
	nlohmann::json array() {
		nlohmann::json out = nlohmann::json::array();
		bool first = true;
		while (true) {
			auto c = whitespace();
			if (!c) {
				peek_error("EOF while parsing a list");
			}
			if (*c == ']') {
				break;
			}
			if (first) {
				first = false;
			} else if (*c == ',') {
				++i_;
				c = whitespace();
				if (!c) {
					peek_error("EOF while parsing a value");
				}
				if (*c == ']') {
					peek_error("trailing comma");
				}
			} else {
				peek_error("expected `,` or `]`");
			}
			out.push_back(value());
		}
		++i_;
		return out;
	}

	// MapAccess, then end_map. A repeated key keeps its last value, as serde_json's Map does.
	nlohmann::json object() {
		nlohmann::json out = nlohmann::json::object();
		bool first = true;
		while (true) {
			auto c = whitespace();
			if (!c) {
				peek_error("EOF while parsing an object");
			}
			if (*c == '}') {
				break;
			}
			if (first) {
				first = false;
				if (*c != '"') {
					peek_error("key must be a string");
				}
			} else if (*c == ',') {
				++i_;
				c = whitespace();
				if (!c) {
					peek_error("EOF while parsing a value");
				}
				if (*c == '}') {
					peek_error("trailing comma");
				}
				if (*c != '"') {
					peek_error("key must be a string");
				}
			} else {
				peek_error("expected `,` or `}`");
			}
			++i_;
			std::string key = string();
			c = whitespace();
			if (!c) {
				peek_error("EOF while parsing an object");
			}
			if (*c != ':') {
				peek_error("expected `:`");
			}
			++i_;
			out[std::move(key)] = value();
		}
		++i_;
		return out;
	}

	// SliceRead::parse_str_bytes with validation, after the opening quote.
	std::string string() {
		std::string out;
		while (true) {
			const std::size_t start = i_;
			while (i_ < s_.size() && s_[i_] != '"' && s_[i_] != '\\' && static_cast<unsigned char>(s_[i_]) >= 0x20) {
				++i_;
			}
			out.append(s_.substr(start, i_ - start));
			if (i_ == s_.size()) {
				error("EOF while parsing a string");
			}
			const char c = s_[i_++];
			if (c == '"') {
				return out;
			}
			if (c != '\\') {
				error("control character (\\u0000-\\u001F) found while parsing a string");
			}
			escape(out);
		}
	}

	unsigned char next_or_eof() {
		const auto c = next();
		if (!c) {
			error("EOF while parsing a string");
		}
		return *c;
	}
	unsigned char peek_or_eof() const {
		const auto c = peek();
		if (!c) {
			error("EOF while parsing a string");
		}
		return *c;
	}

	void escape(std::string& out) {
		switch (next_or_eof()) {
		case '"':
			out += '"';
			break;
		case '\\':
			out += '\\';
			break;
		case '/':
			out += '/';
			break;
		case 'b':
			out += '\b';
			break;
		case 'f':
			out += '\f';
			break;
		case 'n':
			out += '\n';
			break;
		case 'r':
			out += '\r';
			break;
		case 't':
			out += '\t';
			break;
		case 'u':
			unicode_escape(out);
			break;
		default:
			error("invalid escape");
		}
	}

	// SliceRead::decode_hex_escape: four hex digits.
	std::uint16_t hex4() {
		if (s_.size() - i_ < 4) {
			i_ = s_.size();
			error("EOF while parsing a string");
		}
		std::uint16_t n = 0;
		bool ok = true;
		for (std::size_t k = 0; k < 4; ++k) {
			const char h = s_[i_ + k];
			int d = -1;
			if (h >= '0' && h <= '9') {
				d = h - '0';
			} else if (h >= 'a' && h <= 'f') {
				d = h - 'a' + 10;
			} else if (h >= 'A' && h <= 'F') {
				d = h - 'A' + 10;
			}
			ok = ok && d >= 0;
			n = static_cast<std::uint16_t>((n << 4) | (d & 0xF));
		}
		i_ += 4;
		if (!ok) {
			error("invalid escape");
		}
		return n;
	}

	// parse_unicode_escape with validation: a surrogate must be a leading one followed by an escaped trailing one.
	void unicode_escape(std::string& out) {
		const std::uint16_t n1 = hex4();
		if (n1 >= 0xDC00 && n1 <= 0xDFFF) {
			error("lone leading surrogate in hex escape");
		}
		if (n1 < 0xD800 || n1 > 0xDBFF) {
			text::append_utf8(out, n1);
			return;
		}
		for (char want : {'\\', 'u'}) {
			if (peek_or_eof() != static_cast<unsigned char>(want)) {
				++i_;
				error("unexpected end of hex escape");
			}
			++i_;
		}
		const std::uint16_t n2 = hex4();
		if (n2 < 0xDC00 || n2 > 0xDFFF) {
			error("lone leading surrogate in hex escape");
		}
		text::append_utf8(out, ((static_cast<char32_t>(n1 - 0xD800) << 10) | (n2 - 0xDC00)) + 0x10000);
	}

	// serde_json's overflow! check: whether a * 10 + b passes max.
	template <typename T> static bool overflows(T a, T b, T max) {
		return a >= max / 10 && (a > max / 10 || b > max % 10);
	}

	// parse_integer, after the sign.
	nlohmann::json integer(bool positive) {
		const auto c = next();
		if (!c) {
			error("EOF while parsing a value");
		}
		if (*c == '0') {
			const unsigned char p = peek_or_null();
			if (p >= '0' && p <= '9') {
				peek_error("invalid number");
			}
			return number(positive, 0);
		}
		if (*c < '1' || *c > '9') {
			error("invalid number");
		}
		std::uint64_t significand = *c - '0';
		while (true) {
			const unsigned char p = peek_or_null();
			if (p < '0' || p > '9') {
				return number(positive, significand);
			}
			const std::uint64_t digit = p - '0';
			if (overflows<std::uint64_t>(significand, digit, std::numeric_limits<std::uint64_t>::max())) {
				return long_integer(positive, significand);
			}
			++i_;
			significand = significand * 10 + digit;
		}
	}

	nlohmann::json number(bool positive, std::uint64_t significand) {
		const unsigned char p = peek_or_null();
		if (p == '.') {
			return decimal(positive, significand, 0);
		}
		if (p == 'e' || p == 'E') {
			return exponent(positive, significand, 0);
		}
		if (positive) {
			return significand;
		}
		// A negative that wraps to zero or above (-0 and below i64::MIN) becomes a float.
		const auto neg = static_cast<std::int64_t>(std::uint64_t{0} - significand);
		if (neg >= 0) {
			return -static_cast<double>(significand);
		}
		return neg;
	}

	double long_integer(bool positive, std::uint64_t significand) {
		int exp = 0;
		while (true) {
			const unsigned char p = peek_or_null();
			if (p >= '0' && p <= '9') {
				++i_;
				++exp;
			} else if (p == '.') {
				return decimal(positive, significand, exp);
			} else if (p == 'e' || p == 'E') {
				return exponent(positive, significand, exp);
			} else {
				return from_parts(positive, significand, exp);
			}
		}
	}

	double decimal(bool positive, std::uint64_t significand, int exp_before) {
		++i_;
		int exp_after = 0;
		while (true) {
			const unsigned char p = peek_or_null();
			if (p < '0' || p > '9') {
				break;
			}
			const std::uint64_t digit = p - '0';
			if (overflows<std::uint64_t>(significand, digit, std::numeric_limits<std::uint64_t>::max())) {
				// The digits past u64 are dropped.
				while (peek_or_null() >= '0' && peek_or_null() <= '9') {
					++i_;
				}
				const unsigned char q = peek_or_null();
				if (q == 'e' || q == 'E') {
					return exponent(positive, significand, exp_before + exp_after);
				}
				return from_parts(positive, significand, exp_before + exp_after);
			}
			++i_;
			significand = significand * 10 + digit;
			--exp_after;
		}
		if (exp_after == 0) {
			if (peek()) {
				peek_error("invalid number");
			}
			peek_error("EOF while parsing a value");
		}
		const int exp = exp_before + exp_after;
		const unsigned char q = peek_or_null();
		if (q == 'e' || q == 'E') {
			return exponent(positive, significand, exp);
		}
		return from_parts(positive, significand, exp);
	}

	double exponent(bool positive, std::uint64_t significand, int starting) {
		++i_;
		bool positive_exp = true;
		if (peek_or_null() == '+') {
			++i_;
		} else if (peek_or_null() == '-') {
			++i_;
			positive_exp = false;
		}
		const auto c = next();
		if (!c) {
			error("EOF while parsing a value");
		}
		if (*c < '0' || *c > '9') {
			error("invalid number");
		}
		int exp = *c - '0';
		while (peek_or_null() >= '0' && peek_or_null() <= '9') {
			const int digit = peek_or_null() - '0';
			++i_;
			if (overflows<int>(exp, digit, std::numeric_limits<int>::max())) {
				// parse_exponent_overflow: infinity is an error, anything else is zero.
				if (significand != 0 && positive_exp) {
					error("number out of range");
				}
				while (peek_or_null() >= '0' && peek_or_null() <= '9') {
					++i_;
				}
				return positive ? 0.0 : -0.0;
			}
			exp = exp * 10 + digit;
		}
		// saturating_add and saturating_sub of i32.
		const std::int64_t wide = positive_exp ? std::int64_t{starting} + exp : std::int64_t{starting} - exp;
		const auto final_exp = static_cast<int>(
		    std::clamp<std::int64_t>(wide, std::numeric_limits<int>::min(), std::numeric_limits<int>::max()));
		return from_parts(positive, significand, final_exp);
	}

	// f64_from_parts without float_roundtrip: the significand times or over 10^|exponent| from POW10 (1e0 to 1e308),
	// dividing by 1e308 first while the exponent is below -308.
	double from_parts(bool positive, std::uint64_t significand, int exp) const {
		static const std::array<double, 309> pow10 = [] {
			std::array<double, 309> t{};
			for (std::size_t k = 0; k < t.size(); ++k) {
				const std::string lit = "1e" + std::to_string(k);
				std::from_chars(lit.data(), lit.data() + lit.size(), t[k]);
			}
			return t;
		}();
		auto f = static_cast<double>(significand);
		while (true) {
			// wrapping_abs: i32::MIN stays negative and so is past the table.
			const std::int64_t mag = exp == std::numeric_limits<int>::min() ? -1 : (exp < 0 ? -std::int64_t{exp} : exp);
			if (mag >= 0 && mag < static_cast<std::int64_t>(pow10.size())) {
				if (exp >= 0) {
					f *= pow10[static_cast<std::size_t>(mag)];
					if (std::isinf(f)) {
						error("number out of range");
					}
				} else {
					f /= pow10[static_cast<std::size_t>(mag)];
				}
				break;
			}
			if (f == 0.0) {
				break;
			}
			if (exp >= 0) {
				error("number out of range");
			}
			f /= 1e308;
			exp += 308;
		}
		return positive ? f : -f;
	}
};

// org.json's number reading of a string: an integer, else a decimal truncated toward zero.
std::optional<std::int64_t> number_of_str(std::string_view s) {
	std::string_view t = s;
	// Rust's parse takes one leading '+', which from_chars does not; "+-1" stays an error.
	if (t.starts_with('+') && !t.starts_with("+-")) {
		t.remove_prefix(1);
	}
	const char* first = t.data();
	const char* last = t.data() + t.size();

	std::int64_t i = 0;
	auto [iptr, iec] = std::from_chars(first, last, i);
	if (iec == std::errc{} && iptr == last) {
		return i;
	}

	double f = 0.0;
	auto [fptr, fec] = std::from_chars(first, last, f);
	if (fec == std::errc{} && fptr == last && std::isfinite(f)) {
		return trunc_saturate(f);
	}
	// Rust's parse gives 0.0 for an underflow and infinity for an overflow, which is filtered out.
	if (fec == std::errc::result_out_of_range && fptr == last && underflows(t)) {
		return 0;
	}
	return std::nullopt;
}

// The value of a number or a numeric string as an int64, or nothing for any other value.
std::optional<std::int64_t> long_of(const nlohmann::json& v) {
	if (v.is_number_unsigned()) {
		// A u64 keeps its low 64 bits as an i64, as Rust's `u as i64` does.
		return static_cast<std::int64_t>(v.get<std::uint64_t>());
	}
	if (v.is_number_integer()) {
		return v.get<std::int64_t>();
	}
	if (v.is_number_float()) {
		return trunc_saturate(v.get<double>());
	}
	if (v.is_string()) {
		return number_of_str(v.get_ref<const std::string&>());
	}
	return std::nullopt;
}

// The value of a boolean or a "true" or "false" string (any letter case), or nothing for any other value.
std::optional<bool> bool_of(const nlohmann::json& v) {
	if (v.is_boolean()) {
		return v.get<bool>();
	}
	if (v.is_string()) {
		const std::string& s = v.get_ref<const std::string&>();
		if (text::eq_ignore_ascii_case(s, "true")) {
			return true;
		}
		if (text::eq_ignore_ascii_case(s, "false")) {
			return false;
		}
	}
	return std::nullopt;
}

// The value under a key of an object, or nullptr when the key is missing or the value is not an object.
const nlohmann::json* find_key(const Obj& o, std::string_view key) {
	if (!o.is_object()) {
		return nullptr;
	}
	auto it = o.find(std::string(key));
	return it == o.end() ? nullptr : &*it;
}

} // namespace

// optString(key): "" when the key is missing or null.
std::string opt_str(const Obj& o, std::string_view key) {
	return opt_str_or(o, key, "");
}

// optString(key, default): the default when the key is missing or null.
std::string opt_str_or(const Obj& o, std::string_view key, std::string_view default_value) {
	const nlohmann::json* v = find_key(o, key);
	if (v == nullptr || v->is_null()) {
		return std::string(default_value);
	}
	return text_of(*v);
}

// optInt(key, default). A long value keeps its low 32 bits, as Java's intValue.
std::int32_t opt_i32(const Obj& o, std::string_view key, std::int32_t default_value) {
	const nlohmann::json* v = find_key(o, key);
	std::optional<std::int64_t> l = v ? long_of(*v) : std::nullopt;
	return l ? static_cast<std::int32_t>(*l) : default_value;
}

// optLong(key, default).
std::int64_t opt_i64(const Obj& o, std::string_view key, std::int64_t default_value) {
	const nlohmann::json* v = find_key(o, key);
	std::optional<std::int64_t> l = v ? long_of(*v) : std::nullopt;
	return l ? *l : default_value;
}

// optBoolean(key): false when the key is missing or not a boolean.
bool opt_bool(const Obj& o, std::string_view key) {
	const nlohmann::json* v = find_key(o, key);
	std::optional<bool> b = v ? bool_of(*v) : std::nullopt;
	return b ? *b : false;
}

// optJSONArray(key): the array under the key, or nullptr when it is missing or not an array.
const nlohmann::json::array_t* opt_array(const Obj& o, std::string_view key) {
	const nlohmann::json* v = find_key(o, key);
	if (v == nullptr || !v->is_array()) {
		return nullptr;
	}
	return &v->get_ref<const nlohmann::json::array_t&>();
}

// JSONArray.optInt(i, default).
std::int32_t at_i32(std::span<const nlohmann::json> a, std::size_t i, std::int32_t default_value) {
	std::optional<std::int64_t> l = i < a.size() ? long_of(a[i]) : std::nullopt;
	return l ? static_cast<std::int32_t>(*l) : default_value;
}

// JSONArray.optLong(i): 0 when missing.
std::int64_t at_i64(std::span<const nlohmann::json> a, std::size_t i) {
	std::optional<std::int64_t> l = i < a.size() ? long_of(a[i]) : std::nullopt;
	return l ? *l : 0;
}

// JSONArray.optString(i, default).
std::string at_str(std::span<const nlohmann::json> a, std::size_t i, std::string_view default_value) {
	if (i >= a.size() || a[i].is_null()) {
		return std::string(default_value);
	}
	return text_of(a[i]);
}

// getLong(key): a number or a numeric string, else an error.
std::expected<std::int64_t, std::string> get_i64(const Obj& o, std::string_view key) {
	const nlohmann::json* v = find_key(o, key);
	std::optional<std::int64_t> l = v ? long_of(*v) : std::nullopt;
	if (!l) {
		return std::unexpected(std::format("JSONObject[\"{}\"] is not a number.", key));
	}
	return *l;
}

// getString(key): a string, else an error.
std::expected<std::string, std::string> get_str(const Obj& o, std::string_view key) {
	const nlohmann::json* v = find_key(o, key);
	if (v == nullptr || !v->is_string()) {
		return std::unexpected(std::format("JSONObject[\"{}\"] is not a string.", key));
	}
	return v->get<std::string>();
}

// The text of a finite double as serde_json 1.0.154 writes it (zmij): the shortest digits that read back to the
// same value, in plain decimal when the point falls within 16 digits left or 5 digits right of the first digit
// ("1000000000000000.0", "0.00001234"), else in exponent form with a signed, unpadded exponent ("1e+16",
// "1.5e-6"). A whole number keeps ".0". NaN and infinity, which no parsed value holds, are null.
std::string format_double(double f) {
	if (!std::isfinite(f)) {
		return "null";
	}
	// to_chars gives the shortest round-trip digits in "d.ddde+XX" form; the digits and exponent are taken apart.
	char buf[32];
	const auto r = std::to_chars(buf, buf + sizeof buf, f, std::chars_format::scientific);
	std::string_view sci(buf, static_cast<std::size_t>(r.ptr - buf));
	std::string out;
	if (sci.starts_with('-')) {
		out += '-';
		sci.remove_prefix(1);
	}
	const std::size_t e_at = sci.find('e');
	std::string digits;
	for (char c : sci.substr(0, e_at)) {
		if (c != '.') {
			digits += c;
		}
	}
	int exp = 0;
	std::string_view e_text = sci.substr(e_at + 1);
	if (e_text.starts_with('+')) {
		e_text.remove_prefix(1);
	}
	std::from_chars(e_text.data(), e_text.data() + e_text.size(), exp);
	// The point falls after `point` digits: 10^(point-1) <= |f| < 10^point.
	const int point = exp + 1;
	const int len = static_cast<int>(digits.size());
	if (point > -5 && point <= 16) {
		if (point <= 0) {
			out += "0.";
			out.append(static_cast<std::size_t>(-point), '0');
			out += digits;
		} else if (point >= len) {
			out += digits;
			out.append(static_cast<std::size_t>(point - len), '0');
			out += ".0";
		} else {
			out += digits.substr(0, static_cast<std::size_t>(point));
			out += '.';
			out += digits.substr(static_cast<std::size_t>(point));
		}
		return out;
	}
	out += digits[0];
	if (len > 1) {
		out += '.';
		out += digits.substr(1);
	}
	out += exp < 0 ? "e-" : "e+";
	out += std::to_string(exp < 0 ? -exp : exp);
	return out;
}

namespace {

// Writes one value as dump does: nlohmann's layout, which matches serde_json's (no spaces on one line; with an
// indent, one member per line and ": " after each key), with floats in serde_json's form.
void write_value(std::string& out, const nlohmann::json& v, int indent, int depth) {
	const bool pretty = indent >= 0;
	auto newline = [&](int level) {
		if (pretty) {
			out += '\n';
			out.append(static_cast<std::size_t>(indent * level), ' ');
		}
	};
	if (v.is_object() || v.is_array()) {
		const bool obj = v.is_object();
		if (v.empty()) {
			out += obj ? "{}" : "[]";
			return;
		}
		out += obj ? '{' : '[';
		bool first = true;
		for (auto it = v.begin(); it != v.end(); ++it) {
			if (!first) {
				out += ',';
			}
			first = false;
			newline(depth + 1);
			if (obj) {
				out += nlohmann::json(it.key()).dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
				out += pretty ? ": " : ":";
			}
			write_value(out, *it, indent, depth + 1);
		}
		newline(depth);
		out += obj ? '}' : ']';
	} else if (v.is_number_float()) {
		out += format_double(v.get<double>());
	} else {
		out += v.dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
	}
}

} // namespace

std::string dump(const nlohmann::json& v, int indent) {
	std::string out;
	write_value(out, v, indent, 0);
	return out;
}

std::expected<nlohmann::json, std::string> parse_value(std::string_view text) {
	try {
		return Reader(text).read();
	} catch (const SyntaxError& e) {
		return std::unexpected(e.text);
	}
}

std::expected<Obj, std::string> parse_obj(std::string_view text) {
	auto v = parse_value(text);
	if (!v) {
		return std::unexpected(v.error());
	}
	if (!v->is_object()) {
		return std::unexpected(std::string("A JSONObject text must begin with '{'."));
	}
	return *std::move(v);
}

} // namespace palm::json
