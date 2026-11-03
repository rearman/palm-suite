// The lenient reads of org.json (optString, optInt, optLong, optBoolean, optJSONArray) over JSON values, as in
// desktop-rs/core/src/json.rs.

#include "palm/json.hpp"

#include "palm/text.hpp"

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

// org.json's text of a non-string value: numbers and booleans as written, objects and arrays as JSON.
// Known limit: a float prints in nlohmann's form ("1e+20", "1e-07"), where serde_json prints ryu's ("1e20", "1e-7")
// and Kotlin's org.json prints Java's ("1.0E20"). No test vector covers a float. Upgrade path: format the double here
// with its shortest round-trip digits in the exponent form of the target.
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

// serde_json's recursion limit: 128 levels of arrays and objects counted together, so 127 parse and the 128th fails.
constexpr int max_depth = 127;

// True when the text nests deeper than max_depth. It scans the bytes before nlohmann parses, so a deep value never
// reaches nlohmann's recursive destructor, and it skips string contents and their escapes.
bool too_deep(std::string_view text) {
	int depth = 0;
	bool in_string = false;
	for (std::size_t i = 0; i < text.size(); ++i) {
		char c = text[i];
		if (in_string) {
			if (c == '\\') {
				++i;
			} else if (c == '"') {
				in_string = false;
			}
		} else if (c == '"') {
			in_string = true;
		} else if (c == '[' || c == '{') {
			if (++depth > max_depth) {
				return true;
			}
		} else if (c == ']' || c == '}') {
			--depth;
		}
	}
	return false;
}

// org.json's number reading of a string: an integer, else a decimal truncated toward zero.
std::optional<std::int64_t> number_of_str(std::string_view s) {
	// Rust's parse accepts one leading '+'; from_chars does not, so it is dropped here.
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

// The JSON object of a text, or an error when the text is not one.
std::string dump(const nlohmann::json& v, int indent) {
	return v.dump(indent, ' ', false, nlohmann::json::error_handler_t::replace);
}

std::expected<nlohmann::json, std::string> parse_value(std::string_view text) {
	if (too_deep(text)) {
		return std::unexpected(std::string("recursion limit exceeded"));
	}
	try {
		return nlohmann::json::parse(text.begin(), text.end());
	} catch (const nlohmann::json::exception& e) {
		return std::unexpected(std::string(e.what()));
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
